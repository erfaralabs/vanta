package com.vanta.app.data

import android.content.Context
import com.vanta.app.data.ai.CoachPromptSystem
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.data.db.DailyMetricRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt

data class GemmaCallout(
    val text: String,
    val colorHex: String
)

data class GemmaAiAnalysis(
    val strain: Double,                      // WHOOP-style 0.0–21.0 scale
    val recovery: Int,
    val recoveryCategoryLabel: String,
    val energy: Int,
    val overview: String,                    // 2-4 sentence WHOOP/Oura style physiological explanation
    val callouts: List<GemmaCallout>,        // 2-4 rule-based key insights
    val recommendation: String,
    val isLearningPhase: Boolean = true,
    val savedDaysCount: Int = 0,
    val baselineMessage: String = "Building personalized baseline",
    val isModelLoaded: Boolean = true
)

data class HealthConnectTelemetry(
    val steps: Long = 0L,
    val calories: Long = 0L,
    val distanceKm: Double = 0.0,
    val currentBpm: Int = 0,
    val avgBpm: Int = 0,
    val peakBpm: Int = 0,
    val restingBpm: Int = 0,
    val spo2Percent: Double = 98.5,
    val bodyTempCelsius: Double = 36.6,
    val exerciseMinutes: Int = 0,
    val sleepMinutes: Int = 0,               // minutes asleep last night; 0 = sleep NOT tracked
    val hoursSinceLastWorkout: Double? = null // null = no workout session found in the window
) {
    companion object {
        val Default = HealthConnectTelemetry()
    }
}

/**
 * High-Precision Physiological Analytics Engine (WHOOP / Oura Style).
 * Generates calm, objective, evidence-based, clinical physiological analysis.
 */

/** AI providers supported by the coach (Cloud APIs or On-Device LiteRT-LM). */
enum class AiProvider(val label: String) {
    GEMINI("Gemini Free"),
    DEEPSEEK("DeepSeek"),
    MISTRAL("Mistral"),
    OPENROUTER("OpenRouter"),
    ON_DEVICE_LITERT("On-Device (Gemma 4 E2B)")
}

class VantaGemmaEngine(private val context: Context) {

    private val deterministicEngine = VantaDeterministicPhysiologyEngine(context)
    private val aiCache = AiOverviewCache(context)
    private val aiDetailCache = com.vanta.app.data.ai.AiDetailInsightCache(context)
    private val prefs = context.getSharedPreferences("vanta_ai_cache", Context.MODE_PRIVATE)
    private val dao = com.vanta.app.data.db.VantaDatabase.getInstance(context).dailyMetricsDao()

    companion object {
        const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        /** OpenRouter model for short coaching lines. The historical `:free` slug for
         * this exact model was retired, so this is the paid 7B instruct model — fast,
         * cheap, and verified to work with the bundled credit-backed key. */
        const val OPENROUTER_MODEL = "qwen/qwen-2.5-7b-instruct"
        /**
         * Fallback tried automatically when the primary OpenRouter model fails.
         * `openrouter/free` is OpenRouter's aggregate free tier — it routes to whatever
         * free model is currently available, so we never hardcode a `:free` slug that
         * might be retired. Costs nothing and degrades gracefully to the deterministic
         * coach if it also fails.
         */
        val OPENROUTER_FALLBACK_MODELS = listOf("openrouter/free")
        /** Mistral's OpenAI-compatible chat completions endpoint. */
        const val MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions"
        const val MISTRAL_MODEL = "mistral-medium-latest"
        /** Gemini via Google's OpenAI-compatible endpoint. Uses standard stable Flash model ID. */
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        const val GEMINI_MODEL = "gemini-3.5-flash-lite"
        val GEMINI_FALLBACK_MODELS = listOf("gemini-3.5-flash-lite", "gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-flash", "gemini-flash-latest")

        /** Only palette colors are accepted from the model so the UI can never look off-brand. */
        internal val allowedCalloutColors = setOf(
            "#39FF80", "#FFB000", "#FF5252", "#00F2FE", "#FFAA00", "#00E5FF", "#BF5AF2", "#00F5FF"
        )

        /**
         * Parses the model-written callout chips with strict validation. A chip that is
         * blank, too long, off-palette, or duplicated is dropped; if the model returns
         * fewer than 3 usable chips, the deterministic metric callouts pad the section
         * so the card can never show junk or invent data.
         */
        internal fun parseModelCallouts(
            obj: JSONObject,
            fallback: List<GemmaCallout>
        ): List<GemmaCallout> {
            val parsed = runCatching {
                val arr = obj.optJSONArray("callouts")
                val out = mutableListOf<GemmaCallout>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val text = item.optString("text").trim().replace(Regex("\\s+"), " ")
                        val color = item.optString("colorHex").uppercase()
                        if (text.length in 8..110) {
                            out.add(GemmaCallout(text, if (color in allowedCalloutColors) color else "#00F2FE"))
                        }
                    }
                }
                out.distinctBy { it.text.lowercase() }.take(5)
            }.getOrDefault(emptyList())

            val result = parsed.toMutableList()
            for (fb in fallback) {
                if (result.size >= 3) break
                if (result.none { it.text == fb.text }) result.add(fb)
            }
            return result
        }

        /**
         * True when AI prose looks like a FINISHED response (not a truncated
         * mid-sentence fragment). Shared by the home overview and the detail
         * insights so every provider surfaces uniform, complete prose.
         */
        internal fun isCompleteProse(text: String): Boolean {
            val t = text.trim()
            return t.isNotBlank() &&
                t.length >= 15 &&
                (t.endsWith('.') || t.endsWith('!') || t.endsWith('?')) &&
                !t.contains("{") &&
                !t.startsWith("json", ignoreCase = true)
        }
    }

    /** Result of an API-key verification call with rich diagnostics. */
    sealed class ApiKeyCheckResult {
        object Valid : ApiKeyCheckResult()
        data class QuotaExceeded(val details: String) : ApiKeyCheckResult()
        data class InvalidKey(val details: String) : ApiKeyCheckResult()
        data class NetworkError(val details: String) : ApiKeyCheckResult()
    }

    private data class ChatResult(val statusCode: Int, val body: String?)

    private fun providerEndpoint(provider: AiProvider): Pair<String, String> = when (provider) {
        AiProvider.DEEPSEEK -> DEEPSEEK_URL to DEEPSEEK_MODEL
        AiProvider.MISTRAL -> MISTRAL_URL to MISTRAL_MODEL
        AiProvider.OPENROUTER -> OPENROUTER_URL to OPENROUTER_MODEL
        AiProvider.GEMINI, AiProvider.ON_DEVICE_LITERT -> GEMINI_URL to GEMINI_MODEL
    }

    /**
     * Free-tier cloud providers (Gemini Flash) cap usage at ~20 requests/min — and
     * share that quota across every feature (AI coach, notification engine, key
     * check) and every app open. This gate keeps Vanta well under the limit:
     *   • at least 1s between any two cloud calls
     *   • never more than 20 calls in any rolling 60s window
     * so a burst of features can never exhaust the quota on its own.
     */
    private object CloudGate {
        private const val WINDOW_MS = 60_000L
        private const val MAX_PER_WINDOW = 20
        private const val MIN_GAP_MS = 2_000L
        private val lock = Any()
        private val timestamps = ArrayDeque<Long>()

        suspend fun acquire() {
            while (true) {
                val waitMs = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    while (timestamps.isNotEmpty() && now - timestamps.first() >= WINDOW_MS) {
                        timestamps.removeFirst()
                    }
                    val gapWait = timestamps.lastOrNull()?.let { MIN_GAP_MS - (now - it) } ?: 0L
                    val windowWait = if (timestamps.size >= MAX_PER_WINDOW) {
                        WINDOW_MS - (now - timestamps.first())
                    } else {
                        0L
                    }
                    val wait = maxOf(gapWait, windowWait)
                    if (wait <= 0L) {
                        timestamps.addLast(now)
                        0L
                    } else {
                        wait
                    }
                }
                if (waitMs <= 0L) return
                delay(waitMs)
            }
        }
    }

    /** Parses Google's "retry in Xs" free-tier guidance from a 429 error body. */
    private fun parseRetryAfterMs(body: String?): Long? {
        body ?: return null
        val m = Regex("retry in ([0-9.]+)\\s*s", RegexOption.IGNORE_CASE).find(body) ?: return null
        val secs = m.groupValues[1].toDoubleOrNull() ?: return null
        return (secs * 1000).toLong()
    }

    /** Low-level OpenAI-compatible chat completion POST. Returns HTTP status + raw body. */
    private suspend fun rawCompletion(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        provider: AiProvider,
        maxTokens: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): ChatResult = withContext(Dispatchers.IO) {
        CloudGate.acquire()
        val result = postOnce(systemPrompt, userPrompt, apiKey, provider, maxTokens, connectTimeoutMs, readTimeoutMs, imageBase64, imageMimeType)

        // Free-tier quota (HTTP 429): honor the provider's suggested backoff and
        // retry ONCE before giving up to the deterministic coach.
        if (result.statusCode == 429) {
            val backoffMs = parseRetryAfterMs(result.body)?.coerceIn(2_000L, 10_000L) ?: 3_000L
            android.util.Log.w("VantaAI", "Cloud AI rate-limited (429), retrying in ${backoffMs}ms")
            delay(backoffMs)
            val retry = postOnce(systemPrompt, userPrompt, apiKey, provider, maxTokens, connectTimeoutMs, readTimeoutMs, imageBase64, imageMimeType)
            if (retry.statusCode in 200..299) return@withContext retry
        }
        result
    }

    private suspend fun postOnce(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        provider: AiProvider,
        maxTokens: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): ChatResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return ChatResult(401, "API key is blank")

        try {
            val (url, primaryModel) = providerEndpoint(provider)
            val modelsToTry = when (provider) {
                AiProvider.GEMINI -> (listOf(primaryModel) + GEMINI_FALLBACK_MODELS).distinct()
                AiProvider.OPENROUTER -> (listOf(primaryModel) + OPENROUTER_FALLBACK_MODELS).distinct()
                else -> listOf(primaryModel)
            }

            val userContent = if (!imageBase64.isNullOrBlank()) {
                val parts = org.json.JSONArray()
                val textPart = JSONObject()
                    .put("type", "text")
                    .put("text", userPrompt.ifBlank { "Analyze this image for food, nutrients, and fitness guidance." })
                parts.put(textPart)

                val imagePart = JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:${imageMimeType ?: "image/jpeg"};base64,$imageBase64")
                    )
                parts.put(imagePart)
                parts
            } else {
                userPrompt
            }

            val messagesArray = org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userContent))

            var lastCode = -1
            var lastText: String? = null

            for (model in modelsToTry) {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messagesArray)
                    .put("max_tokens", maxTokens)
                    .put("temperature", 0.6)
                    .toString()

                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.setRequestProperty("Authorization", "Bearer $cleanKey")
                    if (provider == AiProvider.OPENROUTER) {
                        conn.setRequestProperty("HTTP-Referer", "https://vanta.app")
                        conn.setRequestProperty("X-Title", "Vanta")
                    }
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

                    lastCode = code
                    lastText = text

                    if (code in 200..299) {
                        return ChatResult(code, text)
                    } else {
                        android.util.Log.e("VantaAI", "OpenAI model $model failed (HTTP $code): $text")
                    }
                    if (code == 401 || code == 403) {
                        // If unauthorized or invalid API key, stop trying other models
                        break
                    }
                } finally {
                    conn.disconnect()
                }
            }

            // If OpenAI compatibility endpoint fails for Gemini, try direct Google Generative AI REST API as secondary fallback
            if (provider == AiProvider.GEMINI && lastCode !in 200..299) {
                android.util.Log.w("VantaAI", "Falling back to Gemini Native REST API...")
                val nativeRes = postGeminiNativeRest(systemPrompt, userPrompt, cleanKey, maxTokens, connectTimeoutMs, readTimeoutMs, imageBase64, imageMimeType)
                if (nativeRes.statusCode in 200..299) {
                    return nativeRes
                }
            }

            return ChatResult(lastCode, lastText)
        } catch (e: Exception) {
            e.printStackTrace()
            return ChatResult(-1, null)
        }
    }

    /** Secondary fallback using Google's native Generative Language REST API */
    private suspend fun postGeminiNativeRest(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        maxTokens: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        imageBase64: String?,
        imageMimeType: String?
    ): ChatResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val models = listOf("gemini-3.5-flash-lite", "gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-flash", "gemini-flash-latest")
        for (model in models) {
            try {
                val restUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val contentsArray = org.json.JSONArray()

                // System & User combined prompt
                val fullText = if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$userPrompt" else userPrompt
                val partsArray = org.json.JSONArray()
                partsArray.put(JSONObject().put("text", fullText))

                if (!imageBase64.isNullOrBlank()) {
                    partsArray.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", imageMimeType ?: "image/jpeg")
                                .put("data", imageBase64)
                        )
                    )
                }

                contentsArray.put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", partsArray)
                )

                val body = JSONObject()
                    .put("contents", contentsArray)
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put("maxOutputTokens", maxTokens)
                            .put("temperature", 0.6)
                    )
                    .toString()

                val conn = URL(restUrl).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.setRequestProperty("x-goog-api-key", cleanKey)
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

                    android.util.Log.e("VantaAI", "Native Gemini model $model -> HTTP $code: $text")

                    if (code in 200..299) {
                        // Translate native Gemini response format to OpenAI-compatible format for consumer parser
                        val nativeJson = JSONObject(text)
                        val textContent = nativeJson.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "") ?: ""

                        val translated = JSONObject()
                            .put(
                                "choices",
                                org.json.JSONArray().put(
                                    JSONObject()
                                        .put("message", JSONObject().put("role", "assistant").put("content", textContent))
                                        .put("finish_reason", "stop")
                                )
                            )
                            .toString()

                        return@withContext ChatResult(code, translated)
                    }

                    if (code == 401 || code == 403) {
                        break
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        ChatResult(-1, null)
    }

    /**
     * Generates model text via the selected provider using the shared VANTA system
     * prompt + the feature's user prompt. Returns the raw model text (expected to be
     * JSON), or null on failure/timeout/missing key.
     */
    internal suspend fun generateWithProvider(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        provider: AiProvider,
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 60_000,
        maxTokens: Int = 800,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): String? {
        if (provider == AiProvider.ON_DEVICE_LITERT) {
            val onDevice = com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context)
            if (onDevice.isModelDownloaded()) {
                val result = onDevice.generate(systemPrompt, userPrompt)
                // Safe + guarded release point: OnDeviceLlmManager keeps the model resident
                // for at least RESIDENT_MIN_MS after load and only evicts when free memory
                // is under RELEASE_THRESHOLD_MB, so this never reloads the ~2.4GB model on
                // consecutive Home coach refreshes (the original load/evict thrash).
                onDevice.maybeReleaseUnderMemoryPressure()
                return result
            } else {
                android.util.Log.w("VantaAI", "On-device model not downloaded")
                return null
            }
        }
        val res = rawCompletion(
            systemPrompt, userPrompt, apiKey, provider,
            maxTokens = maxTokens,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )
        if (res.statusCode !in 200..299) {
            android.util.Log.w("VantaAI", "Cloud AI unavailable (HTTP ${res.statusCode}) — falling back to deterministic coach")
            return null
        }
        val body = res.body ?: return null
        return try {
            val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            val content = choice.getJSONObject("message").optString("content", "").trim()
            if (content.isNotBlank()) content else null
        } catch (e: Exception) {
            null
        }
    }

    /** Backward-compatible single-prompt call — runs under the shared VANTA system prompt. */
    @Deprecated(
        "Use the system+user overload — every call now runs under CoachPromptSystem.SYSTEM_PROMPT."
    )
    internal suspend fun generateWithProvider(
        prompt: String,
        apiKey: String,
        provider: AiProvider,
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 60_000
    ): String? = generateWithProvider(
        CoachPromptSystem.SYSTEM_PROMPT, prompt, apiKey, provider, connectTimeoutMs, readTimeoutMs
    )

    /**
     * Verifies a user-provided API key with a minimal request (tiny max_tokens,
     * short timeouts) against the selected provider with diagnostic error reporting.
     */
    suspend fun checkApiKey(apiKey: String, provider: AiProvider): ApiKeyCheckResult {
        if (provider == AiProvider.ON_DEVICE_LITERT) {
            val onDevice = com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context)
            return if (onDevice.isModelDownloaded()) ApiKeyCheckResult.Valid
            else ApiKeyCheckResult.NetworkError("On-device model is not downloaded.")
        }
        if (apiKey.isBlank()) {
            return ApiKeyCheckResult.InvalidKey("API key cannot be empty.")
        }
        val res = rawCompletion(
            CoachPromptSystem.SYSTEM_PROMPT, "Reply with exactly: OK",
            apiKey, provider, maxTokens = 20, connectTimeoutMs = 12_000, readTimeoutMs = 15_000
        )
        return when {
            res.statusCode in 200..299 -> ApiKeyCheckResult.Valid
            res.statusCode == 429 -> {
                val rawMsg = runCatching {
                    val json = JSONObject(res.body ?: "")
                    val err = json.optJSONObject("error")
                    err?.optString("message") ?: ""
                }.getOrDefault("")
                val msg = if (rawMsg.isNotBlank()) rawMsg else "Rate limit or daily quota reached (HTTP 429)."
                ApiKeyCheckResult.QuotaExceeded(msg)
            }
            res.statusCode in 401..403 -> {
                val rawMsg = runCatching {
                    val json = JSONObject(res.body ?: "")
                    val err = json.optJSONObject("error")
                    err?.optString("message") ?: ""
                }.getOrDefault("")
                val msg = if (rawMsg.isNotBlank()) rawMsg else "Unauthorized / Invalid API Key (HTTP ${res.statusCode})."
                ApiKeyCheckResult.InvalidKey(msg)
            }
            else -> {
                val rawMsg = runCatching {
                    val json = JSONObject(res.body ?: "")
                    val err = json.optJSONObject("error")
                    err?.optString("message") ?: ""
                }.getOrDefault("")
                val msg = if (rawMsg.isNotBlank()) rawMsg else "Could not reach ${provider.label} servers (HTTP ${res.statusCode})."
                ApiKeyCheckResult.NetworkError(msg)
            }
        }
    }

    /**
     * Uses the selected cloud provider when a key is configured; otherwise falls
     * back to the deterministic template coach. With a key, the cloud model writes
     * both the overview prose AND the 3 callout chips (validated against the real
     * metrics and palette). Any failure (no internet, timeout, malformed JSON) falls
     * back to the metric-grounded templates — the Strain/Recovery/Energy numbers are
     * always the deterministic engine's real calculations.
     */
    suspend fun analyzeHealthTelemetry(
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline = UserBaseline.Default,
        apiKey: String? = null,
        provider: AiProvider = AiProvider.DEEPSEEK
    ): GemmaAiAnalysis = withContext(Dispatchers.Default) {
        val detResult = deterministicEngine.calculatePhysiology(telemetry, baseline)
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()

        // Reuse the cached AI overview unless something meaningful changed:
        // recovery changed, strain moved by >= 0.5, a new workout was logged, or time slot shifted.
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val currentSlot = when (currentHour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
        val cachedSlot = prefs.getString("ai_cache_time_slot", "")
        val cachedProvider = prefs.getString("vanta_ai_cache_provider", "")

        val cached = aiCache.load()
        val cacheValid = cached != null &&
            cached.date == today &&
            cachedSlot == currentSlot &&
            cachedProvider == provider.name &&
            cached.generatedWithModel &&
            abs(cached.recovery - detResult.recovery) <= 3 &&
            abs(cached.energy - detResult.energy) <= 3 &&
            abs(detResult.strain - cached.strain) < 0.5 &&
            telemetry.exerciseMinutes <= cached.workoutMinutes
        if (cacheValid) {
            return@withContext buildFromCache(telemetry, detResult, baseline, cached)
        }

        // Something changed → regenerate (cloud AI when a key is configured OR on-device model is downloaded).
        val isProviderReady = if (provider == AiProvider.ON_DEVICE_LITERT) {
            com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context).isModelDownloaded()
        } else {
            !apiKey.isNullOrBlank()
        }

        if (isProviderReady) {
            try {
                // Personalization: the model sees the user's actual history (last 7
                // archived days + trend deltas) + their fixed onboarding profile so
                // it coaches THIS athlete's patterns against stable calibration data.
                val history = runCatching { dao.getAllRecords() }.getOrDefault(emptyList())
                val profile = runCatching {
                    com.vanta.app.data.db.VantaDatabase.getInstance(context).userProfileDao().getUserProfile()
                }.getOrNull()
                val prompt = CoachPromptSystem.coachPrompt(
                    telemetry, detResult, baseline,
                    dataLimited = isDataLimited(telemetry),
                    history = history,
                    profile = profile,
                    provider = provider
                )
                val rawOutput = generateWithProvider(
                    systemPrompt = prompt.system,
                    userPrompt = prompt.user,
                    apiKey = apiKey ?: "",
                    provider = provider,
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 25_000,
                    maxTokens = 600
                )
                if (rawOutput == null) {
                    android.util.Log.w("VantaAI", "AI coach returned nothing — using deterministic coach")
                }
                val json = rawOutput?.let { parseStructuredJson(it, detResult, baseline, telemetry) }
                if (json != null) {
                    android.util.Log.d("VantaAI", "AI coach OK — saving AI overview to cache ($currentSlot, ${provider.name})")
                    prefs.edit().putString("ai_cache_time_slot", currentSlot).putString("vanta_ai_cache_provider", provider.name).apply()
                    aiCache.save(
                        AiOverviewCache.Entry(
                            date = today,
                            recovery = detResult.recovery,
                            energy = detResult.energy,
                            strain = detResult.strain,
                            workoutMinutes = telemetry.exerciseMinutes,
                            generatedWithModel = true,
                            overview = json.overview,
                            recommendation = json.recommendation,
                            callouts = json.callouts
                        )
                    )

                    // Pre-generate buffered AI notification drafts for background worker delivery without loading LLM
                    val buffer = com.vanta.app.data.notification.AiNotificationBuffer(context)
                    val drafts = mutableListOf<com.vanta.app.data.notification.AiNotificationBuffer.BufferedMessage>()
                    val callout1 = json.callouts.firstOrNull()?.text.orEmpty()
                    val callout2 = json.callouts.getOrNull(1)?.text.orEmpty()

                    drafts.add(
                        com.vanta.app.data.notification.AiNotificationBuffer.BufferedMessage(
                            reason = "recovery",
                            title = "Morning Recovery",
                            message = if (callout1.isNotBlank()) "Recovery at ${detResult.recovery}%. $callout1" else "Recovery is at ${detResult.recovery}%. ${json.overview.take(100)}",
                            priority = "normal",
                            date = today
                        )
                    )
                    drafts.add(
                        com.vanta.app.data.notification.AiNotificationBuffer.BufferedMessage(
                            reason = "strain",
                            title = "Strain Spike",
                            message = if (callout2.isNotBlank()) "Daily strain reached ${"%.1f".format(detResult.strain)}. $callout2" else "Daily strain accumulated to ${"%.1f".format(detResult.strain)}/21.",
                            priority = "high",
                            date = today
                        )
                    )
                    drafts.add(
                        com.vanta.app.data.notification.AiNotificationBuffer.BufferedMessage(
                            reason = "workout",
                            title = "Workout Logged",
                            message = "Session complete (${telemetry.exerciseMinutes} min). Daily strain is now ${"%.1f".format(detResult.strain)}/21 — recover well.",
                            priority = "high",
                            date = today
                        )
                    )
                    buffer.saveDrafts(today, drafts)

                    return@withContext json
                }
                if (rawOutput != null) {
                    android.util.Log.w("VantaAI", "AI coach output failed to parse — using deterministic coach")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.w("VantaAI", "AI coach threw — using deterministic coach", e)
            }
        }

        android.util.Log.d("VantaAI", "Using deterministic coach (no API key configured)")
        generateDeterministicInsights(telemetry, detResult, baseline)
    }

    /**
     * Generates an inline detail page insight (Recovery, Strain, or Energy) with:
     * - Data-based persistent caching (keyed to recovery, strain, energy, hour, date)
     * - Instant return (<5ms) if metrics haven't drifted beyond thresholds (recovery/energy <= 3%, strain <= 0.5)
     * - Verdict-first, coach-to-athlete tone, direct, no hedging, ends with concrete action
     * - Safety net: ~220 char length cap, markdown stripping, & disallowed medical claim check
     */
    suspend fun generateDetailInsight(
        targetMetric: com.vanta.app.ui.screens.PhysiologyMetric,
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline,
        apiKey: String?,
        provider: AiProvider,
        context: Context,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList()
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val det = deterministicEngine.calculatePhysiology(telemetry, baseline)
        val liveVal = when (targetMetric) {
            com.vanta.app.ui.screens.PhysiologyMetric.RECOVERY -> det.recovery.toFloat()
            com.vanta.app.ui.screens.PhysiologyMetric.STRAIN -> det.strain.toFloat()
            com.vanta.app.ui.screens.PhysiologyMetric.ENERGY -> det.energy.toFloat()
        }
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val cacheKey = "INLINE_${targetMetric.name}"

        val isProviderReady = if (provider == AiProvider.ON_DEVICE_LITERT) {
            com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context).isModelDownloaded()
        } else {
            !apiKey.isNullOrBlank()
        }

        val cached = aiDetailCache.load(cacheKey)
        if (cached != null && aiDetailCache.isValid(
                entry = cached,
                targetRecovery = det.recovery,
                targetEnergy = det.energy,
                targetStrain = det.strain,
                targetWorkoutMinutes = telemetry.exerciseMinutes,
                currentDate = today,
                currentHour = currentHour
            )
        ) {
            if (cached.isAiGenerated) {
                val syncText = dynamicallySyncMetricText(cached.text, targetMetric, liveVal, det)
                return@withContext syncText to true
            }
        }

        if (!isProviderReady) {
            return@withContext "" to false
        }

        val prompt = CoachPromptSystem.detailPrompt(
            targetMetric = targetMetric,
            telemetry = telemetry,
            det = det,
            baseline = baseline,
            profile = profile,
            history = history,
            provider = provider
        )

        val rawOutput = runCatching {
            withTimeoutOrNull(20_000L) {
                generateWithProvider(
                    systemPrompt = prompt.system,
                    userPrompt = prompt.user,
                    apiKey = apiKey ?: "",
                    provider = provider,
                    maxTokens = 500
                )
            }
        }.getOrNull()

        if (rawOutput != null) {
            val extracted = com.vanta.app.data.ai.PhysiologyInsightPromptSystem.extractProseFromRaw(rawOutput)
            val validated = extracted?.let {
                com.vanta.app.data.ai.PhysiologyTemplateSelector.cleanAndValidateAiResponse(
                    raw = it,
                    maxWords = 50,
                    maxSentences = 3
                ) ?: it.take(300)
            }.orEmpty()

            // Reject truncated fragments that don't end in sentence punctuation.
            val trimmedValidated = validated.trimEnd()
            val isComplete = trimmedValidated.isNotBlank() &&
                (trimmedValidated.endsWith('.') || trimmedValidated.endsWith('!') || trimmedValidated.endsWith('?'))
            if (isComplete) {
                aiDetailCache.save(
                    metricKey = cacheKey,
                    date = today,
                    hourOfDay = currentHour,
                    recovery = det.recovery,
                    energy = det.energy,
                    strain = det.strain,
                    workoutMinutes = telemetry.exerciseMinutes,
                    text = validated,
                    isAiGenerated = true
                )
                return@withContext validated to true
            }
        }

        "" to false
    }

    /**
     * Generates an expanded "Vanta Coach" deep-dive breakdown (3-5 sentences) with:
     * - Data-based persistent caching (keyed to recovery, strain, energy, hour, date)
     * - The "why": references specific numbers, baseline comparisons, and physiological rationale
     * - Coach-to-athlete tone, second person, ends with concrete athletic action
     * - Safety net: ~400 char length cap, markdown stripping, & disallowed medical claim check
     */
    suspend fun generateVantaCoachDeepInsight(
        targetMetric: com.vanta.app.ui.screens.PhysiologyMetric,
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline,
        apiKey: String?,
        provider: AiProvider,
        context: Context,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList()
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val det = deterministicEngine.calculatePhysiology(telemetry, baseline)
        val liveVal = when (targetMetric) {
            com.vanta.app.ui.screens.PhysiologyMetric.RECOVERY -> det.recovery.toFloat()
            com.vanta.app.ui.screens.PhysiologyMetric.STRAIN -> det.strain.toFloat()
            com.vanta.app.ui.screens.PhysiologyMetric.ENERGY -> det.energy.toFloat()
        }
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val cacheKey = "DEEP_${targetMetric.name}"

        val hasKeyOrOnDevice = (!apiKey.isNullOrBlank()) ||
            (provider == AiProvider.ON_DEVICE_LITERT && com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context).isModelDownloaded())

        val cached = aiDetailCache.load(cacheKey)
        if (cached != null && aiDetailCache.isValid(
                entry = cached,
                targetRecovery = det.recovery,
                targetEnergy = det.energy,
                targetStrain = det.strain,
                targetWorkoutMinutes = telemetry.exerciseMinutes,
                currentDate = today,
                currentHour = currentHour
            )
        ) {
            if (cached.isAiGenerated) {
                val syncText = dynamicallySyncMetricText(cached.text, targetMetric, liveVal, det)
                return@withContext syncText to true
            }
        }

        if (!hasKeyOrOnDevice) {
            return@withContext "Vanta Coach is not configured yet. Please enter your API key in Settings or download the On-Device Gemma model." to false
        }

        val prompt = com.vanta.app.data.ai.PhysiologyInsightPromptSystem.vantaCoachDeepPrompt(
            targetMetric = targetMetric,
            telemetry = telemetry,
            det = det,
            baseline = baseline,
            profile = profile,
            history = history,
            provider = provider
        )

        val rawOutput = runCatching {
            withTimeoutOrNull(25_000L) {
                generateWithProvider(
                    systemPrompt = prompt.system,
                    userPrompt = prompt.user,
                    apiKey = apiKey ?: "",
                    provider = provider,
                    maxTokens = 600
                )
            }
        }.getOrNull()

        if (rawOutput != null) {
            val extracted = com.vanta.app.data.ai.PhysiologyInsightPromptSystem.extractProseFromRaw(rawOutput)
            val validated = extracted?.let {
                com.vanta.app.data.ai.PhysiologyTemplateSelector.cleanAndValidateAiResponse(
                    raw = it,
                    maxWords = 120,
                    maxSentences = 5
                ) ?: it.take(600)
            }.orEmpty()

            // Reject truncated fragments that don't end in sentence punctuation.
            val trimmedValidated = validated.trimEnd()
            val isComplete = trimmedValidated.isNotBlank() &&
                (trimmedValidated.endsWith('.') || trimmedValidated.endsWith('!') || trimmedValidated.endsWith('?'))
            if (isComplete) {
                aiDetailCache.save(
                    metricKey = cacheKey,
                    date = today,
                    hourOfDay = currentHour,
                    recovery = det.recovery,
                    energy = det.energy,
                    strain = det.strain,
                    workoutMinutes = telemetry.exerciseMinutes,
                    text = validated,
                    isAiGenerated = true
                )
                return@withContext validated to true
            }
        }

        // No AI reachable (offline / provider error): keep the page honest with a
        // short connection note instead of a canned deterministic breakdown.
        return@withContext com.vanta.app.data.ai.PhysiologyInsightPromptSystem.OFFLINE_MESSAGE to false
    }

    /**
     * Streams Vanta Coach deep-dive insight in real time word-by-word.
     */
    fun streamVantaCoachDeepInsight(
        targetMetric: com.vanta.app.ui.screens.PhysiologyMetric,
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline,
        apiKey: String?,
        provider: AiProvider,
        context: Context,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList()
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        val (finalText, isAi) = generateVantaCoachDeepInsight(
            targetMetric = targetMetric,
            telemetry = telemetry,
            baseline = baseline,
            apiKey = apiKey,
            provider = provider,
            context = context,
            profile = profile,
            history = history
        )
        if (finalText.isBlank()) return@flow

        val words = finalText.split(Regex("(?<=\\s)|(?=\\s)"))
        for (w in words) {
            emit(w)
            kotlinx.coroutines.delay(22)
        }
    }

    /** Builds a fresh result with CURRENT numbers and CACHED AI overview and callout text. */
    private fun buildFromCache(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        cached: AiOverviewCache.Entry
    ): GemmaAiAnalysis {
        val cleanOverview = if (cached.overview.contains("{") || cached.overview.startsWith("json", ignoreCase = true) || cached.overview.length < 15) {
            generateCoachOverview(t, det, baseline)
        } else {
            cached.overview
        }
        val syncedOverview = dynamicallySyncOverview(cleanOverview, det, t)
        val syncedCallouts = cached.callouts.map { c ->
            GemmaCallout(dynamicallySyncOverview(c.text, det, t), c.colorHex)
        }
        return GemmaAiAnalysis(
            strain = det.strain,
            recovery = det.recovery,
            recoveryCategoryLabel = det.recoveryCategory.label,
            energy = det.energy,
            overview = syncedOverview,
            callouts = if (syncedCallouts.isNotEmpty()) syncedCallouts else generateCoachCallouts(t, det, baseline),
            recommendation = cached.recommendation,
            isLearningPhase = baseline.isLearningPhase,
            savedDaysCount = baseline.savedDaysCount,
            baselineMessage = baseline.subtleStatusMessage,
            isModelLoaded = true
        )
    }

    internal fun dynamicallySyncMetricText(
        text: String,
        targetMetric: com.vanta.app.ui.screens.PhysiologyMetric,
        liveVal: Float,
        det: DeterministicPhysiologyResult
    ): String {
        return when (targetMetric) {
            com.vanta.app.ui.screens.PhysiologyMetric.STRAIN -> {
                val str = "%.1f".format(liveVal)
                text.replace(Regex("(\\d+\\.\\d+)\\s*/\\s*21(\\.0)?"), "$str / 21.0")
                    .replace(Regex("(\\d+\\.\\d+)\\s+strain"), "$str strain")
            }
            com.vanta.app.ui.screens.PhysiologyMetric.RECOVERY -> {
                text.replace(Regex("(\\d+)%\\s+recovery"), "${det.recovery}% recovery")
                    .replace(Regex("Recovery\\s+(?:dipped to|reached|holds at|sits at|at)\\s+(\\d+)%"), "Recovery holds at ${det.recovery}%")
            }
            com.vanta.app.ui.screens.PhysiologyMetric.ENERGY -> {
                text.replace(Regex("(\\d+)%\\s+energy"), "${det.energy}% energy")
                    .replace(Regex("Energy\\s+(?:reserves are reduced at|reserves sit high at|is balanced at|at)\\s+(\\d+)%"), "Energy is balanced at ${det.energy}%")
            }
        }
    }

    internal fun dynamicallySyncOverview(
        text: String,
        det: DeterministicPhysiologyResult,
        t: HealthConnectTelemetry? = null
    ): String {
        val str = "%.1f".format(det.strain)
        var updated = text
            .replace(Regex("(\\d+\\.\\d+)\\s*/\\s*21(\\.0)?"), "$str / 21.0")
            .replace(Regex("(\\d+\\.\\d+)\\s+strain"), "$str strain")
            .replace(Regex("(\\d+)%\\s+recovery"), "${det.recovery}% recovery")
            .replace(Regex("(\\d+)%\\s+energy"), "${det.energy}% energy")

        if (t != null && t.steps > 0) {
            val stepFmt = "%,d".format(t.steps)
            updated = updated.replace(Regex("([0-9,]+)\\s+steps", RegexOption.IGNORE_CASE), "$stepFmt steps")
        }
        if (t != null && t.calories > 0) {
            val calFmt = "%,d".format(t.calories)
            updated = updated.replace(Regex("([0-9,]+)\\s+kcal", RegexOption.IGNORE_CASE), "$calFmt kcal")
                .replace(Regex("([0-9,]+)\\s+calories", RegexOption.IGNORE_CASE), "$calFmt calories")
        }
        return updated
    }

    // ── AI COACH: personalized, metric-grounded daily coaching ─────────────────

    /**
     * Coaching instructions passed to the on-device model. The model ONLY writes the
     * short overview prose (2–3 sentences) as JSON; callouts are always generated
     * deterministically from real metrics so they can never drift from the data.
     */
    private fun parseStructuredJson(
        rawOutput: String,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        telemetry: HealthConnectTelemetry
    ): GemmaAiAnalysis? {
        val trimmed = rawOutput.trim()
        if (trimmed.isEmpty()) return null

        val fallbackCallouts = generateCoachCallouts(telemetry, det, baseline)
        val fallbackOverview = generateCoachOverview(telemetry, det, baseline)

        // Strip markdown code fences (```json ... ``` or ``` ...)
        val cleanRaw = trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        // 1. Try parsing JSON format
        val jsonStart = cleanRaw.indexOf("{")
        val jsonEnd = cleanRaw.lastIndexOf("}")
        if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            try {
                val jsonStr = cleanRaw.substring(jsonStart, jsonEnd + 1)
                val obj = JSONObject(jsonStr)
                val callouts = parseModelCallouts(obj, fallbackCallouts)
                var rawOverview = obj.optString("overview", "").trim()
                if (rawOverview.isBlank()) {
                    rawOverview = obj.optString("summary", "").trim()
                }
                if (rawOverview.isBlank()) {
                    rawOverview = obj.optString("insight", "").trim()
                }
                if (rawOverview.isBlank() || rawOverview.startsWith("{") || rawOverview.startsWith("json", ignoreCase = true)) {
                    rawOverview = fallbackOverview
                }

                val sentences = rawOverview
                    .split(Regex("(?<=[.!?]) +"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val combined = if (sentences.size <= 4) rawOverview else sentences.take(4).joinToString(" ")
                // Reject mid-sentence fragments so the home card never shows "a few words and stop".
                val overview = if (isCompleteProse(combined)) combined else fallbackOverview
                return GemmaAiAnalysis(
                    strain = det.strain,
                    recovery = det.recovery,
                    recoveryCategoryLabel = det.recoveryCategory.label,
                    energy = det.energy,
                    overview = overview,
                    callouts = callouts,
                    recommendation = obj.optString("recommendation", getCoachRecommendation(det.recovery)),
                    isLearningPhase = baseline.isLearningPhase,
                    savedDaysCount = baseline.savedDaysCount,
                    baselineMessage = baseline.subtleStatusMessage,
                    isModelLoaded = true
                )
            } catch (e: Exception) {
                // Fallthrough to prose extraction
            }
        }

        // 2. Resilient plain-text fallback (clean any stray JSON / markdown tokens)
        val cleanProse = cleanRaw
            .replace(Regex("(?i)^json\\s*\\{?"), "")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("[{}\"]"), "")
            .trim()

        val sentences = cleanProse
            .split(Regex("(?<=[.!?]) +"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("overview:", ignoreCase = true) }

        val overview = if (sentences.isNotEmpty()) {
            sentences.take(3).joinToString(" ")
        } else {
            fallbackOverview
        }

        val finalOverview = if (isCompleteProse(overview)) overview else fallbackOverview

        return GemmaAiAnalysis(
            strain = det.strain,
            recovery = det.recovery,
            recoveryCategoryLabel = det.recoveryCategory.label,
            energy = det.energy,
            overview = finalOverview,
            callouts = fallbackCallouts,
            recommendation = getCoachRecommendation(det.recovery),
            isLearningPhase = baseline.isLearningPhase,
            savedDaysCount = baseline.savedDaysCount,
            baselineMessage = baseline.subtleStatusMessage,
            isModelLoaded = true
        )
    }

    // ── Metric signal snapshot ──────────────────────────────────────────────────
    private data class CoachState(
        val recovery: Int,
        val energy: Int,
        val strain: Double,
        val steps: Long,
        val stepsPct: Double,      // fraction of the user's usual daily steps
        val exerciseMinutes: Int,
        val workoutLabel: String?, // e.g. "high-intensity session (55 min)", null if none
        val rhrDelta: Int,         // baseline - today (positive = RHR lower = better)
        val rhrToday: Int,         // today's resting HR in bpm (0 if not registered yet)
        val rhrBaseline: Int,      // user's normal resting HR in bpm (0 if unknown)
        val strainVsWeek: Double,  // today's strain - weekly avg strain
        val avgStrain: Double,     // user's 7-day average strain
        val avgSteps: Double,      // user's usual daily steps
        val hasHrData: Boolean,
        val dataLimited: Boolean,
        val daySeed: Int,
        val hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    )

    private fun isDataLimited(t: HealthConnectTelemetry): Boolean =
        t.steps < 2500 && t.currentBpm == 0 && t.avgBpm == 0 && t.exerciseMinutes < 20

    private fun buildCoachState(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline
    ): CoachState {
        val stepsTarget = baseline.avgSteps.coerceAtLeast(4000.0)
        val stepsPct = (t.steps.toDouble() / stepsTarget).coerceIn(0.0, 1.5)
        val rhrDelta = det.rhrBaseline - det.rhrToday
        val hasHrData = t.avgBpm in 40..220 || t.peakBpm in 40..220 || t.currentBpm in 40..220
        val workoutLabel = if (t.exerciseMinutes > 0) {
            val intensity = when {
                det.hrMax > 0 && (t.avgBpm >= det.hrMax * 0.75 || t.peakBpm >= det.hrMax * 0.9) -> "high-intensity"
                det.hrMax > 0 && t.avgBpm >= det.hrMax * 0.6 -> "steady"
                else -> "easy"
            }
            "$intensity session (${t.exerciseMinutes} min)"
        } else null
        return CoachState(
            recovery = det.recovery,
            energy = det.energy,
            strain = det.strain,
            steps = t.steps,
            stepsPct = stepsPct,
            exerciseMinutes = t.exerciseMinutes,
            workoutLabel = workoutLabel,
            rhrDelta = rhrDelta,
            rhrToday = det.rhrToday,
            rhrBaseline = det.rhrBaseline,
            strainVsWeek = det.strain - baseline.avgStrain,
            avgStrain = baseline.avgStrain,
            avgSteps = baseline.avgSteps,
            hasHrData = hasHrData,
            dataLimited = isDataLimited(t),
            daySeed = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString().hashCode(),
            hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        )
    }

    /** Splits on sentence punctuation and caps the count so the overview never balloons. */
    private fun capSentences(text: String, max: Int): String {
        val sentences = text.split(Regex("(?<=[.!?]) +")).map { it.trim() }.filter { it.isNotEmpty() }
        return sentences.take(max).joinToString(" ")
    }

    /** Deterministic pick from a rotated pool so phrasing changes every day. */
    private fun pick(pool: List<String>, seed: Int, salt: Int): String {
        val idx = ((seed + salt) % pool.size + pool.size) % pool.size
        return pool[idx]
    }

    // ── Callout modules (each grounded in one real metric) ────────────────────
    private fun recoveryCallout(c: CoachState): GemmaCallout {
        val r = c.recovery
        val isEvening = c.hourOfDay >= 17 || c.hourOfDay < 5
        return when {
            r >= 85 -> GemmaCallout(pick(if (isEvening) listOf(
                "You're at $r% recovery. Bank good rest tonight so you're ready to hit it tomorrow.",
                "$r% recovery banked. Relax tonight and save the heavy effort for tomorrow.",
                "Recovery reads $r%. Protect that foundation with a full night of sleep."
            ) else listOf(
                "You're at $r% recovery — today is a go day. Push the intensity while you have it.",
                "$r% recovery on your side. Give the main lift everything, then stop.",
                "Recovery reads $r%. That's your green light for a real session."
            ), c.daySeed, 0), "#39FF80")
            r >= 70 -> GemmaCallout(pick(if (isEvening) listOf(
                "At $r% recovery, unwind tonight so your energy stays high tomorrow.",
                "$r% recovery today. Keep the evening low key and recharge.",
                "Solid $r% recovery. Bank your rest tonight for tomorrow's session."
            ) else listOf(
                "At $r% recovery, train with intent — just keep the ego in check.",
                "$r% is the sweet spot: solid effort, honest volume, no heroics.",
                "$r% recovery today. Quality over quantity is the whole game."
            ), c.daySeed, 0), "#39FF80")
            r >= 55 -> GemmaCallout(pick(if (isEvening) listOf(
                "$r% recovery today. Keep the evening easy and prioritize sleep.",
                "Recovery's at $r% — get to sleep early tonight to build back up.",
                "$r% means the tank needs sleep. Relax tonight, train tomorrow."
            ) else listOf(
                "$r% recovery. Treat today as a build day, not a smash day.",
                "Recovery's at $r% — cut the volume, keep the quality.",
                "$r% means the tank's half-full. Bank the session, skip the extras."
            ), c.daySeed, 0), "#FFB000")
            else -> GemmaCallout(pick(listOf(
                "$r% recovery tonight — make sleep the absolute priority.",
                "At $r%, early sleep tonight is the best workout you can do.",
                "$r% today. Unwind early — recovery is the whole game tonight."
            ), c.daySeed, 0), "#FF5252")
        }
    }

    private fun energyCallout(c: CoachState): GemmaCallout {
        val e = c.energy
        val isEvening = c.hourOfDay >= 17 || c.hourOfDay < 5
        return when {
            e >= 75 -> GemmaCallout(pick(if (isEvening) listOf(
                "Energy sits high at $e%. Ideal state for active recovery tonight.",
                "$e% energy remaining. Keep the evening relaxed and recharge for tomorrow.",
                "Energy's at $e%. Finish the evening calm and bank the recovery."
            ) else listOf(
                "You've got $e% to spend. Front-load your main priorities while it's there.",
                "$e% energy — make the first half of the day count.",
                "Energy's high at $e%. Tackle your main effort early."
            ), c.daySeed, 1), "#FFAA00")
            e >= 50 -> GemmaCallout(pick(if (isEvening) listOf(
                "$e% energy. Moderate reserves left — keep the evening low key.",
                "You've got $e% energy remaining tonight. Time to wind down.",
                "$e% energy tonight — focus on solid rest."
            ) else listOf(
                "$e% energy. Moderate effort, clean execution — no wasted sets.",
                "You've got enough at $e%. Hit the main tasks, skip the fluff.",
                "$e% is a deliver-and-rest number. Keep your effort focused."
            ), c.daySeed, 1), "#FFAA00")
            else -> GemmaCallout(pick(listOf(
                "Only $e% available. Save the gas for what matters most.",
                "$e% energy — the goal is recovery, not output. Keep movement easy.",
                "Energy's low at $e%. A short walk beats a long grind."
            ), c.daySeed, 1), "#FF5252")
        }
    }

    private fun strainCallout(c: CoachState): GemmaCallout? {
        val s = c.strain
        val fmt = "%.1f".format(s)
        val weekFmt = "%.1f".format(c.avgStrain)
        val w = c.avgStrain > 0.5 // weekly average is real once the baseline has data
        // Honest comparison vs the user's own weekly average (skipped while building baseline).
        val rel = if (w) when {
            c.strainVsWeek >= 2.0 -> "${"%.1f".format(c.strainVsWeek)} above your $weekFmt weekly average"
            c.strainVsWeek <= -2.0 -> "light next to your $weekFmt weekly average"
            else -> "right around your $weekFmt weekly average"
        } else null
        return when {
            s < 1.0 -> GemmaCallout(pick(listOf(
                "Nothing logged yet (strain $fmt). The day is still yours to shape.",
                "Strain sits at $fmt — wide open. A session still fits comfortably.",
                "No real strain yet ($fmt). Decide where today's effort goes."
            ), c.daySeed, 2), "#00F5FF")
            s < 4.0 -> GemmaCallout(pick(listOf(
                if (rel != null) "Strain $fmt so far — $rel. There's plenty of runway for a workout."
                else "Strain $fmt so far — light. There's plenty of runway for a workout.",
                if (rel != null) "You've logged $fmt strain ($rel). The heavy part of the day is still ahead of you."
                else "You've logged $fmt strain. The heavy part of the day is still ahead of you.",
                "$fmt is a gentle start. The question is what you do with the rest of the day."
            ), c.daySeed, 2), "#00F5FF")
            s < 7.0 -> GemmaCallout(pick(listOf(
                if (rel != null) "Strain's building at $fmt ($rel). If you train now, keep the session tight."
                else "Strain's building at $fmt. If you train now, keep the session tight.",
                if (rel != null) "$fmt already — $rel. Good base, don't stack a second hard session."
                else "$fmt already. Good base — don't stack a second hard session on top.",
                "You're at $fmt strain. A focused session is fine; a marathon isn't."
            ), c.daySeed, 2), "#00F5FF")
            s < 10.0 -> GemmaCallout(pick(listOf(
                if (rel != null) "Strain $fmt — $rel. That's a real day's work; protect the rest of it."
                else "Strain $fmt — that's a real day's work. Protect the rest of it.",
                if (rel != null) "You're deep into the day at $fmt ($rel). The last reps count, the extras don't."
                else "You're deep into the day at $fmt. The last reps count, the extras don't.",
                if (rel != null) "$fmt strain logged, $rel. Everything after this should be maintenance."
                else "$fmt strain logged. Everything after this should be maintenance."
            ), c.daySeed, 2), "#00E5FF")
            else -> GemmaCallout(pick(listOf(
                if (rel != null) "Strain is at $fmt — $rel. That's the ceiling for today; shut it down soon."
                else "Strain is at $fmt. That's the ceiling for today — shut it down soon.",
                if (rel != null) "$fmt strain is a serious number ($rel). Recovery mode starts now."
                else "$fmt strain is a serious number. Recovery mode starts now.",
                if (rel != null) "You've hit $fmt — $rel. Be proud of the work, then let the body reset."
                else "You've hit $fmt. Be proud of the work, then let the body reset."
            ), c.daySeed, 2), "#FF5252")
        }
    }

    private fun stepsCallout(c: CoachState): GemmaCallout {
        val pct = (c.stepsPct * 100).roundToInt()
        val avg = c.avgSteps.roundToInt()
        return when {
            pct >= 100 -> GemmaCallout(pick(listOf(
                "${c.steps} steps — past your usual $avg. The movement box is checked; now it's about training.",
                "You've cleared your typical $avg steps (${c.steps} today). Move well, no more is needed.",
                "${c.steps} steps banked, well over your usual $avg. Keep the afternoon easy."
            ), c.daySeed, 3), "#00F2FE")
            pct >= 50 -> GemmaCallout(pick(listOf(
                "You're at $pct% of your usual $avg steps (${c.steps}). A short walk gets you home.",
                "$pct% of your typical $avg steps logged (${c.steps}). Fifteen easy minutes fixes the rest.",
                "${c.steps} steps so far — $pct% of your usual $avg. Don't force it; a loop around the block is enough."
            ), c.daySeed, 3), "#00F2FE")
            else -> GemmaCallout(pick(listOf(
                "Only ${c.steps} steps so far — $pct% of your usual $avg. Even 15 minutes of walking changes the day.",
                "${c.steps} steps, well under your usual $avg. A short walk resets everything.",
                "You're at $pct% of your usual $avg steps. Movement is the cheapest recovery tool you have."
            ), c.daySeed, 3), "#00F2FE")
        }
    }

    private fun hrTrendCallout(c: CoachState): GemmaCallout? {
        if (!c.hasHrData || c.rhrToday <= 0 || c.rhrBaseline <= 0) return null
        return when {
            c.rhrDelta >= 2 -> GemmaCallout(pick(listOf(
                "Resting HR is ${c.rhrToday} bpm — ${c.rhrDelta} below your ${c.rhrBaseline} norm. Yesterday was absorbed; today you can push.",
                "Morning RHR is ${c.rhrToday} bpm, under your usual ${c.rhrBaseline}. The engine is ready again.",
                "RHR ${c.rhrToday} vs baseline ${c.rhrBaseline} — a clean bill for training."
            ), c.daySeed, 4), "#BF5AF2")
            c.rhrDelta >= -1 -> GemmaCallout(pick(listOf(
                "Resting HR is ${c.rhrToday} bpm, right at your ${c.rhrBaseline} baseline — nothing carrying over from yesterday.",
                "RHR ${c.rhrToday} matches your normal ${c.rhrBaseline}. Train on your own terms today.",
                "Resting HR at baseline (${c.rhrToday} bpm). A clean slate to work from."
            ), c.daySeed, 4), "#BF5AF2")
            else -> GemmaCallout(pick(listOf(
                "Resting HR is ${c.rhrToday} bpm, ${-c.rhrDelta} above your ${c.rhrBaseline} norm. Keep today's effort to a 6 out of 10.",
                "Morning RHR elevated at ${c.rhrToday} — yesterday's load is still settling. Go easy.",
                "RHR ${c.rhrToday} is running above your ${c.rhrBaseline} norm. Light work only until it settles."
            ), c.daySeed, 4), "#FFB000")
        }
    }

    private fun workoutCallout(c: CoachState): GemmaCallout? {
        val label = c.workoutLabel ?: return null
        return when {
            label.startsWith("high") -> GemmaCallout(pick(listOf(
                "You logged a $label. That's the day's strain story — keep everything after it light.",
                "$label banked. Hard work done; the recovery clock is running.",
                "Big session in the books ($label). The rest of today is maintenance."
            ), c.daySeed, 5), "#FF5252")
            label.startsWith("steady") -> GemmaCallout(pick(listOf(
                "A $label. Clean, controlled work — exactly what builds.",
                "$label — quality minutes, nothing wasted.",
                "You put in a $label. Steady progress beats a chaotic week."
            ), c.daySeed, 5), "#FFAA00")
            else -> GemmaCallout(pick(listOf(
                "Light movement ($label). The right call for where you're at.",
                "$label — sometimes that IS the workout.",
                "Kept it easy ($label). Honest work for a recovery day."
            ), c.daySeed, 5), "#00F2FE")
        }
    }

    private fun weeklyTrendCallout(c: CoachState): GemmaCallout {
        val diff = c.strainVsWeek
        val fmt = "%.1f".format(abs(diff))
        return when {
            diff > 2.0 -> GemmaCallout(pick(listOf(
                "Today's strain is $fmt above your weekly average. That's a spike — respect it and ease off after.",
                "You're running $fmt over your usual load. Tomorrow will ask you to go easy.",
                "$fmt heavier than your typical day. Strong work, but bank some rest."
            ), c.daySeed, 6), "#FFB000")
            diff < -2.0 -> GemmaCallout(pick(listOf(
                "Today is $fmt under your weekly average. A strategic easy day, not a waste.",
                "Light day compared to your week ($fmt under). The body banks that.",
                "$fmt lighter than usual. Use it — the next hard day will feel fresher."
            ), c.daySeed, 6), "#39FF80")
            else -> GemmaCallout(pick(listOf(
                "Today's load lines up with your weekly average. Consistency is the whole story.",
                "Strain matches your usual week. Steady, repeatable, no surprises.",
                "Right on your weekly pace. The best weeks are boring like this."
            ), c.daySeed, 6), "#00F2FE")
        }
    }

    /**
     * 3–5 callouts, each grounded in a real metric. Recovery and Energy always
     * lead; the rest are selected in a daily-rotated order and phrased from
     * rotated pools, so the same state never produces the same card twice.
     */
    private fun generateCoachCallouts(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline
    ): List<GemmaCallout> {
        val c = buildCoachState(t, det, baseline)
        val workout = workoutCallout(c)
        return if (workout != null) {
            listOf(workout, recoveryCallout(c))
        } else {
            listOf(recoveryCallout(c), energyCallout(c))
        }
    }

    /** 2–3 sentence overview: the day's story, the next action, and where it stands. */
    private fun generateCoachOverview(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline
    ): String {
        val c = buildCoachState(t, det, baseline)
        val r = det.recovery
        val e = det.energy
        val isEvening = c.hourOfDay >= 17 || c.hourOfDay < 5

        // 60 generic templates (GenericCoachTemplates.kt) selected by recovery level,
        // time of day, and a daily rotation. Workouts are deliberately never mentioned,
        // so a day with zero workout minutes is never called out — the briefing always
        // reads naturally from the numbers that ARE present.
        val pool: List<String> = when {
            c.dataLimited && c.hourOfDay in 5..11 -> com.vanta.app.data.GenericCoachTemplates.DATA_LIMITED
            isEvening && r >= 85 -> com.vanta.app.data.GenericCoachTemplates.EVE_HIGH
            isEvening && r >= 70 -> com.vanta.app.data.GenericCoachTemplates.EVE_GOOD
            isEvening && r >= 55 -> com.vanta.app.data.GenericCoachTemplates.EVE_MODERATE
            isEvening -> com.vanta.app.data.GenericCoachTemplates.EVE_LOW
            r >= 85 -> com.vanta.app.data.GenericCoachTemplates.DAY_HIGH
            r >= 70 -> com.vanta.app.data.GenericCoachTemplates.DAY_GOOD
            r >= 55 -> com.vanta.app.data.GenericCoachTemplates.DAY_MODERATE
            else -> com.vanta.app.data.GenericCoachTemplates.DAY_LOW
        }
        return com.vanta.app.data.GenericCoachTemplates.render(pick(pool, c.daySeed, 7), r, e, det.strain)
    }

    fun generateDeterministicInsights(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline
    ): GemmaAiAnalysis {
        return GemmaAiAnalysis(
            strain = det.strain,
            recovery = det.recovery,
            recoveryCategoryLabel = det.recoveryCategory.label,
            energy = det.energy,
            overview = generateCoachOverview(t, det, baseline),
            callouts = generateCoachCallouts(t, det, baseline),
            recommendation = getCoachRecommendation(det.recovery),
            isLearningPhase = baseline.isLearningPhase,
            savedDaysCount = baseline.savedDaysCount,
            baselineMessage = baseline.subtleStatusMessage,
            isModelLoaded = false
        )
    }

    /**
     * Reads today's cached AI analysis without network or IO blocking. Returns non-null
     * if a genuine AI model generation is cached and still valid for today's numbers.
     * Never overwrites or destroys the cache with fallback templates.
     */
    fun instantCachedAnalysis(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline
    ): GemmaAiAnalysis? {
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val cached = aiCache.load() ?: return null
        val hasJargon = cached.overview.lowercase().contains("physiological status") || cached.overview.lowercase().contains("systemic fatigue")
        if (hasJargon) {
            aiCache.clear()
            return null
        }
        if (cached.date != today) return null
        val recoveryDiff = kotlin.math.abs(cached.recovery - det.recovery)
        val energyDiff = kotlin.math.abs(cached.energy - det.energy)
        val strainDiff = kotlin.math.abs(cached.strain - det.strain)
        if (recoveryDiff <= 3 && energyDiff <= 3 && strainDiff < 0.5 && t.exerciseMinutes <= cached.workoutMinutes) {
            return buildFromCache(t, det, baseline, cached)
        }
        return null
    }

    fun clearCache() {
        aiCache.clear()
        aiDetailCache.clearAll()
        vantixPrefs.edit().clear().apply()
        prefs.edit().remove("ai_cache_time_slot").remove("vanta_ai_cache_provider").apply()
    }

    fun hasDataChangedSinceCache(
        telemetry: HealthConnectTelemetry,
        detResult: DeterministicPhysiologyResult
    ): Boolean {
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val currentSlot = when (currentHour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
        val cachedSlot = prefs.getString("ai_cache_time_slot", "")
        val cached = aiCache.load() ?: return true

        if (cached.date != today || cachedSlot != currentSlot || !cached.generatedWithModel) return true
        if (kotlin.math.abs(cached.recovery - detResult.recovery) > 2) return true
        if (kotlin.math.abs(cached.energy - detResult.energy) > 2) return true
        if (kotlin.math.abs(detResult.strain - cached.strain) >= 0.4) return true
        if (telemetry.exerciseMinutes > cached.workoutMinutes) return true

        return false
    }

    private fun getCoachRecommendation(recovery: Int): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isEvening = hour >= 17 || hour < 5
        return when {
            isEvening && recovery >= 85 -> "Recharge for tomorrow."
            isEvening && recovery >= 70 -> "Time to wind down."
            isEvening -> "Focus on rest tonight."
            recovery >= 85 -> "Full send — body ready."
            recovery >= 75 -> "Train hard, keep reps sharp."
            recovery >= 60 -> "Clean effort, stay steady."
            recovery >= 40 -> "Light movement day."
            else -> "Rest and reset."
        }
    }

    // ── VANTIX AI Insight ─────────────────────────────────────────────────────

    private val vantixPrefs = context.getSharedPreferences("vanta_vantix_cache", Context.MODE_PRIVATE)

    /**
     * Generates a 2-3 sentence VANTIX-specific AI insight about the user's
     * training load pattern (ATL/CTL/TSB/mode/step trend).
     *
     * Caching rules (never spams the API):
     *  - 6-hour TTL — VANTIX reads multi-day patterns, not hourly changes
     *  - Invalidated if loadStatus changed, isTrainingMode changed, or ATL shifted > 1.5
     *  - Falls back to a deterministic template if no API key or rate-limited
     *  - Routes through the shared CloudGate (max 8 calls/min)
     */
    suspend fun generateVantixInsight(
        core: com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.AdaptiveCoreResult,
        apiKey: String?,
        provider: AiProvider
    ): String = withContext(Dispatchers.IO) {
        val fingerprint = "${core.loadStatus.name}_${core.isTrainingMode}_${"%.1f".format(core.atl)}_${provider.name}"
        val cachedFingerprint = vantixPrefs.getString("vantix_fingerprint", "")
        val cachedTimestamp = vantixPrefs.getLong("vantix_timestamp", 0L)
        val cachedText = vantixPrefs.getString("vantix_text", null)
        val sixHoursMs = 6 * 60 * 60 * 1000L
        val cacheValid = cachedText != null &&
            cachedFingerprint == fingerprint &&
            System.currentTimeMillis() - cachedTimestamp < sixHoursMs

        if (cacheValid) {
            android.util.Log.d("VantixAI", "Cache hit — returning stored insight")
            return@withContext cachedText!!
        }

        val hasKeyOrOnDevice = (!apiKey.isNullOrBlank()) ||
            (provider == AiProvider.ON_DEVICE_LITERT && com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context).isModelDownloaded())

        // Try AI if key or on-device model is available
        if (hasKeyOrOnDevice) {
            try {
                val system = """
                    You are Vanta's coach — the same trainer who knows this athlete personally.
                    Write exactly 2 sentences of calm, specific coaching insight (STRICT LIMIT: 35 to 55 words total) about their
                    current training load pattern. Reference the actual numbers you receive.
                    No markdown. No greetings. No hype, exclamation marks, cheerleading, or motivational fluff. Plain, direct, conversational English.
                """.trimIndent()

                val modeLabel = if (core.isTrainingMode) "Training Mode" else "Daily Mover Mode"
                val stepTrendLabel = when {
                    core.stepTrend > 400  -> "strongly increasing"
                    core.stepTrend > 100  -> "gradually increasing"
                    core.stepTrend < -400 -> "strongly declining"
                    core.stepTrend < -100 -> "slightly declining"
                    else                  -> "holding steady"
                }
                val user = buildString {
                    append("Mode: $modeLabel. ")
                    append("Status: ${core.loadStatus.label}. ")
                    append("ATL (7-day load): ${"%.1f".format(core.atl)}. ")
                    append("CTL (42-day base): ${"%.1f".format(core.ctl)}. ")
                    append("TSB (freshness): ${"%.1f".format(core.tsb)}. ")
                    append("ATL/CTL ratio: ${"%.2f".format(core.atlCtlRatio)}. ")
                    append("Activity consistency: ${(core.activityConsistency * 100).roundToInt()}%. ")
                    append("Days tracked: ${core.totalDaysTracked}. ")
                    if (!core.isTrainingMode) {
                        append("14-day step trend: $stepTrendLabel. ")
                        append("14-day avg steps: ${core.avgSteps14d.toLong()}. ")
                    }
                    append("Readiness trend (7-day slope): ${"%.2f".format(core.readinessTrend)}/day.")
                }

                val text = generateWithProvider(
                    systemPrompt = system,
                    userPrompt = user,
                    apiKey = apiKey ?: "",
                    provider = provider,
                    connectTimeoutMs = 12_000,
                    readTimeoutMs = 20_000
                )?.trim()

                if (!text.isNullOrBlank() && text.length > 20) {
                    android.util.Log.d("VantixAI", "AI insight generated — caching for 6h")
                    vantixPrefs.edit()
                        .putString("vantix_text", text)
                        .putString("vantix_fingerprint", fingerprint)
                        .putLong("vantix_timestamp", System.currentTimeMillis())
                        .apply()
                    return@withContext text
                }
            } catch (e: Exception) {
                android.util.Log.w("VantixAI", "AI insight failed — using deterministic fallback", e)
            }
        }

        // Deterministic fallback — always something meaningful shown
        vantixDeterministicInsight(core).also { fallback ->
            vantixPrefs.edit()
                .putString("vantix_text", fallback)
                .putString("vantix_fingerprint", fingerprint)
                .putLong("vantix_timestamp", System.currentTimeMillis())
                .apply()
        }
    }

    private fun vantixDeterministicInsight(
        core: com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.AdaptiveCoreResult
    ): String = when (core.loadStatus) {
        com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.LoadStatus.OVERREACHING ->
            "Your recent effort (ATL ${"%.1f".format(core.atl)}) is running ${
                "%.0f".format((core.atlCtlRatio - 1.0) * 100)
            }% above your long-term base. Your body is accumulating more than it can absorb right now — ease off today and let adaptation compound."

        com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.LoadStatus.OPTIMAL ->
            "ATL and CTL are aligned at ${"%.1f".format(core.atl)} and ${"%.1f".format(core.ctl)} — this is the ideal build window. Keep this rhythm and you'll see the fitness gains compound over the next few weeks."

        com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.LoadStatus.UNDERLOADED ->
            "Activity has tapered off — your CTL base (${"%.1f".format(core.ctl)}) is starting to drift down. Even a moderate effort today would arrest the decline and keep your fitness base intact."

        com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.LoadStatus.DAILY_MOVER -> {
            val trendWord = when {
                core.stepTrend > 100  -> "building"
                core.stepTrend < -100 -> "declining"
                else                  -> "consistent"
            }
            "Your daily movement is $trendWord over the last 14 days with an average of ${
                "%,d".format(core.avgSteps14d.toLong())
            } steps. Consistency at ${(core.activityConsistency * 100).roundToInt()}% is your biggest lever — keep showing up daily."
        }

        com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.LoadStatus.INSUFFICIENT_DATA ->
            "VANTIX is still reading your patterns — keep moving daily and the load intelligence will activate as your history builds."
    }

    /**
     * Picks a chat system prompt matched to the active engine: a long, rich one for
     * cloud providers and a short, strictly-conversational one for the on-device model.
     */
    private fun buildChatSystemPrompt(
        forOnDevice: Boolean,
        context: android.content.Context,
        det: com.vanta.app.data.DeterministicPhysiologyResult,
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord?,
        history: List<com.vanta.app.data.db.DailyMetricRecord>,
        weatherLine: String?
    ): String = if (forOnDevice) {
        com.vanta.app.data.ai.OnDeviceChatPromptSystem.createSystemPrompt(context, det, telemetry, baseline, profile, history, weatherLine)
    } else {
        com.vanta.app.data.ai.CoachChatPromptSystem.createSystemPrompt(context, det, telemetry, baseline, profile, history, weatherLine)
    }

    /**
     * Dedicated Conversational AI Coach response generator.
     */
    suspend fun generateChatResponse(
        historyMessages: List<com.vanta.app.data.ai.ChatMessage>,
        userQuery: String,
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline,
        apiKey: String?,
        provider: AiProvider,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList(),
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): String = withContext(Dispatchers.IO) {
        val det = deterministicEngine.calculatePhysiology(telemetry, baseline)
        val onDeviceMgr = com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context)
        val isOnDeviceReady = onDeviceMgr.isModelDownloaded()
        val effectiveProvider = if (provider == AiProvider.ON_DEVICE_LITERT || (apiKey.isNullOrBlank() && isOnDeviceReady)) {
            AiProvider.ON_DEVICE_LITERT
        } else {
            provider
        }

        // Record user topic into continuous coach memory
        if (userQuery.length in 5..120) {
            com.vanta.app.data.ai.CoachMemoryStore.getInstance(context).recordChatTopic(userQuery)
        }

        val weatherLine = com.vanta.app.data.weather.WeatherService.currentWeatherLine(context)
        val systemPrompt = buildChatSystemPrompt(effectiveProvider == AiProvider.ON_DEVICE_LITERT, context, det, telemetry, baseline, profile, history, weatherLine)
        val fullConversation = com.vanta.app.data.ai.CoachChatPromptSystem.buildConversationPrompt(historyMessages, userQuery)

        if (effectiveProvider == AiProvider.ON_DEVICE_LITERT && isOnDeviceReady) {
            val output = onDeviceMgr.generate(systemPrompt, fullConversation, imageBase64)
            if (!output.isNullOrBlank()) return@withContext com.vanta.app.data.ai.CoachChatPromptSystem.sanitizeOutput(output)
        }

        try {
            val output = generateWithProvider(
                systemPrompt = systemPrompt,
                userPrompt = fullConversation,
                apiKey = apiKey ?: "",
                provider = effectiveProvider,
                connectTimeoutMs = 30_000,
                readTimeoutMs = 60_000,
                maxTokens = if (imageBase64 != null) 700 else 280,
                imageBase64 = imageBase64,
                imageMimeType = imageMimeType
            )?.trim()

            if (!output.isNullOrBlank()) {
                return@withContext com.vanta.app.data.ai.CoachChatPromptSystem.sanitizeOutput(output)
            }
        } catch (e: Exception) {
            android.util.Log.e("VantaAIChat", "Chat AI generation error: ${e.message}", e)
        }

        return@withContext "Unable to reach ${effectiveProvider.label} engine. Please check your connection or AI key in Settings."
    }

    /**
     * Real-time token streaming chat generation from LiteRT-LM GPU engine (or progressive cloud stream).
     */
    fun generateChatResponseStreaming(
        historyMessages: List<com.vanta.app.data.ai.ChatMessage>,
        userQuery: String,
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline,
        apiKey: String?,
        provider: AiProvider,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList(),
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): Flow<String> = flow {
        val det = deterministicEngine.calculatePhysiology(telemetry, baseline)
        val onDeviceMgr = com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(context)
        val isOnDeviceReady = onDeviceMgr.isModelDownloaded()
        val effectiveProvider = if (provider == AiProvider.ON_DEVICE_LITERT || (apiKey.isNullOrBlank() && isOnDeviceReady)) {
            AiProvider.ON_DEVICE_LITERT
        } else {
            provider
        }

        // Record user topic into continuous coach memory
        if (userQuery.length in 5..120) {
            com.vanta.app.data.ai.CoachMemoryStore.getInstance(context).recordChatTopic(userQuery)
        }

        val weatherLine = com.vanta.app.data.weather.WeatherService.currentWeatherLine(context)
        val systemPrompt = buildChatSystemPrompt(effectiveProvider == AiProvider.ON_DEVICE_LITERT, context, det, telemetry, baseline, profile, history, weatherLine)
        val fullConversation = com.vanta.app.data.ai.CoachChatPromptSystem.buildConversationPrompt(historyMessages, userQuery)


        if (effectiveProvider == AiProvider.ON_DEVICE_LITERT && isOnDeviceReady) {
            var prefixStripped = false
            var buffer = StringBuilder()
            onDeviceMgr.generateStreaming(systemPrompt, fullConversation, imageBase64).collect { chunk ->
                if (!prefixStripped) {
                    buffer.append(chunk)
                    val raw = buffer.toString()
                    val cleaned = com.vanta.app.data.ai.CoachChatPromptSystem.sanitizeOutput(raw)
                    if (cleaned.isNotEmpty() || raw.length > 25) {
                        prefixStripped = true
                        emit(cleaned)
                    }
                } else {
                    emit(chunk)
                }
            }
        } else {
            val fullText = generateChatResponse(
                historyMessages, userQuery, telemetry, baseline, apiKey, provider, profile, history, imageBase64, imageMimeType
            )
            val words = fullText.split(" ")
            for (i in words.indices) {
                emit(if (i == 0) words[i] else " " + words[i])
                delay(12)
            }
        }
    }
}
