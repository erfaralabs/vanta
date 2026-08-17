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
 * VANTA's single source of truth for every AI prompt.
 *
 * Provides tailored prompt architectures:
 *  1. Universal Cloud AI (Gemini, DeepSeek, OpenRouter) -> Rich, comprehensive, elite human athletic coach breakdown.
 *  2. On-Device LiteRT -> Strict, lightweight, token-constrained, fast edge generation.
 */
object CoachPromptSystem {

    /**
     * Universal Cloud AI system prompt (Gemini / DeepSeek / OpenRouter).
     * High reasoning capability, deep physiological understanding, concise human athletic coach voice.
     */
    val CLOUD_SYSTEM_PROMPT: String =
        """
        You are VANTA, an elite wearable human performance coach trusted by serious athletes and high performers (WHOOP & Oura grade).
        You analyze real physiological telemetry (Recovery score, Strain score, Energy levels, Resting Heart Rate, Active Time, and 7-day baseline trends) with deep scientific precision and an authentic, supportive human voice.

        STRICT ANTI-HALLUCINATION & NUMERIC GROUNDING RULES:
        - NEVER invent, hallucinate, or extrapolate biometrics (HR, HRV, steps, strain, recovery, workouts) not explicitly present in the data payload.
        - Every metric mentioned MUST match the exact numeric values or baseline deltas in the payload (e.g., if strain is 2.3, say 2.3; if recovery is 86%, say 86%).
        - Never contradict mathematical reality: if a metric is below baseline by 3+ points, do not call it "consistent" or "aligned".
        - Never give medical advice, clinical diagnoses, promises, or guarantees.

        UNIVERSAL WORD LIMIT & CONCISENESS RULES:
        - ABSOLUTELY NO ESSAYS OR RUN-ON RAMBLING: Deliver tight, punchy, high-impact coaching. Never write walls of text.
        - Strictly obey the word and sentence caps specified in each task.
        - Every word must deliver actionable athletic insight.

        VOICE, TONE & VOCABULARY:
        - Calm, warm, direct, confident. Speak like a premier human athletic coach reviewing data in a 1-on-1 debrief.
        - ABSOLUTELY NO CLINICAL/TEXTBOOK JARGON: never say "physiological status", "systemic fatigue", "maintain status", "hemodynamic", "parasympathetic", or textbook phrasing.
        - Speak directly to the athlete ("You're at 86% recovery...", "Resting HR is down 2 bpm below your baseline...", "Solid foundation to push intensity today.").
        - Pay strict attention to current time. If it is evening or night (after 17:00), focus on evening recovery, sleep architecture, and unwinding.
        - No cheerleading, no exclamation marks (!), no clichés, no corporate filler.
        - Follow the exact JSON output format requested. Respond with ONLY that JSON — no preamble, no markdown fences.
        """.trimIndent()

    /**
     * On-Device LiteRT system prompt (Edge / Gemma 4B / Gemma 2B mobile runtime).
     * Smart, natural, athletic human coach persona tailored for efficient local execution.
     */
    val ON_DEVICE_SYSTEM_PROMPT: String =
        """
        You are VANTA, a smart on-device athletic performance coach powered by Gemma.
        You speak directly to the athlete with confidence, physiological insight, and authentic coaching wisdom (WHOOP & Oura style).

        COACHING PRINCIPLES & CONCISENESS:
        - Ground all statements in the athlete's real numbers (Recovery, Strain, Energy, Resting HR, Workouts, and 7-day baselines).
        - STRICT LENGTH LIMIT: Keep output concise and punchy (obey the exact word limits). Never write long essays or rambling paragraphs.
        - Connect recovery readiness to daily training capacity. If recovery is high, encourage progressive overload; if recovery is low, prescribe tactical rest and restoration.
        - Respect the local time of day: morning focus on daily goals; evening/night focus on recovery and sleep.
        - Tone: warm, direct, observant, second person ("you" / "your"). No clinical textbook jargon. No exclamation marks (!).
        - Respond ONLY with the requested JSON schema. No markdown fences, no conversational preamble.
        """.trimIndent()

    val SYSTEM_PROMPT: String get() = CLOUD_SYSTEM_PROMPT

    /** A fully separated prompt: system (persona) + user (task) — payload lives in the user prompt. */
    data class AiPrompt(
        val feature: String,
        val system: String,
        val user: String
    )

    /** Builds the AI Coach's daily dashboard prompt (feature task + today's data payload). */
    fun coachPrompt(
        telemetry: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        dataLimited: Boolean,
        history: List<DailyMetricRecord> = emptyList(),
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        provider: AiProvider = AiProvider.GEMINI
    ): AiPrompt {
        val isOnDevice = provider == AiProvider.ON_DEVICE_LITERT
        return AiPrompt(
            feature = if (isOnDevice) "coach_ondevice" else "coach_cloud",
            system = if (isOnDevice) ON_DEVICE_SYSTEM_PROMPT else CLOUD_SYSTEM_PROMPT,
            user = if (isOnDevice) {
                buildOnDeviceCoachUserPrompt(telemetry, det, baseline, dataLimited, history, profile)
            } else {
                buildCloudCoachUserPrompt(telemetry, det, baseline, dataLimited, history, profile)
            }
        )
    }

    /** Builds an inline per-metric AI prompt for Recovery, Strain, or Energy. */
    fun detailPrompt(
        targetMetric: PhysiologyMetric,
        telemetry: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList(),
        provider: AiProvider = AiProvider.GEMINI
    ): AiPrompt {
        val isOnDevice = provider == AiProvider.ON_DEVICE_LITERT
        return AiPrompt(
            feature = "detail_inline_${targetMetric.name.lowercase()}",
            system = if (isOnDevice) ON_DEVICE_SYSTEM_PROMPT else CLOUD_SYSTEM_PROMPT,
            user = if (isOnDevice) {
                buildOnDeviceDetailInlineUserPrompt(targetMetric, telemetry, det, baseline, profile, history)
            } else {
                buildCloudDetailInlineUserPrompt(targetMetric, telemetry, det, baseline, profile, history)
            }
        )
    }

    /** Builds an expanded Vanta Coach deep-dive AI prompt. */
    fun vantaCoachDeepPrompt(
        targetMetric: PhysiologyMetric,
        telemetry: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList(),
        provider: AiProvider = AiProvider.GEMINI
    ): AiPrompt {
        val isOnDevice = provider == AiProvider.ON_DEVICE_LITERT
        return AiPrompt(
            feature = "vanta_coach_${targetMetric.name.lowercase()}",
            system = if (isOnDevice) ON_DEVICE_SYSTEM_PROMPT else CLOUD_SYSTEM_PROMPT,
            user = if (isOnDevice) {
                buildOnDeviceVantaCoachDeepUserPrompt(targetMetric, telemetry, det, baseline, profile, history)
            } else {
                buildCloudVantaCoachDeepUserPrompt(targetMetric, telemetry, det, baseline, profile, history)
            }
        )
    }

    /** Builds an AI Coach notification prompt. */
    fun notificationPrompt(
        data: NotificationPromptData,
        history: List<DailyMetricRecord> = emptyList(),
        provider: AiProvider = AiProvider.GEMINI
    ): AiPrompt {
        val isOnDevice = provider == AiProvider.ON_DEVICE_LITERT
        return AiPrompt(
            feature = "notification",
            system = if (isOnDevice) ON_DEVICE_SYSTEM_PROMPT else CLOUD_SYSTEM_PROMPT,
            user = buildNotificationUserPrompt(data, history)
        )
    }

    /** Data payload for a notification event. */
    data class NotificationPromptData(
        val triggerLabel: String,
        val recovery: Int,
        val energy: Int,
        val strain: Double,
        val steps: Long,
        val workoutMinutes: Int,
        val weeklyAvgStrain: Double,
        val streak: Int = 0,
        val weekWorkouts: Int = 0,
        val profileName: String = "",
        val heartRateAllowed: Boolean = true
    )

    // ── Universal Cloud Prompts ─────────────────────────────────────────────────

    private fun buildCloudCoachUserPrompt(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        dataLimited: Boolean,
        history: List<DailyMetricRecord> = emptyList(),
        profile: com.vanta.app.data.db.UserProfileRecord? = null
    ): String {
        val strainFmt = "%.1f".format(det.strain)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val min = Calendar.getInstance().get(Calendar.MINUTE)
        val timeOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
        val timeFmt = String.format(Locale.US, "%02d:%02d (%s)", hour, min, timeOfDay)

        val limited = if (dataLimited) {
            "Today's telemetry is currently light. Acknowledge this calmly in your overview without assuming it is morning if the time is evening."
        } else ""

        return """
            Universal Cloud Coach Task:
            - Write a concise, high-impact 2 to 3 sentence physiological overview (STRICT LIMIT: 40 to 60 words total). Never write long essays or rambling explanations.
            - Then generate exactly 2 high-impact callout chips highlighting key metrics with fitting colors.
            $limited

            ATHLETIC COACHING GUARDRAILS:
            - Speak directly to the athlete like a premier human athletic director.
            - Ground all claims in the exact numbers below; never hallucinate or invent stats.
            - Synthesize the interplay between Recovery (${det.recovery}%), Strain ($strainFmt / 21.0), and Energy (${det.energy}%).
            - Pay strict attention to current time ($timeFmt). If evening/night (after 17:00), focus on recovery pacing and evening wind-down.
            ${if (t.exerciseMinutes > 0) "- If a workout was completed (${t.exerciseMinutes} min), factor that in and advise recovery/nutrition." else "- Never claim a workout happened if none is logged."}
            - Zero exclamation marks (!). No walls of text.

            Today's Telemetry & Baselines:
            - Current local time: $timeFmt
            - Recovery: ${det.recovery}% (7-day baseline: ${baseline.avgRecovery.roundToInt()}%)
            - Energy: ${det.energy}% (7-day baseline: ${baseline.avgEnergy.roundToInt()}%)
            - Strain accumulated: $strainFmt / 21.0 (7-day baseline target: ${"%.1f".format(baseline.avgStrain)})
            - Steps: ${t.steps}
            ${if (t.exerciseMinutes > 0) "- Workout minutes: ${t.exerciseMinutes}" else ""}
            - Active HR: ${if (t.avgBpm > 0) "${t.avgBpm} bpm" else "not registered yet"}
            - Resting HR: ${if (det.rhrToday > 0) "${det.rhrToday} bpm" else "not registered yet"}
            - Weekly average strain: ${"%.1f".format(baseline.avgStrain)}
            ${profileSection(profile)}
            ${historySection(history)}

            Callout Chip Requirements:
            - Each callout focuses on ONE specific metric from today's data (8-14 words max).
            - Include the actual number it references.
            - colorHex must be: "#39FF80" (optimal/green), "#00F2FE" (neutral/blue), "#FFB000" (caution/amber), or "#FF5252" (strain/red).

            Respond with ONLY this JSON format:
            {
              "overview": "<concise 2-3 sentence coaching analysis, 40-60 words max>",
              "callouts": [
                {"text": "<specific metric observation, 8-14 words>", "colorHex": "#39FF80"},
                {"text": "<specific next action or metric delta, 8-14 words>", "colorHex": "#00F2FE"}
              ]
            }
        """.trimIndent()
    }

    private fun buildCloudDetailInlineUserPrompt(
        targetMetric: PhysiologyMetric,
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList()
    ): String {
        val strainFmt = "%.1f".format(det.strain)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val min = Calendar.getInstance().get(Calendar.MINUTE)
        val timeOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
        val timeFmt = String.format(Locale.US, "%02d:%02d (%s)", hour, min, timeOfDay)

        val targetBaselineText = when (targetMetric) {
            PhysiologyMetric.RECOVERY -> "${baseline.avgRecovery.roundToInt()}%"
            PhysiologyMetric.STRAIN -> "%.1f".format(baseline.avgStrain)
            PhysiologyMetric.ENERGY -> "${baseline.avgEnergy.roundToInt()}%"
        }

        return """
            Universal Cloud Detail Insight Task for ${targetMetric.label}:
            - Write a sharp, verdict-first 1 to 2 sentence insight (STRICT LIMIT: 25 to 45 words total) for the ${targetMetric.label} detail page.
            - State the physiological driver behind the number and the tactical athletic recommendation.
            - Ground in the real metrics below. Zero exclamation marks (!). No rambling essays.

            Metrics:
            - Time: $timeFmt
            - Recovery: ${det.recovery}% (baseline ${baseline.avgRecovery.roundToInt()}%)
            - Strain: $strainFmt / 21.0 (baseline target ${"%.1f".format(baseline.avgStrain)})
            - Energy: ${det.energy}% (baseline ${baseline.avgEnergy.roundToInt()}%)
            - Focus Metric: ${targetMetric.label} (Value: ${when (targetMetric) { PhysiologyMetric.RECOVERY -> "${det.recovery}%"; PhysiologyMetric.STRAIN -> strainFmt; PhysiologyMetric.ENERGY -> "${det.energy}%" }}, Baseline: $targetBaselineText)
            - Steps: ${t.steps}${if (t.exerciseMinutes > 0) " | Workout: ${t.exerciseMinutes} min" else ""}
            ${profileSection(profile)}
            ${historySection(history)}

            Respond with ONLY this JSON:
            {"insight": "<1-2 sentence sharp verdict insight, 25-45 words max>"}
        """.trimIndent()
    }

    private fun buildCloudVantaCoachDeepUserPrompt(
        targetMetric: PhysiologyMetric,
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList()
    ): String {
        val strainFmt = "%.1f".format(det.strain)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val min = Calendar.getInstance().get(Calendar.MINUTE)
        val timeOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
        val timeFmt = String.format(Locale.US, "%02d:%02d (%s)", hour, min, timeOfDay)

        val targetBaselineText = when (targetMetric) {
            PhysiologyMetric.RECOVERY -> "${baseline.avgRecovery.roundToInt()}%"
            PhysiologyMetric.STRAIN -> "%.1f".format(baseline.avgStrain)
            PhysiologyMetric.ENERGY -> "${baseline.avgEnergy.roundToInt()}%"
        }

        return """
            Universal Cloud Vanta Coach Deep-Dive Task for ${targetMetric.label}:
            - Write a substantive 2 to 3 sentence physiological breakdown (STRICT LIMIT: 50 to 75 words total) explaining the factors behind today's ${targetMetric.label} score.
            - Compare today's numbers against 7-day rolling baselines, yesterday's load, and acute recovery trends.
            - Conclude with an actionable, concrete training/recovery directive for the rest of today.
            - STRICT PROHIBITION ON ESSAYS: Never exceed 75 words. Zero exclamation points (!).

            Complete Telemetry Payload:
            - Current local time: $timeFmt
            - Recovery: ${det.recovery}% (baseline: ${baseline.avgRecovery.roundToInt()}%)
            - Strain: $strainFmt / 21.0 (baseline: ${"%.1f".format(baseline.avgStrain)})
            - Energy: ${det.energy}% (baseline: ${baseline.avgEnergy.roundToInt()}%)
            - Target Focus Metric: ${targetMetric.label} (Current: ${when (targetMetric) { PhysiologyMetric.RECOVERY -> "${det.recovery}%"; PhysiologyMetric.STRAIN -> strainFmt; PhysiologyMetric.ENERGY -> "${det.energy}%" }}, Baseline: $targetBaselineText)
            - Steps: ${t.steps}
            ${if (t.exerciseMinutes > 0) "- Workout: ${t.exerciseMinutes} min" else ""}
            - Resting HR: ${if (det.rhrToday > 0) "${det.rhrToday} bpm" else "not registered yet"}
            ${profileSection(profile)}
            ${historySection(history)}

            Respond with ONLY this JSON format:
            {"insight": "<2-3 sentence punchy athletic breakdown, 50-75 words max>"}
        """.trimIndent()
    }

    // ── On-Device LiteRT (Gemma 4B / 2B) Prompts ───────────────────────────────

    private fun buildOnDeviceCoachUserPrompt(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        dataLimited: Boolean,
        history: List<DailyMetricRecord> = emptyList(),
        profile: com.vanta.app.data.db.UserProfileRecord? = null
    ): String {
        val strainFmt = "%.1f".format(det.strain)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val min = Calendar.getInstance().get(Calendar.MINUTE)
        val timeOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night"
        }
        val timeFmt = String.format(Locale.US, "%02d:%02d (%s)", hour, min, timeOfDay)

        return """
            On-Device Coaching Task:
            - Write a natural, impactful 2 sentence athletic coaching debrief (STRICT LIMIT: 30 to 50 words total) reviewing today's readiness and advising on next steps.
            - Write 2 specific callout chips highlighting key metrics with fitting colors.

            Athlete's Telemetry & Baselines:
            - Local time: $timeFmt
            - Recovery: ${det.recovery}% (7-day baseline: ${baseline.avgRecovery.roundToInt()}%)
            - Strain accumulated: $strainFmt / 21.0 (7-day baseline target: ${"%.1f".format(baseline.avgStrain)})
            - Energy: ${det.energy}% (7-day baseline: ${baseline.avgEnergy.roundToInt()}%)
            - Steps: ${t.steps}
            ${if (t.exerciseMinutes > 0) "- Workout: ${t.exerciseMinutes} min" else ""}
            - Resting HR: ${if (det.rhrToday > 0) "${det.rhrToday} bpm" else "not registered yet"}
            ${profileSection(profile)}
            ${historySection(history)}

            Respond with ONLY this JSON format:
            {
              "overview": "<2 sentence coaching debrief, 30-50 words max>",
              "callouts": [
                {"text": "<specific metric observation, 8-12 words>", "colorHex": "#39FF80"},
                {"text": "<tactical training/recovery directive, 8-12 words>", "colorHex": "#00F2FE"}
              ]
            }
        """.trimIndent()
    }

    private fun buildOnDeviceDetailInlineUserPrompt(
        targetMetric: PhysiologyMetric,
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList()
    ): String {
        val strainFmt = "%.1f".format(det.strain)
        val targetBaselineText = when (targetMetric) {
            PhysiologyMetric.RECOVERY -> "${baseline.avgRecovery.roundToInt()}%"
            PhysiologyMetric.STRAIN -> "%.1f".format(baseline.avgStrain)
            PhysiologyMetric.ENERGY -> "${baseline.avgEnergy.roundToInt()}%"
        }
        return """
            On-Device Insight Task for ${targetMetric.label}:
            - Write a direct 1 to 2 sentence athletic insight (STRICT LIMIT: 20 to 35 words total) explaining ${targetMetric.label}.
            - Current: ${when (targetMetric) { PhysiologyMetric.RECOVERY -> "${det.recovery}%"; PhysiologyMetric.STRAIN -> strainFmt; PhysiologyMetric.ENERGY -> "${det.energy}%" }} (Baseline: $targetBaselineText).
            - State the physiological driver and a concrete training/recovery action. No essays.
            
            Respond with ONLY JSON:
            {"insight": "<1-2 sentence direct athletic verdict, 20-35 words max>"}
        """.trimIndent()
    }

    private fun buildOnDeviceVantaCoachDeepUserPrompt(
        targetMetric: PhysiologyMetric,
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline,
        profile: com.vanta.app.data.db.UserProfileRecord? = null,
        history: List<DailyMetricRecord> = emptyList()
    ): String {
        val strainFmt = "%.1f".format(det.strain)
        val targetBaselineText = when (targetMetric) {
            PhysiologyMetric.RECOVERY -> "${baseline.avgRecovery.roundToInt()}%"
            PhysiologyMetric.STRAIN -> "%.1f".format(baseline.avgStrain)
            PhysiologyMetric.ENERGY -> "${baseline.avgEnergy.roundToInt()}%"
        }
        return """
            On-Device Deep Breakdown for ${targetMetric.label}:
            - Write a 2 to 3 sentence coach breakdown (STRICT LIMIT: 40 to 65 words total) explaining the factors behind today's ${targetMetric.label}.
            - Score: ${when (targetMetric) { PhysiologyMetric.RECOVERY -> "${det.recovery}%"; PhysiologyMetric.STRAIN -> strainFmt; PhysiologyMetric.ENERGY -> "${det.energy}%" }} vs baseline $targetBaselineText.
            - Provide clear athletic reasoning and close with a directive for the day. No essays.
            
            Respond with ONLY JSON:
            {"insight": "<2-3 sentence rich athletic breakdown, 40-65 words max>"}
        """.trimIndent()
    }

    // ── Notifications & Common Builders ─────────────────────────────────────────

    private fun buildNotificationUserPrompt(
        d: NotificationPromptData,
        history: List<DailyMetricRecord>
    ): String {
        val hrRule = if (d.heartRateAllowed) "" else "- NEVER mention heart rate, resting HR, or HRV.\n"
        val nameLine = if (d.profileName.isNotBlank())
            "\nYou're writing TO ${d.profileName.uppercase()} — a serious athlete. Use their name naturally ONLY when it feels right (never force it)."
        else ""
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val min = Calendar.getInstance().get(Calendar.MINUTE)
        val timeFmt = String.format(Locale.US, "%02d:%02d", hour, min)

        return """
            Notification task — send ONE push notification to a serious athlete.
            Trigger: ${d.triggerLabel}$nameLine
            Current time: $timeFmt
            Today's real numbers: recovery ${d.recovery}%, energy ${d.energy}%, strain ${"%.1f".format(d.strain)}/21,
            steps ${d.steps}, workout ${d.workoutMinutes} min, weekly avg strain ${"%.1f".format(d.weeklyAvgStrain)}.
            ${if (d.streak > 0) "Training streak: ${d.streak} days." else ""}
            ${if (d.weekWorkouts > 0) "Workouts this week: ${d.weekWorkouts}." else ""}
            ${historySection(history)}

            Write ONE short, natural sentence (8-16 words). Direct, warm, confident.
            - Never explain what Recovery, Strain, or Energy mean.
            - Never invent numbers; use only the data above.
            - Respect the current time ($timeFmt).
            - No clichés, no exclamation spam, no quotes.
            - End the message with EXACTLY ONE fitting emoji that matches the trigger
              (e.g. 🌅 recovery, 🏋️ workout, ⚡ strain, 🔥 streak, 🎯 goal, 📊 weekly).
            $hrRule
            Respond with ONLY this JSON: {"title": "...", "message": "...", "priority": "normal|high"}
        """.trimIndent()
    }

    private fun profileSection(profile: com.vanta.app.data.db.UserProfileRecord?): String {
        if (profile == null) return ""
        return buildString {
            append("\nAbout the athlete (fixed profile data):\n")
            append("  - Name: ").append(profile.name.ifBlank { "-" }).append("\n")
            append("  - Age: ").append(profile.calculatedAge).append("\n")
            append("  - Sex: ").append(profile.sex).append("\n")
            append("  - Height: ").append("%.0f".format(profile.heightCm)).append(" cm\n")
            append("  - Weight: ").append("%.1f".format(profile.weightKg)).append(" kg\n")
            append("  - Fitness goal: ").append(profile.fitnessGoal).append("\n")
            append("Use this ONLY to calibrate tone and effort framing. Never mention it unless it adds real context.")
        }
    }

    private fun historySection(history: List<DailyMetricRecord>): String {
        if (history.isEmpty()) return ""
        val recent = history.sortedBy { it.date }.takeLast(7)
        val lines = recent.joinToString("\n") { r ->
            "  ${r.date}: strain ${"%.1f".format(r.strain)}, recovery ${r.recovery}%, energy ${r.energy}%, steps ${r.steps}, ${r.workoutDurationMin} min workout"
        }
        val yday = recent.getOrNull(recent.size - 2)
        val strainDelta = if (yday != null) recent.last().strain - yday.strain else null
        val recoveryDelta = if (yday != null) recent.last().recovery - yday.recovery else null
        val avgStrain = if (recent.isNotEmpty()) recent.map { it.strain }.average() else 0.0
        return buildString {
            append("\nYour recent history (oldest to newest):\n").append(lines)
            if (strainDelta != null && recoveryDelta != null) {
                append("\n- vs previous day: strain ")
                append(if (strainDelta >= 0) "+" else "").append("%.1f".format(strainDelta))
                append(", recovery ")
                append(if (recoveryDelta >= 0) "+" else "").append("$recoveryDelta%")
            }
            append("\n- 7-day average strain: ").append("%.1f".format(avgStrain))
            append("\nUse this history to personalize: reference relevant trends or recent days ONLY when they add context. Never overload a short message with data.")
        }
    }
}
