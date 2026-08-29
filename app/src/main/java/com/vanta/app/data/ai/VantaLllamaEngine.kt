package com.vanta.app.data.ai

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Chat-only llama.cpp engine (Vulkan-first, ARM64 NEON) — the fast on-device chat
 * path. Analysis and notifications keep using the existing MediaPipe / LiteRT-LM
 * stack, so this library only ever drives the chat, keeping storage and memory
 * focused.
 *
 * Native lib (`libvanta_llama.so`) is built only with `-Pllama=true`. If the native
 * lib or the GGUF model is missing, [isAvailable] is false and chat falls back to
 * the existing engine — the app never crashes.
 */
class VantaLllamaEngine(private val context: Context) {

    companion object {
        private const val TAG = "VantaLllama"

        /** Single GGUF file the user downloads (the whole app's on-device model). */
        const val MODEL_FILENAME = "Qwen_Qwen3-VL-2B-Instruct-Q4_K_M.gguf"

        /** Download source for the chosen model. */
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/bartowski/Qwen_Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen_Qwen3-VL-2B-Instruct-Q4_K_M.gguf"

        /** ~1.1 GB (Q4_K_M). */
        const val ESTIMATED_SIZE_BYTES = 1_107_410_240L

        /** Download suggestions (Qwen3-VL-2B is strong at instruction/JSON). */
        val PREFERRED_MODELS = listOf(MODEL_FILENAME)

        @Volatile
        private var nativeLoaded = false

        @Synchronized
        private fun ensureNative() {
            if (!nativeLoaded) {
                System.loadLibrary("vanta_llama")
                nativeLoaded = true
            }
        }
    }

    /** Where the GGUF lives (app-specific external storage, auto-cleared on uninstall). */
    fun modelFile(): File {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILENAME)
    }

    fun isModelDownloaded(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() > 50_000_000L // >= ~50 MB so a partial download never counts
    }

    fun isNativeAvailable(): Boolean = try {
        ensureNative()
        true
    } catch (t: Throwable) {
        Log.w(TAG, "native lib unavailable — llama chat disabled", t)
        false
    }

    /** Chat is usable only when BOTH the native lib and the model file are present. */
    fun isAvailable(): Boolean = isNativeAvailable() && isModelDownloaded()

    /** max(1, physical_cores - 2) with ARM NEON. */
    fun recommendedThreads(): Int =
        maxOf(1, Runtime.getRuntime().availableProcessors() - 2)

    /** Generation threads: fewer is better on a small device (decode is memory-bound). */
    fun genThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    /** Prompt-eval threads: more parallel cores = faster time-to-first-token. */
    fun batchThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Warm up the model/context (2K context, F16 KV cache, auto attention). */
    fun init(): Boolean {
        if (!isAvailable()) return false
        return try {
            ensureNative()
            nativeInit(modelFile().absolutePath, genThreads(), batchThreads(), 2048, 1024, true) != 0L
        } catch (t: Throwable) {
            Log.e(TAG, "llama init failed", t)
            false
        }
    }

    /** Generate a full chat reply (called on a background thread). */
    fun generate(system: String, user: String, maxTokens: Int = 180): String = try {
        ensureNative()
        val seed = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        nativeGenerate(system, user, maxTokens, 0.6f, 0.95f, 40, seed)
    } catch (t: Throwable) {
        Log.e(TAG, "llama generate failed", t)
        ""
    }

    /**
     * True token-by-token streaming from the GPU. Each emission is one decoded token,
     * so the first token arrives as soon as the prompt finishes decoding (real TTFT).
     * Callers must run [init] before, and [release] after, this flow completes.
     */
    fun generateStreaming(system: String, user: String, maxTokens: Int = 180): Flow<String> = flow {
        if (!isAvailable()) {
            emit("")
            return@flow
        }
        ensureNative()
        val seed = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val ok = try {
            nativeStreamInit(system, user, maxTokens, 0.6f, 0.95f, 40, seed)
        } catch (t: Throwable) {
            Log.e(TAG, "llama stream init failed", t)
            false
        }
        if (!ok) {
            emit("")
            return@flow
        }
        try {
            var guard = 0
            while (guard++ < maxTokens + 8) {
                val piece = nativeStreamNext()
                if (piece.isEmpty()) break
                emit(piece)
            }
        } finally {
            nativeStreamRelease()
        }
    }.flowOn(Dispatchers.IO)

    fun release() {
        try {
            ensureNative()
            nativeRelease()
        } catch (_: Throwable) {}
    }

    /**
     * Fully frees the model weights + GPU memory (not just the per-turn context).
     * Call when backgrounding or on teardown so we never pin ~1.5 GB while idle.
     */
    fun unload() {
        try {
            ensureNative()
            nativeUnload()
        } catch (_: Throwable) {}
    }

    // ── Native ────────────────────────────────────────────────────────────────
    private external fun nativeInit(modelPath: String, nThreads: Int, nThreadsBatch: Int, nCtx: Int, nBatch: Int, flashAttn: Boolean): Long
    private external fun nativeGenerate(system: String, user: String, maxTokens: Int, temperature: Float, topP: Float, topK: Int, seed: Int): String
    private external fun nativeStreamInit(system: String, user: String, maxTokens: Int, temperature: Float, topP: Float, topK: Int, seed: Int): Boolean
    private external fun nativeStreamNext(): String
    private external fun nativeStreamRelease()
    private external fun nativeRelease()
    private external fun nativeUnload()
}
