package com.vanta.app.data.ai

import android.content.Context
import com.vanta.app.data.DeterministicPhysiologyResult
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.UserProfileRecord
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Dedicated prompt architecture for interactive 1-on-1 AI Chat with Vanta Coach.
 *
 * Designed for natural, human-like coaching:
 * - Never robotic or repetitive (no "Vanta Coach:" prefixes, no repeated names)
 * - Grounded in live biometrics, active telemetry, and rolling 14-day VANTIX metrics
 * - Concise, authentic athletic voice without artificial emoji dumping
 */
object CoachChatPromptSystem {

    fun createSystemPrompt(
        context: Context,
        det: DeterministicPhysiologyResult,
        telemetry: HealthConnectTelemetry,
        baseline: UserBaseline,
        profile: UserProfileRecord?,
        history: List<DailyMetricRecord>
    ): String {
        val athleteName = profile?.name?.trim()?.takeIf { it.isNotBlank() }?.split(" ")?.firstOrNull() ?: "Athlete"
        val memorySummary = CoachMemoryStore.getInstance(context).getAthleteMemorySummary()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val timeString = now.format(DateTimeFormatter.ofPattern("h:mm a"))
        val dayOfWeekString = now.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val currentHour = now.hour

        val timeOfDay = when (currentHour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }

        val workoutStatus: String? = if (telemetry.exerciseMinutes > 0) {
            "${telemetry.exerciseMinutes} min logged"
        } else {
            null // never call out a zero-workout day — just omit the line
        }
        val rhrStatus = if (telemetry.restingBpm > 0) {
            "${telemetry.restingBpm} bpm (Baseline: ${baseline.avgRestingBpm.toInt()} bpm)"
        } else {
            "Calibrating (Baseline: ${baseline.avgRestingBpm.toInt()} bpm)"
        }

        val core = baseline.adaptiveCore
        val coreInfo = if (core != null) {
            "ATL: ${"%.1f".format(core.atl)}, CTL: ${"%.1f".format(core.ctl)}, TSB: ${"%.1f".format(core.tsb)} (${core.loadStatus.label})"
        } else {
            "ATL/CTL: Calibrating toward 14-day Adaptive Core"
        }

        val bioAge = baseline.biologicalAge
        val vantixInfo = if (bioAge != null) {
            val deltaStr = if (bioAge.deltaYears < 0) {
                "${"%.1f".format(-bioAge.deltaYears)} years YOUNGER than chronological age (Excellent / Optimal Longevity & Biological Health)"
            } else if (bioAge.deltaYears > 0) {
                "${"%.1f".format(bioAge.deltaYears)} years older than chronological age (Elevated strain / recovery deficit)"
            } else {
                "Matches chronological age exactly (Balanced Baseline)"
            }
            val factorBreakdown = bioAge.factors.joinToString("; ") { "${it.title}: ${if (it.deltaYears < 0) "-${"%.1f".format(-it.deltaYears)}" else "+${"%.1f".format(it.deltaYears)}"} yrs (${it.description})" }
            """
            - VANTIX Biological Age: ${"%.1f".format(bioAge.biologicalAge)} years (Chronological: ${bioAge.chronologicalAge} yrs)
            - Longevity Status: $deltaStr
            - Calibration Confidence: ${bioAge.confidenceLabel}
            - 6-Factor Physiological Drivers: $factorBreakdown
            """.trimIndent()
        } else if (baseline.savedDaysCount < 14) {
            "- VANTIX Biological Age: Calibrating (Day ${baseline.savedDaysCount} of 14 needed). Explain to athlete that VANTIX requires 14 days of continuous autonomic telemetry to calculate an accurate, clinical-grade biological age score."
        } else {
            "- VANTIX Biological Age: Calibrating baseline with active Health Connect data."
        }

        return """
        You are Vanta Coach, the personal AI fitness, recovery and wellness coach inside the VANTA app.

        ESTABLISHED COACH-ATHLETE RELATIONSHIP:
        - You and $athleteName have an ongoing, continuous coaching relationship (${baseline.savedDaysCount} days active).
        - NEVER greet them as a stranger or introduce yourself from scratch (never say "Hi, I'm Vanta Coach" or "How can I help you today?").
        - NATURAL NAME USAGE: Do NOT repetitively say their name ($athleteName) in every single message. Only use their name sparingly when genuinely impactful or encouraging, not as a robotic greeting prefix.
        - Continuous Athlete Memory: $memorySummary

        ATHLETE BIOMETRICS & BASELINE PROFILE:
        - Name: $athleteName
        - Age: ${profile?.calculatedAge ?: 27} years
        - Sex / Physiology: ${profile?.sex ?: "Athlete"}
        - Height / Weight: ${profile?.heightCm?.toInt() ?: 178} cm / ${profile?.weightKg?.toInt() ?: 75} kg
        - Primary Fitness Goal: ${profile?.fitnessGoal ?: "General Fitness, Energy & Longevity"}
        - Daily Target Steps: ${profile?.stepsGoal ?: 10000} steps
        - Tracking History: ${baseline.savedDaysCount} recorded days on Vanta

        TODAY'S LIVE PHYSIOLOGICAL TELEMETRY:
        - Current Time: $timeString ($dayOfWeekString, $timeOfDay)
        - Recovery: ${det.recovery}% (${det.recoveryCategory.label})
        - Daily Strain: ${"%.1f".format(det.strain)} / 21.0 (7-Day Avg: ${"%.1f".format(baseline.avgStrain)})
        - Energy: ${det.energy}%
        - Steps: ${telemetry.steps} (7-Day Avg: ${baseline.avgSteps.toLong()})
        - Active Calories: ${telemetry.calories} kcal
        ${if (workoutStatus != null) "- Workout Activity: $workoutStatus" else ""}
        - Resting HR: $rhrStatus
        - Current HR: ${if (telemetry.currentBpm > 0) "${telemetry.currentBpm} bpm" else "Resting"}
        - Training Load: $coreInfo

        VANTIX BIOLOGICAL AGE & LONGEVITY STATUS:
        $vantixInfo
        - Recent History: ${if (history.isEmpty()) "Baseline calibrating (${baseline.savedDaysCount} days tracked)" else history.take(5).joinToString("; ") { "${it.date}: Rec=${it.recovery}%, Strain=${"%.1f".format(it.strain)}" }}

        ROLE & GUIDELINES:
        - You are the coach speaking directly to the athlete.
        - NO PREFIXES: NEVER write "Vanta Coach:", "Coach:", or "Assistant:" at the start of your message. Begin directly with your guidance.
        - Tone & Delivery: Speak naturally like a premier human athletic director in a 1-on-1 session. Be direct, clear, and authentic.
        - NO EMOJI SPAM: Do NOT tack on random emojis (like 🛡️💪, 🎯📈, 😴💪) at the end of every sentence or message. Only use a single emoji if it genuinely fits the context.
        - Use their real metrics to justify your recommendations.
        - VANTIX Biological Age Questions: If the athlete asks about their VANTIX score, biological age, longevity, or if their score is good or bad, explain it independently with deep context based on their 6 physiological drivers (resting HR, step volume, fitness capacity, recovery quality, and consistency).
        - Food/workout images: estimate macros, portion sizes, or evaluate form concisely.
        """.trimIndent()
    }

    fun buildConversationPrompt(
        historyMessages: List<ChatMessage>,
        userQuery: String
    ): String = buildString {
        historyMessages.takeLast(8).forEach { msg ->
            val speaker = if (msg.role == "user") "Athlete" else "Coach"
            append("$speaker: ${msg.content}\n\n")
        }
        append("Athlete: $userQuery\nCoach:")
    }

    fun sanitizeOutput(raw: String): String {
        var clean = raw
            .replace(Regex("^(?:Vanta\\s*Coach|Coach|Assistant)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:Analyzing\\s+your\\s+recovery|Analyzing\\s+your\\s+metrics)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        // Strip markdown code fences wherever they appear (```json / ``` / ~~~).
        clean = clean
            .replace(Regex("```[a-zA-Z]*"), "")
            .replace("```", "")
            .trim()

        // If the whole reply is wrapped in a JSON object, pull the natural-language field out.
        if (clean.startsWith("{") && clean.contains("}")) {
            val extracted = runCatching {
                val json = org.json.JSONObject(clean)
                json.optString("response").ifBlank {
                    json.optString("message").ifBlank {
                        json.optString("text").ifBlank {
                            json.optString("content").ifBlank {
                                json.optString("reply", "")
                            }
                        }
                    }
                }
            }.getOrNull()
            if (!extracted.isNullOrBlank()) clean = extracted.trim()
        }

        return clean.trim()
    }
}
