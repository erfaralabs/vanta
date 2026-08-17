package com.vanta.app.data.ai

import com.vanta.app.data.AiProvider
import com.vanta.app.data.DeterministicPhysiologyResult
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.ui.screens.PhysiologyMetric
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dedicated prompt architecture for Physiological Insights & Daily Analysis in VANTA.
 *
 * Covers:
 *  1. Daily Dashboard Overview & Callouts
 *  2. Deep Metric Breakdowns (Recovery, Strain, Energy Details)
 *  3. Event Push Notifications
 *
 * Prompts are engineered to return clean prose without raw template artifacts.
 */
object PhysiologyInsightPromptSystem {

    val CLOUD_SYSTEM_PROMPT: String =
        """
        You are VANTA, an elite wearable human performance coach (WHOOP & Oura grade).
        You analyze physiological telemetry (Recovery %, Strain score, Energy %, Resting HR, Active Time, and baseline trends) with deep scientific precision and an authentic, supportive human voice.

        STRICT ANTI-HALLUCINATION & NUMERIC GROUNDING RULES:
        - NEVER invent biometrics not explicitly present in the data payload.
        - Every metric mentioned MUST match the exact numeric values in the payload.
        - Never give medical advice or clinical diagnoses.

        CONCISENESS RULES:
        - Deliver tight, punchy, high-impact athletic insights (no essays or walls of text).
        - Zero exclamation marks (!). No robotic prefixes.
        """.trimIndent()

    val ON_DEVICE_SYSTEM_PROMPT: String =
        """
        You are VANTA, a smart on-device athletic performance coach powered by Gemma.
        You speak directly to the athlete with confidence, physiological insight, and authentic coaching wisdom.

        STRICT RULES:
        - Speak directly to the athlete in the second person ("Your recovery sits at...").
        - Ground all statements in the real numbers provided.
        - Be direct, concise, and athletic. No markdown headers.
        """.trimIndent()

    /**
     * Deep Metric Breakdown prompt for PhysiologyDetailScreen (Recovery, Strain, Energy).
     * Output is pure prose (2-3 sentences, 40-65 words) ready for real-time streaming without JSON tags.
     */
    fun vantaCoachDeepPrompt(
        targetMetric: PhysiologyMetric,
        telemetry: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList(),
        provider: AiProvider = AiProvider.GEMINI
    ): CoachPromptSystem.AiPrompt {
        val isOnDevice = provider == AiProvider.ON_DEVICE_LITERT
        val strainFmt = "%.1f".format(det.strain)
        val targetBaselineText = when (targetMetric) {
            PhysiologyMetric.RECOVERY -> "${baseline.avgRecovery.roundToInt()}%"
            PhysiologyMetric.STRAIN -> "%.1f".format(baseline.avgStrain)
            PhysiologyMetric.ENERGY -> "${baseline.avgEnergy.roundToInt()}%"
        }
        val targetVal = when (targetMetric) {
            PhysiologyMetric.RECOVERY -> "${det.recovery}%"
            PhysiologyMetric.STRAIN -> "$strainFmt / 21.0"
            PhysiologyMetric.ENERGY -> "${det.energy}%"
        }

        val userPrompt = """
            Write a direct, 2 to 3 sentence athletic breakdown (STRICT LIMIT: 35 to 60 words total) explaining today's ${targetMetric.label}.
            - Current ${targetMetric.label}: $targetVal (7-Day Baseline: $targetBaselineText).
            - Real numbers: Recovery=${det.recovery}%, Strain=$strainFmt/21.0, Energy=${det.energy}%, Steps=${telemetry.steps}, Workout=${telemetry.exerciseMinutes} min.
            - Explain the physiological driver clearly and conclude with a specific actionable recovery or training directive.
            - DO NOT return JSON. DO NOT include prefixes like "Content:", "Sentence 1:", or "Vanta Coach:". Return ONLY the clean coaching sentences.
        """.trimIndent()

        return CoachPromptSystem.AiPrompt(
            feature = "deep_breakdown",
            system = if (isOnDevice) ON_DEVICE_SYSTEM_PROMPT else CLOUD_SYSTEM_PROMPT,
            user = userPrompt
        )
    }

    /**
     * Daily Dashboard Overview prompt.
     */
    fun dailyOverviewPrompt(
        telemetry: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        dataLimited: Boolean,
        history: List<DailyMetricRecord> = emptyList(),
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        provider: AiProvider = AiProvider.GEMINI
    ): CoachPromptSystem.AiPrompt {
        val isOnDevice = provider == AiProvider.ON_DEVICE_LITERT
        val strainFmt = "%.1f".format(det.strain)

        val userPrompt = """
            Daily Dashboard Overview Task:
            - Write a concise 2 to 3 sentence overview (STRICT LIMIT: 40 to 60 words total).
            - Telemetry: Recovery=${det.recovery}%, Strain=$strainFmt/21.0, Energy=${det.energy}%, Steps=${telemetry.steps}, Workout=${telemetry.exerciseMinutes} min.
            - Then generate 2 callout chips highlighting key metrics.
            
            Respond in this exact JSON format:
            {
              "overview": "<2-3 sentence overview, 40-60 words max>",
              "callouts": [
                {"text": "<observation 1>", "colorHex": "#39FF80"},
                {"text": "<observation 2>", "colorHex": "#00F2FE"}
              ]
            }
        """.trimIndent()

        return CoachPromptSystem.AiPrompt(
            feature = "daily_overview",
            system = if (isOnDevice) ON_DEVICE_SYSTEM_PROMPT else CLOUD_SYSTEM_PROMPT,
            user = userPrompt
        )
    }

    /**
     * Cleans raw AI response text, stripping any JSON wraps, markdown, or template artifacts.
     */
    fun sanitizeInsightText(raw: String): String {
        var clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        
        // If wrapped in JSON object, extract insight text field
        if (clean.startsWith("{") && clean.contains("}")) {
            try {
                val json = org.json.JSONObject(clean)
                clean = json.optString("insight").ifBlank {
                    json.optString("text").ifBlank {
                        json.optString("overview").ifBlank {
                            json.optString("message", clean)
                        }
                    }
                }
            } catch (e: Throwable) {
                // regex extraction fallback
                val match = Regex("\"(?:insight|text|overview|message)\"\\s*:\\s*\"([^\"]+)\"").find(clean)
                if (match != null) {
                    clean = match.groupValues[1]
                }
            }
        }

        return clean
            .replace(Regex("^(?:\\{\\s*\"insight\"\\s*:\\s*\"|\"insight\"\\s*:\\s*\")"), "")
            .replace(Regex("\"\\s*\\}?$"), "")
            .replace(Regex("^(?:Content|Insight|Sentence\\s*\\d+|Breakdown|Vanta\\s*Coach)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:Sentence\\s*\\d+\\s*\\([^)]*\\)?)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Content:\\s*Sentence\\s*\\d+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[*_#`\\\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
