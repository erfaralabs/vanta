package com.vanta.app

import com.vanta.app.data.AiProvider
import com.vanta.app.data.DeterministicPhysiologyResult
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.RecoveryCategory
import com.vanta.app.data.VantaGemmaEngine
import com.vanta.app.data.WatchWearMode
import com.vanta.app.data.ai.CoachChatPromptSystem
import com.vanta.app.data.ai.CoachPromptSystem
import com.vanta.app.data.ai.PhysiologyInsightPromptSystem
import com.vanta.app.data.ai.PhysiologyTemplateSelector
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.ui.screens.PhysiologyMetric
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * UNIFORM OUTPUT CHECK across every AI provider.
 *
 * Same deep-breakdown prompt + same post-processing pipeline (extractProseFromRaw →
 * cleanAndValidateAiResponse → sentence-completion guard → deterministic fallback)
 * for Gemini, DeepSeek, OpenRouter, and simulated on-device Gemma 4 E2B outputs.
 * Verifies the Strain / Recovery / Energy detail page renders the same clean
 * prose shape regardless of which provider the user selected.
 *
 * Keys are read from env (never hardcoded): GEMINI_TEST_KEY, DEEPSEEK_TEST_KEY,
 * OPENROUTER_TEST_KEY. Providers without a key are skipped; on-device is simulated
 * because the LiteRT-LM runtime only runs on Android.
 */
class AiLiveDemoTest {

    // Demo payload: a realistic mid-training-day snapshot.
    private val demoTelemetry = HealthConnectTelemetry(
        steps = 8420,
        calories = 520,
        avgBpm = 68,
        peakBpm = 142,
        restingBpm = 58,
        exerciseMinutes = 45,
        sleepMinutes = 460
    )

    private val demoDet = DeterministicPhysiologyResult(
        strain = 12.4,
        recovery = 74,
        recoveryCategory = RecoveryCategory.GOOD,
        energy = 68,
        wearMode = WatchWearMode.ALL_DAY_WEAR,
        hrMax = 182,
        rhrBaseline = 60,
        rhrToday = 58,
        avgHrBaseline = 74,
        avgHrToday = 68,
        isLearningPhase = false,
        savedDaysCount = 14,
        baselineSummaryMessage = "7-day baseline stable",
        breakdownExplanation = "Recovery holds at 74%"
    )

    private val demoBaseline = UserBaseline.Default.copy(
        avgStrain = 10.5,
        avgRecovery = 72.0,
        avgEnergy = 70.0,
        isLearningPhase = false,
        savedDaysCount = 14
    )

    private data class Provider(val name: String, val url: String, val model: String, val key: String)
    private data class Reply(val content: String, val finishReason: String)

    private fun availableProviders(): List<Provider> {
        val list = mutableListOf<Provider>()
        System.getenv("GEMINI_TEST_KEY")?.takeIf { it.isNotBlank() }?.let {
            list += Provider("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-flash-latest", it)
        }
        System.getenv("DEEPSEEK_TEST_KEY")?.takeIf { it.isNotBlank() }?.let {
            list += Provider("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat", it)
        }
        System.getenv("OPENROUTER_TEST_KEY")?.takeIf { it.isNotBlank() }?.let {
            list += Provider("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "qwen/qwen-2.5-7b-instruct", it)
        }
        return list
    }

    /** OpenAI-compatible POST — mirrors VantaGemmaEngine.postOnce (model, headers, body). */
    private fun callProvider(p: Provider, system: String, user: String): Reply {
        val models = mutableListOf(p.model)
        if (p.name == "Gemini") models += "gemini-flash-lite-latest"
        var lastError: Exception? = null
        for (model in models) {
            for (attempt in 1..2) {
                try {
                    return postModel(p, model, system, user)
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(if (attempt == 1) 3_000L else 8_000L)
                }
            }
        }
        throw (lastError ?: IllegalStateException("${p.name} unavailable"))
    }

    private fun postModel(p: Provider, model: String, system: String, user: String): Reply {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
            .put("max_tokens", 600)
            .put("temperature", 0.6)
            .toString()

        val conn = URL(p.url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer ${p.key}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("${p.name} ($model) HTTP $code: ${text.take(600)}")
            }
            val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            Reply(
                content = choice.getJSONObject("message").optString("content", ""),
                finishReason = choice.optString("finish_reason", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    /** The app's uniform post-processing (generateVantaCoachDeepInsight) — provider agnostic. */
    private fun uniformPipeline(raw: String): String {
        val extracted = PhysiologyInsightPromptSystem.extractProseFromRaw(raw)
        val validated = extracted?.let {
            PhysiologyTemplateSelector.cleanAndValidateAiResponse(
                raw = it,
                maxWords = 120,
                maxSentences = 5
            ) ?: it.take(600)
        }.orEmpty()
        val trimmed = validated.trimEnd()
        val complete = trimmed.isNotBlank() &&
            (trimmed.endsWith('.') || trimmed.endsWith('!') || trimmed.endsWith('?'))
        return if (complete) trimmed else ""
    }

    @Test
    fun `all providers produce uniform strain recovery energy output`() {
        val providers = availableProviders()
        // Skip cleanly when no keys are configured (run with GEMINI_TEST_KEY etc.).
        org.junit.Assume.assumeTrue(
            "Set at least one of GEMINI_TEST_KEY / DEEPSEEK_TEST_KEY / OPENROUTER_TEST_KEY",
            providers.isNotEmpty()
        )

        val metrics = listOf(PhysiologyMetric.RECOVERY, PhysiologyMetric.STRAIN, PhysiologyMetric.ENERGY)
        val out = StringBuilder()
        out.appendLine("VANTA UNIFORM OUTPUT CHECK")
        out.appendLine("Same prompt + same normalization pipeline for every provider.")
        out.appendLine("Providers with keys: ${providers.joinToString { it.name }}")
        out.appendLine()

        val realFinals = mutableListOf<String>()
        val metricFinals = mutableMapOf<PhysiologyMetric, MutableList<String>>()
        for (metric in metrics) {
            out.appendLine("===================== $metric =====================")
            for (p in providers) {
                val prompt = PhysiologyInsightPromptSystem.vantaCoachDeepPrompt(
                    targetMetric = metric,
                    telemetry = demoTelemetry,
                    det = demoDet,
                    baseline = demoBaseline,
                    history = emptyList(),
                    provider = if (p.name == "Gemini") AiProvider.GEMINI else if (p.name == "DeepSeek") AiProvider.DEEPSEEK else AiProvider.OPENROUTER
                )
                val reply = callProvider(p, prompt.system, prompt.user)
                // Mirror the app: finish_reason=length is rejected at the API layer.
                val finalText = if (reply.finishReason == "length") "" else uniformPipeline(reply.content)
                if (finalText.isNotBlank()) {
                    realFinals += finalText
                    metricFinals.getOrPut(metric) { mutableListOf() } += finalText
                }
                out.appendLine("--- ${p.name} (finish_reason=${reply.finishReason}) ---")
                out.appendLine("  RAW   : ${reply.content.trim().take(150)}")
                out.appendLine("  FINAL : ${finalText.ifBlank { "(TRUNCATED → deterministic fallback)" }}")
                out.appendLine()
            }

            // Simulated on-device Gemma 4 E2B behaviours run through the SAME pipeline.
            val simulated = listOf(
                "Your recovery sits at 74%, clearing your 72% baseline. Keep tonight low key to protect tomorrow.",
                """{"insight": "Your strain is at 12.4 today, outperforming your 10.5 baseline. Keep the evening light."}""",
                "```json\n{\"text\": \"Your energy sits at 68%, aligned with your baseline. Hydrate and stay consistent.\"}\n```",
                "Your recovery is at 74% and your baseline is at"
            )
            out.appendLine("--- On-Device Gemma 4 E2B (simulated) ---")
            for (sample in simulated) {
                val finalText = uniformPipeline(sample)
                out.appendLine("  RAW   : ${sample.take(150)}")
                out.appendLine("  FINAL : ${finalText.ifBlank { "(rejected → deterministic fallback)" }}")
            }
            out.appendLine()
        }

        // ── Homepage Vanta Coach overview (same provider-agnostic guarantees) ──
        out.appendLine("===================== HOME OVERVIEW (Vanta Coach) =====================")
        val homeFinals = mutableListOf<String>()
        for (p in providers) {
            val prompt = CoachPromptSystem.coachPrompt(
                telemetry = demoTelemetry,
                det = demoDet,
                baseline = demoBaseline,
                dataLimited = false,
                history = emptyList(),
                provider = if (p.name == "Gemini") AiProvider.GEMINI else if (p.name == "DeepSeek") AiProvider.DEEPSEEK else AiProvider.OPENROUTER
            )
            val reply = callProvider(p, prompt.system, prompt.user)
            // The app extracts the "overview" field then applies isCompleteProse.
            val extracted = PhysiologyInsightPromptSystem.extractProseFromRaw(reply.content)
            val ok = extracted != null && VantaGemmaEngine.isCompleteProse(extracted)
            if (ok) homeFinals += extracted!!
            out.appendLine("--- ${p.name} (finish_reason=${reply.finishReason}) ---")
            out.appendLine("  FINAL OVERVIEW : ${if (ok) extracted else "(rejected → deterministic fallback)"}")
            out.appendLine()
        }
        for (finalOverview in homeFinals) {
            assertFalse("home overview no JSON braces: $finalOverview", finalOverview.contains("{") || finalOverview.contains("}"))
            assertTrue("home overview must end with punctuation: $finalOverview", VantaGemmaEngine.isCompleteProse(finalOverview))
        }

        // ── Chat verification (conversation prompt + sanitizer, live) ──
        out.appendLine("===================== CHAT (Coach) =====================")
        for (p in providers) {
            val convo = CoachChatPromptSystem.buildConversationPrompt(
                historyMessages = listOf(
                    com.vanta.app.data.ai.ChatMessage(role = "user", content = "Should I train hard today?"),
                    com.vanta.app.data.ai.ChatMessage(role = "assistant", content = "Your recovery is at 74%, so a solid session is reasonable.")
                ),
                userQuery = "What about my strain target?"
            )
            val system = "You are Vanta Coach, a direct athletic performance coach. Answer concisely using the athlete's real numbers."
            val reply = callProvider(p, system, convo)
            val cleaned = CoachChatPromptSystem.sanitizeOutput(reply.content)
            out.appendLine("--- ${p.name} (finish_reason=${reply.finishReason}) ---")
            out.appendLine("  REPLY : ${cleaned.ifBlank { "(truncated/empty → error fallback)" }}")
            out.appendLine()
        }

        // Persist the report FIRST so a failed assertion never hides the evidence.
        File("/tmp/vanta_ai_demo.txt").writeText(out.toString())

        // Uniformity assertions on every REAL provider's AI text.
        assertTrue("at least one provider produced AI text", realFinals.isNotEmpty())
        for (text in realFinals) {
            assertFalse("no JSON braces: $text", text.contains("{") || text.contains("}"))
            assertTrue("must end with sentence punctuation: $text", text.endsWith('.') || text.endsWith('!') || text.endsWith('?'))
            val sentences = text.split(Regex("(?<=[.!?]) ")).count { it.isNotBlank() }
            assertTrue("sentence count 1..5, got $sentences: $text", sentences in 1..5)
        }
        // For each metric, ALL providers must agree on sentence count (uniform shape).
        for ((metric, texts) in metricFinals) {
            val counts = texts.map {
                it.split(Regex("(?<=[.!?]) ")).count { c -> c.isNotBlank() }
            }.distinct()
            assertTrue("$metric: all providers agree on sentence count ($counts)", counts.size == 1)
        }
        println("Uniform-output report → /tmp/vanta_ai_demo.txt")
    }
}

