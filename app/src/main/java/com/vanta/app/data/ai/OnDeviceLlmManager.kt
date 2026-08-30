package com.vanta.app.data.ai

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Manages on-device local inference using the official Google LiteRT-LM runtime (`com.google.ai.edge.litertlm`).
 *
 * Configured specifically for Gemma 4 LiteRT-LM models (`gemma-4-E2B-it.litertlm`):
 * - GPU hardware acceleration with automatic CPU fallback
 * - Lifecycle management with thread-safe singleton initialization
 * - Mutex-protected generation across full stream lifetimes
 * - Safe model deletion without deadlock
 * - Strict minimum size check (500 MB)
 */
class OnDeviceLlmManager private constructor(private val context: Context) {

    enum class ModelState {
        NOT_DOWNLOADED,
        DOWNLOADING,
        DOWNLOADED,
        INITIALIZING,
        READY,
        ERROR
    }

    /**
     * Where the ~1.18 GB on-device model weights live on disk. The user picks this
     * in Settings so a large download never silently bloats private app storage.
     */
    enum class ModelStorageLocation {
        /** App-private external storage — deleted automatically on uninstall. */
        APP_STORAGE,

        /** User-visible Public Downloads folder — survives reinstall, can live on SD. */
        PUBLIC_DOWNLOADS
    }

    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    @Volatile
    private var engine: Engine? = null
    private val inferenceMutex = Mutex()
    private val lifecycleMutex = Mutex()

    var lastError: String? = null
        private set

    companion object {
        private const val TAG = "OnDeviceLlm"
        const val MODEL_FILENAME = "Qwen_Qwen3-VL-2B-Instruct-Q4_K_M.gguf"
        const val MODEL_DISPLAY_NAME = "Qwen3-VL-2B (Q4_K_M)"
        const val ESTIMATED_SIZE_BYTES = 1_107_410_240L // ~1.1 GB
        const val MIN_MODEL_SIZE_BYTES = 500_000_000L   // >= 500 MB required for valid model
        /**
         * Max OUTPUT tokens for on-device generation. Applied as ConversationConfig
         * maxOutputToken so the engine's (possibly small) default cap can never cut
         * an insight or chat reply off mid-sentence. 384 tokens ≈ 285 words keeps
         * chat replies tight and premium (matching the brevity guidance in the
         * coach system prompt) while leaving headroom for insights.
         */
        const val MAX_SEQUENCE_TOKENS = 384

        /** SharedPreferences key for the user's chosen on-device model storage location. */
        const val STORAGE_LOCATION_PREF = "model_storage_location"

        @Volatile
        private var instance: OnDeviceLlmManager? = null

        fun getInstance(context: Context): OnDeviceLlmManager {
            return instance ?: synchronized(this) {
                instance ?: OnDeviceLlmManager(context.applicationContext).also { instance = it }
            }
        }

        fun getModelFile(context: Context): File {
            // Read the pref directly (not via getInstance) so this static helper never
            // re-enters getInstance during OnDeviceLlmManager construction.
            val prefs = context.getSharedPreferences("vanta_ai_settings", Context.MODE_PRIVATE)
            val raw = prefs.getString(STORAGE_LOCATION_PREF, null)
            val location = runCatching { ModelStorageLocation.valueOf(raw ?: "") }
                .getOrDefault(ModelStorageLocation.APP_STORAGE)
            val dir = when (location) {
                ModelStorageLocation.PUBLIC_DOWNLOADS ->
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                ModelStorageLocation.APP_STORAGE ->
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            }
            if (!dir.exists()) dir.mkdirs()
            return File(dir, MODEL_FILENAME)
        }
    }

    init {
        cleanupStaleFiles()
        checkModelAvailability()
    }

    /**
     * Storage safeguard: removes any partial model file (an interrupted DownloadManager
     * pull) and any orphaned parallel-download temp parts (`.vanta-dl`). Without this, a
     * killed 1.1 GB download could leave ~1.2 GB of temp files behind and grow on retries.
     */
    private fun cleanupStaleFiles() {
        runCatching {
            val file = getModelFile(context)
            val dir = file.parentFile ?: return@runCatching
            File(dir, ".vanta-dl").deleteRecursively()
            dir.listFiles()?.forEach { f ->
                if (f.name == MODEL_FILENAME && f.length() > 0L && f.length() < MIN_MODEL_SIZE_BYTES) {
                    f.delete()
                }
            }
        }
    }

    private fun preloadOpenCl() {
        val candidatePaths = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so"
        )
        for (path in candidatePaths) {
            val f = File(path)
            if (f.exists()) {
                try {
                    System.load(f.absolutePath)
                    Log.i(TAG, "Successfully loaded OpenCL library from: $path")
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not load OpenCL from $path: ${t.message}")
                }
            }
        }
    }

    fun checkModelAvailability() {
        val modelFile = getModelFile(context)
        if (modelFile.exists() && modelFile.length() >= MIN_MODEL_SIZE_BYTES && !isDownloadInProgress()) {
            if (_state.value != ModelState.READY) {
                _state.value = ModelState.DOWNLOADED
            }
        } else {
            _state.value = ModelState.NOT_DOWNLOADED
        }
    }

    fun isModelDownloaded(): Boolean {
        // A live/abandoned download must never be treated as a usable model:
        // DownloadManager writes the target file in place, so a partial file can
        // easily pass the size check mid-download or after a process restart.
        if (isDownloadInProgress()) return false
        val modelFile = getModelFile(context)
        return modelFile.exists() && modelFile.length() >= MIN_MODEL_SIZE_BYTES
    }

    /** Persisted flag so a mid-download (or app-restart-during-download) never looks ready. */
    fun isDownloadInProgress(): Boolean {
        val prefs = context.getSharedPreferences("vanta_ai_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("model_download_in_progress", false)
    }

    fun setDownloadInProgress(inProgress: Boolean) {
        val prefs = context.getSharedPreferences("vanta_ai_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("model_download_in_progress", inProgress).apply()
    }

    fun setDownloadingState() {
        _state.value = ModelState.DOWNLOADING
    }

    fun getModelStorageLocation(): ModelStorageLocation {
        val prefs = context.getSharedPreferences("vanta_ai_settings", Context.MODE_PRIVATE)
        val raw = prefs.getString(STORAGE_LOCATION_PREF, null)
        return runCatching { ModelStorageLocation.valueOf(raw ?: "") }
            .getOrDefault(ModelStorageLocation.APP_STORAGE)
    }

    fun setModelStorageLocation(location: ModelStorageLocation) {
        context.getSharedPreferences("vanta_ai_settings", Context.MODE_PRIVATE)
            .edit().putString(STORAGE_LOCATION_PREF, location.name).apply()
        checkModelAvailability()
    }

    /**
     * Checks total device RAM in Gigabytes.
     */
    fun getTotalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 8.0
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * Checks if the device has at least 8 GB RAM (totalMem >= 7.0 GB accounting for OS kernel reservation).
     */
    fun isRamSufficientForOnDevice(context: Context): Boolean {
        return getTotalRamGb(context) >= 7.0
    }

    /**
     * Checks if the host device is powered by a MediaTek SoC with NeuroPilot APU/NPU hardware.
     */
    fun isMediaTekDevice(): Boolean {
        val hardware = android.os.Build.HARDWARE.lowercase()
        val soc = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.os.Build.SOC_MANUFACTURER.lowercase()
            } else ""
        }.getOrDefault("")
        val board = android.os.Build.BOARD.lowercase()
        return hardware.contains("mt") || hardware.contains("dimensity") ||
                soc.contains("mediatek") || board.contains("mt")
    }

    private fun getAvailableMemoryMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 4096
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    /**
     * Initializes the LiteRT-LM runtime on demand with comprehensive crash-proofing,
     * low-memory protection, and automatic GPU/CPU routing.
     */
    private suspend fun ensureEngineLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext true

        val modelFile = getModelFile(context)
        if (!modelFile.exists() || modelFile.length() < MIN_MODEL_SIZE_BYTES) {
            _state.value = ModelState.NOT_DOWNLOADED
            lastError = "Model file not found or incomplete on disk."
            return@withContext false
        }

        lifecycleMutex.withLock {
            if (engine != null) return@withLock true

            // Low-Memory Guard: Protect from Android OS kernel LowMemoryKiller (SIGKILL)
            val freeMemMb = getAvailableMemoryMb()
            if (freeMemMb < 800) {
                runCatching { System.gc() }
                val postGcMb = getAvailableMemoryMb()
                if (postGcMb < 650) {
                    val msg = "Low device memory ($postGcMb MB available). Please close background apps."
                    Log.w(TAG, msg)
                    lastError = msg
                    _state.value = ModelState.ERROR
                    return@withLock false
                }
            }

            _state.value = ModelState.INITIALIZING
            lastError = null
            Log.d(TAG, "Initializing LiteRT-LM Engine on demand (Available RAM: ${getAvailableMemoryMb()} MB)...")

            // Proactively request GC to maximize contiguous memory headroom for native buffers
            runCatching { System.gc() }

            val totalRamGb = getTotalRamGb(context)
            val prefs = context.getSharedPreferences("vanta_ai_settings", Context.MODE_PRIVATE)
            val forceCpu = prefs.getBoolean("force_cpu_backend", false) || totalRamGb < 6.0

            // 1. Try GPU First (Vulkan/OpenCL) if device has >= 6GB RAM and no previous GPU driver failure
            if (!forceCpu) {
                preloadOpenCl()
                var gpuInst: Engine? = null
                try {
                    val gpuConfig = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.GPU()
                    )
                    gpuInst = Engine(gpuConfig)
                    gpuInst.initialize()
                    engine = gpuInst
                    _state.value = ModelState.READY
                    Log.d(TAG, "LiteRT-LM GPU Engine initialized successfully.")
                    return@withLock true
                } catch (gpuEx: Throwable) {
                    Log.w(TAG, "GPU Engine init failed on device GPU driver, persistently falling back to CPU: ${gpuEx.message}")
                    prefs.edit().putBoolean("force_cpu_backend", true).apply()
                    runCatching { gpuInst?.close() }
                    engine = null
                }
            }

            // 2. Fallback to optimized SIMD CPU execution (safe across all Android hardware)
            val cpuThreads = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors()))
            var cpuInst: Engine? = null
            try {
                val cpuConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(numOfThreads = cpuThreads)
                )
                cpuInst = Engine(cpuConfig)
                cpuInst.initialize()
                engine = cpuInst
                _state.value = ModelState.READY
                Log.d(TAG, "LiteRT-LM CPU Engine ($cpuThreads threads) initialized successfully.")
                return@withLock true
            } catch (cpuEx: Throwable) {
                runCatching { cpuInst?.close() }
                val errorMsg = cpuEx.message ?: cpuEx.javaClass.simpleName
                Log.e(TAG, "LiteRT-LM CPU Engine init failed: $errorMsg", cpuEx)
                lastError = errorMsg
                _state.value = ModelState.ERROR
                return@withLock false
            }
        }
    }

    suspend fun initializeDirect(): Boolean = ensureEngineLoaded()

    private fun createVulkanConversationConfig(): com.google.ai.edge.litertlm.ConversationConfig {
        return com.google.ai.edge.litertlm.ConversationConfig(
            samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(
                topK = 40,
                topP = 0.9,
                temperature = 0.6,
                seed = 0
            ),
            // Explicit output budget: without this the LiteRT-LM default cap can
            // truncate on-device replies ("a few words and stop"). 512 tokens lets
            // every insight/chat response run to completion while bounding latency.
            maxOutputToken = MAX_SEQUENCE_TOKENS
        )
    }

    /**
     * Streams tokens as they are generated by LiteRT-LM Vulkan/OpenCL GPU Engine.
     * Protects engine with inferenceMutex across the full streaming session.
     */
    fun generateStreaming(system: String, user: String, imageBase64: String? = null): Flow<String> = callbackFlow<String> {
        val modelFile = getModelFile(context)
        if (!modelFile.exists() || modelFile.length() < MIN_MODEL_SIZE_BYTES) {
            close()
            return@callbackFlow
        }

        val prompt = "$system\n\n$user"

        val ready = try {
            ensureEngineLoaded()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed ensureEngineLoaded during streaming: ${t.message}", t)
            false
        }

        val currentEngine = engine
        if (!ready || currentEngine == null) {
            close()
            return@callbackFlow
        }

        inferenceMutex.lock()
        var lockHeld = true

        val effectivePrompt = if (imageBase64 != null) {
            "[User attached a meal / workout photo. Note: On-device model - give guidance on typical nutrition for this meal, invite the athlete to list any specific ingredients for exact macros]\n$prompt"
        } else {
            prompt
        }

        try {
            val conversation = currentEngine.createConversation(createVulkanConversationConfig())
            conversation.sendMessageAsync(effectivePrompt, object : com.google.ai.edge.litertlm.MessageCallback {
                override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                    val textChunks = message.contents.contents.filterIsInstance<Content.Text>().map { it.text }
                    val chunk = textChunks.joinToString("")
                    if (chunk.isNotEmpty()) {
                        trySend(chunk)
                    }
                }

                override fun onDone() {
                    if (lockHeld) {
                        inferenceMutex.unlock()
                        lockHeld = false
                    }
                    close()
                }

                override fun onError(throwable: Throwable) {
                    Log.e(TAG, "LiteRT-LM streaming error: ${throwable.message}", throwable)
                    if (lockHeld) {
                        inferenceMutex.unlock()
                        lockHeld = false
                    }
                    close()
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "LiteRT-LM start streaming error: ${e.message}", e)
            if (lockHeld) {
                inferenceMutex.unlock()
                lockHeld = false
            }
            close()
        }

        awaitClose {
            if (lockHeld) {
                inferenceMutex.unlock()
                lockHeld = false
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Suspendingly generates a complete response on GPU with automatic CPU fallback.
     */
    suspend fun generate(system: String, user: String, imageBase64: String? = null): String? = withContext(Dispatchers.IO) {
        if (!isModelDownloaded()) return@withContext null
        val prompt = "$system\n\n$user"
        val ready = ensureEngineLoaded()
        var currentEngine = engine
        if (!ready || currentEngine == null) return@withContext null

        val effectivePrompt = if (imageBase64 != null) {
            "[Multimodal Context: User attached an image for nutritional analysis / exercise breakdown]\n$prompt"
        } else {
            prompt
        }

        val result = executeInference(currentEngine, prompt, imageBase64)
        if (result != null) return@withContext result

        // If GPU execution failed or returned null, retry with multi-thread CPU engine
        Log.w(TAG, "GPU inference returned null/failed, attempting CPU fallback...")
        try {
            currentEngine.close()
        } catch (e: Throwable) {
            // Ignore
        }
        engine = null

        val modelFile = getModelFile(context)
        return@withContext try {
            val cpuConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(numOfThreads = 4)
            )
            val cpuInst = Engine(cpuConfig)
            cpuInst.initialize()
            engine = cpuInst
            _state.value = ModelState.READY
            executeInference(cpuInst, prompt, imageBase64)
        } catch (t: Throwable) {
            Log.e(TAG, "CPU fallback also failed: ${t.message}", t)
            null
        }
    }

    private suspend fun executeInference(inst: Engine, prompt: String, imageBase64: String? = null): String? = inferenceMutex.withLock {
        kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
            try {
                val conversation = inst.createConversation(createVulkanConversationConfig())
                val responseBuilder = StringBuilder()

                val effectivePrompt = if (imageBase64 != null) {
                    "[User attached a meal / workout photo. Note: On-device model - give guidance on typical nutrition for this meal, invite the athlete to list any specific ingredients for exact macros]\n$prompt"
                } else {
                    prompt
                }

                conversation.sendMessageAsync(effectivePrompt, object : com.google.ai.edge.litertlm.MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        val textChunks = message.contents.contents.filterIsInstance<Content.Text>().map { it.text }
                        responseBuilder.append(textChunks.joinToString(""))
                    }

                    override fun onDone() {
                        if (cont.isActive) {
                            val text = responseBuilder.toString().trim()
                            cont.resume(text.ifEmpty { null })
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "LiteRT-LM generation error: ${throwable.message}", throwable)
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }
                })
            } catch (e: Throwable) {
                Log.e(TAG, "LiteRT-LM execution failure: ${e.message}", e)
                if (cont.isActive) {
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * Keeps the loaded engine resident for fast follow-up GPU inference, releasing
     * it only when the device is under memory pressure. Avoids paying the expensive
     * model-load / shader-compile cost on every insight tap.
     */
    fun maybeReleaseUnderMemoryPressure() {
        val freeMb = getAvailableMemoryMb()
        if (freeMb < 700) {
            Log.w(TAG, "Low memory ($freeMb MB available) — releasing on-device engine.")
            unloadEngine()
        }
    }

    /**
     * Unloads the engine and releases all GPU/CPU memory and JNI native allocations.
     * Called immediately when the user leaves the AI Chat overlay.
     */
    fun unloadEngine() {
        try {
            engine?.close()
            engine = null
            if (isModelDownloaded()) {
                _state.value = ModelState.DOWNLOADED
            }
            Log.d(TAG, "LiteRT-LM Engine discarded from memory.")
        } catch (e: Throwable) {
            Log.w(TAG, "Error discarding engine: ${e.message}")
        }
    }

    /**
     * Triggers asynchronous background load of the model if downloaded.
     */
    fun preloadModelAsync() {
        if (!isModelDownloaded()) return
        if (_state.value == ModelState.READY || _state.value == ModelState.INITIALIZING) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {}
            ensureEngineLoaded()
        }
    }

    /**
     * Safely deletes the model file and releases engine resources.
     */
    fun deleteModel(): Boolean {
        if (!inferenceMutex.tryLock()) {
            Log.w(TAG, "Cannot delete model while inference is actively running.")
            return false
        }
        return try {
            try {
                engine?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Error closing engine: ${e.message}")
            }
            engine = null
            // Delete the model wherever it may reside — the storage location may have
            // changed since it was downloaded.
            val candidates = listOf(
                getModelFile(context),
                File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, MODEL_FILENAME),
                File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), MODEL_FILENAME)
            )
            var deleted = true
            for (f in candidates.distinctBy { it.absolutePath }) {
                if (f.exists() && !f.delete()) deleted = false
            }
            _state.value = ModelState.NOT_DOWNLOADED
            deleted
        } finally {
            inferenceMutex.unlock()
        }
    }
}
