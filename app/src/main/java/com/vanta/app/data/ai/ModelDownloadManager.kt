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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "ModelDownload"
        // Verified direct LiteRT-LM Gemma 4 E2B model weights URL
        const val DEFAULT_MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

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
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Vanta AI: Gemma 4 E2B")
                .setDescription("Downloading on-device intelligence model (~1.18 GB)...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, OnDeviceLlmManager.MODEL_FILENAME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            if (!hfToken.isNullOrBlank()) {
                request.addRequestHeader("Authorization", "Bearer $hfToken")
            }

            downloadId = downloadManager.enqueue(request)
            OnDeviceLlmManager.getInstance(context).setDownloadingState()
            OnDeviceLlmManager.getInstance(context).setDownloadInProgress(true)

            _progress.value = Progress(status = DownloadStatus.DOWNLOADING, percent = 0)
            startProgressPolling()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download: ${e.message}", e)
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
        if (downloadId != -1L) {
            downloadManager.remove(downloadId)
            downloadId = -1L
        }
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
