package com.vanta.app.data.ai

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Background model downloader for Gemma 4 E2B (`google/gemma-4-E2B-it`).
 *
 * Features:
 * - Android DownloadManager for resilient background download with resume support
 * - Storage check to ensure sufficient space before downloading
 * - Real-time progress broadcasting (downloaded bytes, total bytes, percent)
 * - Automatic model warm-up upon download completion
 */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
class ModelDownloadManager private constructor(private val context: Context) {

    enum class DownloadStatus {
        IDLE,
        CHECKING_STORAGE,
        DOWNLOADING,
        WARMING_UP,
        COMPLETED,
        ERROR,
        CANCELLED
    }

    data class Progress(
        val status: DownloadStatus = DownloadStatus.IDLE,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = OnDeviceLlmManager.ESTIMATED_SIZE_BYTES,
        val percent: Int = 0,
        val errorMessage: String? = null
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var downloadId: Long = -1L
    private var pollingJob: Job? = null
    private var parallelJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "ModelDownload"
        // Number of concurrent byte-range connections. Higher = faster big-file downloads.
        private const val DOWNLOAD_THREADS = 6
        // Verified direct Gemma 4 E2B LiteRT-LM (vision+text) download URL.
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

        @Volatile
        private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // The RECEIVER_NOT_EXPORTED flag only exists from API 33; below that the 2-arg
    // form is the correct (and only) call. Lint can't prove the SDK guard, so it is
    // suppressed (class-level) — the runtime behavior is already safe on every API level.
    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                if (id == downloadId && downloadId != -1L) {
                    handleDownloadCompleted(id)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // Check if model is already ready
        val llm = OnDeviceLlmManager.getInstance(context)
        if (llm.isModelDownloaded()) {
            _progress.value = Progress(
                status = DownloadStatus.COMPLETED,
                bytesDownloaded = OnDeviceLlmManager.ESTIMATED_SIZE_BYTES,
                totalBytes = OnDeviceLlmManager.ESTIMATED_SIZE_BYTES,
                percent = 100
            )
        }
    }

    fun getAvailableStorageBytes(): Long {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    fun startDownload(customUrl: String? = null, hfToken: String? = null) {
        val availableBytes = getAvailableStorageBytes()
        if (availableBytes < OnDeviceLlmManager.ESTIMATED_SIZE_BYTES + 300_000_000L) { // Need at least ~1.5 GB free
            val freeMb = availableBytes / (1024 * 1024)
            _progress.value = Progress(
                status = DownloadStatus.ERROR,
                errorMessage = "Insufficient storage space ($freeMb MB free). At least 1.5 GB required."
            )
            return
        }

        val url = customUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_URL
        val targetFile = OnDeviceLlmManager.getModelFile(context)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        try {
            cancelSystemDownloads()
            OnDeviceLlmManager.getInstance(context).setDownloadingState()
            OnDeviceLlmManager.getInstance(context).setDownloadInProgress(true)
            _progress.value = Progress(status = DownloadStatus.DOWNLOADING, percent = 0)
            closeActiveDownloads()
            startParallelDownload(url, hfToken)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download: ${e.message}", e)
            _progress.value = Progress(status = DownloadStatus.ERROR, errorMessage = e.message)
        }
    }

    /** Cancels any lingering system-level DownloadManager downloads for this app. */
    private fun cancelSystemDownloads() {
        try {
            val filter = DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING
            )
            downloadManager.query(filter)?.use { c ->
                while (c.moveToNext()) {
                    val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                    if (uri != null && (uri.contains("huggingface") || uri.contains(".gguf"))) {
                        downloadManager.remove(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelSystemDownloads: ${e.message}")
        }
    }

    private fun closeActiveDownloads() {
        pollingJob?.cancel()
        parallelJob?.cancel()
        runCatching { File(OnDeviceLlmManager.getModelFile(context).parentFile, ".vanta-dl").deleteRecursively() }
    }

    /** Downloads the GGUF across [DOWNLOAD_THREADS] concurrent HTTP byte-range connections. */
    private fun startParallelDownload(url: String, hfToken: String?) {
        val targetFile = OnDeviceLlmManager.getModelFile(context)
        val partDir = File(targetFile.parentFile, ".vanta-dl")
        runCatching { partDir.deleteRecursively() }
        partDir.mkdirs()

        parallelJob = scope.launch {
            val totalBytes = runCatching { probeContentLength(url, hfToken) }
                .getOrDefault(OnDeviceLlmManager.ESTIMATED_SIZE_BYTES)
                .takeIf { it > 0L } ?: OnDeviceLlmManager.ESTIMATED_SIZE_BYTES

            val done = AtomicLong(0L)
            val total = AtomicLong(totalBytes)
            val failed = AtomicBoolean(false)
            val threads = DOWNLOAD_THREADS
            val chunkSize = (totalBytes + threads - 1) / threads
            val parts = (0 until threads).map { File(partDir, "part-$it") }

            val ticker = launch {
                while (isActive) {
                    val d = done.get()
                    val t = total.get()
                    val pct = if (t > 0L) ((d * 100) / t).toInt().coerceIn(0, 99) else 0
                    _progress.value = Progress(status = DownloadStatus.DOWNLOADING, bytesDownloaded = d, totalBytes = t, percent = pct)
                    delay(500)
                }
            }

            try {
                coroutineScope {
                    val jobs = (0 until threads).map { i ->
                        launch(Dispatchers.IO) {
                            val start = i * chunkSize
                            val end = minOf((i + 1) * chunkSize - 1, totalBytes - 1)
                            if (start > end) return@launch
                            try {
                                val conn = (URL(url).openConnection() as HttpURLConnection)
                                conn.requestMethod = "GET"
                                conn.connectTimeout = 15_000
                                conn.readTimeout = 40_000
                                conn.instanceFollowRedirects = true
                                conn.setRequestProperty("Range", "bytes=$start-$end")
                                conn.setRequestProperty("Accept-Encoding", "identity")
                                conn.setUseCaches(false)
                                if (!hfToken.isNullOrBlank()) {
                                    conn.setRequestProperty("Authorization", "Bearer $hfToken")
                                }
                                val code = conn.responseCode
                                if (code != HttpURLConnection.HTTP_PARTIAL) {
                                    throw IllegalStateException("Server did not honor Range (HTTP $code)")
                                }
                                conn.inputStream.use { input ->
                                    RandomAccessFile(parts[i], "rw").use { raf ->
                                        raf.setLength(0)
                                        val buf = ByteArray(256 * 1024)
                                        var n: Int
                                        while (input.read(buf).also { n = it } != -1) {
                                            raf.write(buf, 0, n)
                                            done.addAndGet(n.toLong())
                                        }
                                    }
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                if (!failed.get()) {
                                    failed.set(true)
                                    Log.e(TAG, "Download thread $i failed: ${e.message}", e)
                                }
                            }
                        }
                    }
                    jobs.joinAll()
                }

                if (failed.get()) {
                    throw IllegalStateException("Parallel download failed (a connection dropped).")
                }
                if (done.get() < totalBytes) {
                    throw IllegalStateException("Downloaded ${done.get()} of $totalBytes bytes")
                }

                RandomAccessFile(targetFile, "rw").use { out ->
                    out.setLength(0)
                    parts.forEach { p ->
                        RandomAccessFile(p, "r").use { inp ->
                            val buf = ByteArray(256 * 1024)
                            var n: Int
                            while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                        }
                        p.delete()
                    }
                }
                runCatching { partDir.delete() }

                ticker.cancel()
                handleDownloadCompleted(-1L)
            } catch (e: Exception) {
                ticker.cancel()
                Log.e(TAG, "Parallel download failed, falling back to DownloadManager: ${e.message}", e)
                if (targetFile.exists()) targetFile.delete()
                runCatching { partDir.deleteRecursively() }
                startDownloadManager(url, hfToken)
            }
        }
    }
    /** Reads Content-Length via a HEAD request (falls back to the known model size). */
    private fun probeContentLength(url: String, hfToken: String?): Long {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (!hfToken.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $hfToken")
            val len = conn.contentLengthLong
            conn.disconnect()
            len
        } catch (e: Exception) {
            Log.w(TAG, "HEAD probe failed: ${e.message}")
            -1L
        }
    }

    /** Legacy single-connection system downloader, used only as a fallback. */
    private fun startDownloadManager(url: String, hfToken: String?) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Vanta AI: Gemma 4 E2B")
                .setDescription("Downloading on-device vision+text model (~2.4 GB)...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            val storageLocation = OnDeviceLlmManager.getInstance(context).getModelStorageLocation()
            if (storageLocation == OnDeviceLlmManager.ModelStorageLocation.PUBLIC_DOWNLOADS) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, OnDeviceLlmManager.MODEL_FILENAME)
            } else {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, OnDeviceLlmManager.MODEL_FILENAME)
            }
            if (!hfToken.isNullOrBlank()) {
                request.addRequestHeader("Authorization", "Bearer $hfToken")
            }
            downloadId = downloadManager.enqueue(request)
            startProgressPolling()
        } catch (e: Exception) {
            Log.e(TAG, "DownloadManager fallback failed: ${e.message}", e)
            OnDeviceLlmManager.getInstance(context).setDownloadInProgress(false)
            _progress.value = Progress(status = DownloadStatus.ERROR, errorMessage = e.message)
        }
    }

    private fun startProgressPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _progress.value.status == DownloadStatus.DOWNLOADING) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor? = downloadManager.query(query)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val bytesDownloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val totalBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            .takeIf { it > 0 } ?: OnDeviceLlmManager.ESTIMATED_SIZE_BYTES
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

                        val percent = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                handleDownloadCompleted(downloadId)
                                return@launch
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                _progress.value = Progress(
                                    status = DownloadStatus.ERROR,
                                    errorMessage = "Download failed (code: $reason)"
                                )
                                return@launch
                            }
                            else -> {
                                _progress.value = Progress(
                                    status = DownloadStatus.DOWNLOADING,
                                    bytesDownloaded = bytesDownloaded,
                                    totalBytes = totalBytes,
                                    percent = percent
                                )
                            }
                        }
                    }
                }
                delay(800)
            }
        }
    }

    private fun handleDownloadCompleted(id: Long) {
        pollingJob?.cancel()
        scope.launch {
            _progress.value = Progress(status = DownloadStatus.COMPLETED, percent = 100)
            OnDeviceLlmManager.getInstance(context).setDownloadInProgress(false)
            OnDeviceLlmManager.getInstance(context).checkModelAvailability()
        }
    }

    fun cancelDownload() {
        pollingJob?.cancel()
        parallelJob?.cancel()
        if (downloadId != -1L) {
            downloadManager.remove(downloadId)
            downloadId = -1L
        }
        runCatching { File(OnDeviceLlmManager.getModelFile(context).parentFile, ".vanta-dl").deleteRecursively() }
        OnDeviceLlmManager.getInstance(context).setDownloadInProgress(false)
        val file = OnDeviceLlmManager.getModelFile(context)
        if (file.exists()) file.delete()
        _progress.value = Progress(status = DownloadStatus.CANCELLED)
        OnDeviceLlmManager.getInstance(context).checkModelAvailability()
    }

    fun deleteModel(): Boolean {
        cancelDownload()
        val deleted = OnDeviceLlmManager.getInstance(context).deleteModel()
        _progress.value = Progress(status = DownloadStatus.IDLE)
        return deleted
    }
}
