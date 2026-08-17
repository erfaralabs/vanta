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

    /**
     * Shown on the detail pages when the AI engine can't be reached (offline,
     * no provider configured, or a failed call). Keeps the page honest instead
     * of pretending to coach with a canned breakdown.
     */
    const val OFFLINE_MESSAGE =
        "No internet connection — Vanta Coach needs to reach an AI engine for a live breakdown. Connect and try again."

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
     * Dedicated prose-only system prompt for the deep metric breakdown card
     * (Recovery / Strain / Energy detail page). This prompt is separate from the
     * shared prompts because the shared ones intentionally request JSON — the
     * deep breakdown must always be RAW PROSE so it renders cleanly on screen.
     */
    val DEEP_DIVE_SYSTEM_PROMPT: String =
        """
        You are VANTA, an elite wearable human performance coach (WHOOP & Oura grade).
        You explain Recovery, Strain, and Energy with deep scientific precision and an authentic, supportive human voice.

        STRICT RULES:
        - Ground every statement in the exact numeric values in the payload. Never invent biometrics.
        - Speak directly to the athlete in the second person ("Your recovery sits at...").
        - Keep the answer to 2-3 punchy sentences.
        - Never give medical advice or clinical diagnoses.

        FORMAT (CRITICAL):
        - RAW PROSE ONLY. Never output JSON.
        - Never use braces { }, never wrap sentences in quotation marks.
        - Never use markdown code fences, bullet lists, or headings.
        - Never start with labels such as "Insight:", "Content:", "Sentence 1:", "Breakdown:", or "Vanta Coach:".
        - Always complete the answer — never stop mid-sentence.
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
            - Real numbers: Recovery=${det.recovery}%, Strain=$strainFmt/21.0, Energy=${det.energy}%, Steps=${telemetry.steps}${if (telemetry.exerciseMinutes > 0) ", Workout=${telemetry.exerciseMinutes} min" else ""}.
            - Explain the physiological driver clearly and conclude with a specific actionable recovery or training directive.

            RAW PROSE ONLY — CRITICAL FORMATTING RULES:
            - Never output JSON. Never use braces { }, never wrap the answer in quotation marks, never use markdown code fences.
            - Never start with labels such as "Insight:", "Content:", "Sentence 1:", "Breakdown:", or "Vanta Coach:".
            - Return ONLY the clean coaching sentences, and always complete the final sentence.
        """.trimIndent()

        return CoachPromptSystem.AiPrompt(
            feature = "deep_breakdown",
            system = DEEP_DIVE_SYSTEM_PROMPT,
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
            - Telemetry: Recovery=${det.recovery}%, Strain=$strainFmt/21.0, Energy=${det.energy}%, Steps=${telemetry.steps}${if (telemetry.exerciseMinutes > 0) ", Workout=${telemetry.exerciseMinutes} min" else ""}.
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

    /**
     * Robustly extracts clean prose from ANY raw model output.
     *
     * Handles every real-world failure mode seen from cloud & on-device models:
     * - Plain prose
     * - Prose wrapped in a markdown code fence (```json ... ```)
     * - A JSON object with any of the known text keys (insight/text/overview/summary/
     *   message/description/content), complete OR truncated mid-value
     * - Prose prefixed by the model's own commentary ("Here is your insight:")
     * - Stray JSON braces / quoted keys left over from a failed parse
     *
     * Returns null when nothing usable can be salvaged (caller falls back to the
     * deterministic template so the screen never shows raw JSON or junk).
     */
    fun extractProseFromRaw(raw: String): String? {
        if (raw.isBlank()) return null
        var clean = raw.trim()

        // 1. Strip markdown code fences wherever they appear
        clean = clean.replace(Regex("```[a-zA-Z]*"), "").replace("```", "").trim()

        // 2. If the output looks like JSON, pull out the actual text value first
        val jsonKeyRegex = "\"(?:insight|text|overview|summary|message|description|content)\""
        if (clean.startsWith("{") || Regex(jsonKeyRegex).containsMatchIn(clean)) {
            val parsed = runCatching {
                val json = org.json.JSONObject(clean)
                json.optString("insight").ifBlank {
                    json.optString("text").ifBlank {
                        json.optString("overview").ifBlank {
                            json.optString("summary").ifBlank {
                                json.optString("message").ifBlank {
                                    json.optString("description").ifBlank {
                                        json.optString("content", "")
                                    }
                                }
                            }
                        }
                    }
                }
            }.getOrNull()

            if (!parsed.isNullOrBlank()) {
                clean = parsed
            } else {
                // Truncated / malformed JSON → extract the first text value via regex
                val valueMatch = Regex(
                    "$jsonKeyRegex\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
                ).find(clean)
                if (valueMatch != null) {
                    clean = valueMatch.groupValues[1].replace("\\\"", "\"")
                } else {
                    // No recognizable value → strip JSON braces and quoted keys
                    clean = clean
                        .replace(Regex("\"[a-zA-Z_]+\"\\s*:"), "")
                        .replace(Regex("[{}\"]"), "")
                }
            }
        }

        // 3. Remove leftover labels, stray markdown, and normalize whitespace
        clean = clean
            .replace(Regex("^(?:json)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex("^(?:content|insight|breakdown|summary|analysis|refinement|vanta\\s*coach|coach|answer|response|here\\s*is\\s*(?:your|the)?\\s*(?:insight|breakdown|analysis|answer|response)?)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(Regex("^(?:sentence\\s*\\d+\\s*\\([^)]*\\)?)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[*_#`\\\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.length < 15) return null
        if (clean.startsWith("{") || clean.endsWith("}")) return null
        return clean
    }
}
