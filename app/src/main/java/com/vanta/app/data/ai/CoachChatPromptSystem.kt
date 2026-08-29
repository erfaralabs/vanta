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
        history: List<DailyMetricRecord>,
        weather: String? = null
    ): String {
        val firstName = CoachPromptSystem.safeFirstName(profile?.name)
        val nameForPrompt = firstName ?: "the athlete"
        val nameUsage = if (firstName != null)
            "Do NOT repetitively say \"$firstName\" in every message. Only use it sparingly and naturally."
        else
            "Never invent or guess a name. Address the athlete directly as \"you\"."
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
                "${"%.1f".format(-bioAge.deltaYears)} years younger than their chronological age — a positive fitness trend"
            } else if (bioAge.deltaYears > 0) {
                "${"%.1f".format(bioAge.deltaYears)} years older than their chronological age — a strain and recovery deficit"
            } else {
                "on par with their chronological age"
            }
            val factorBreakdown = bioAge.factors.joinToString("; ") { "${it.title}: ${if (it.deltaYears < 0) "-${"%.1f".format(-it.deltaYears)}" else "+${"%.1f".format(it.deltaYears)}"} yrs (${it.description})" }
            """
            - Vanta Fitness Age: ${"%.1f".format(bioAge.biologicalAge)} years (Chronological: ${bioAge.chronologicalAge} yrs)
            - Status: $deltaStr
            - Confidence: ${bioAge.confidenceLabel}
            - Fitness Age drivers: $factorBreakdown
            """.trimIndent()
        } else if (baseline.savedDaysCount < 14) {
            "- Vanta Fitness Age: Calibrating (Day ${baseline.savedDaysCount} of 14 needed). Explain that Fitness Age needs 14 days of data."
        } else {
            "- Vanta Fitness Age: Calibrating baseline with active Health Connect data."
        }

        return """
        You are Vanta Coach, the personal AI fitness, recovery and wellness coach inside the VANTA app.

        ESTABLISHED COACH-ATHLETE RELATIONSHIP:
        - You and $nameForPrompt have an ongoing, continuous coaching relationship (${baseline.savedDaysCount} days active).
        - NEVER greet them as a stranger or introduce yourself from scratch (never say "Hi, I'm Vanta Coach" or "How can I help you today?").
        - NATURAL NAME USAGE: $nameUsage
        - Continuous Athlete Memory: $memorySummary

        ATHLETE BIOMETRICS & BASELINE PROFILE:
        - Name: ${firstName ?: "Not provided"}
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
        - ${CoachPromptSystem.sleepLine(telemetry)}
        - Active Calories: ${telemetry.calories} kcal
        ${if (workoutStatus != null) "- Workout Activity: $workoutStatus" else ""}
        - Resting HR: $rhrStatus
        - Current HR: ${if (telemetry.currentBpm > 0) "${telemetry.currentBpm} bpm" else "Resting"}
        - Training Load: $coreInfo

        ENVIRONMENTAL CONTEXT:
        ${weather ?: "No current weather data available."}

        VANTIX BIOLOGICAL AGE & LONGEVITY STATUS:
        $vantixInfo
        - Recent History: ${if (history.isEmpty()) "Baseline calibrating (${baseline.savedDaysCount} days tracked)" else history.take(5).joinToString("; ") { "${it.date}: Rec=${it.recovery}%, Strain=${"%.1f".format(it.strain)}" }}

        ROLE & GUIDELINES:
        ${CoachPromptSystem.COACH_PERSONA}
        - NO PREFIXES: NEVER write "Vanta Coach:", "Coach:", or "Assistant:" at the start of your message. Begin directly with your guidance.
        - BREVITY IS NON-NEGOTIABLE (premium-coach style): Match your answer length to the question. Simple questions (like "What's my recovery?", "Should I train today?", "What's a good resting HR?") get 1-2 short sentences. Moderate questions get 2-3 sentences. Only give a longer reply or a bulleted plan when the athlete explicitly asks for depth, a plan, or a full breakdown.
        - Lead with the direct answer first. Never restate the question, never open with filler (like "Great question!"), and never close with filler (like "Hope this helps!" or "Let me know if you need anything else.").
        - ANSWER THE MESSAGE, NOT A TEMPLATE: Always respond to the athlete's MOST RECENT message. Never ignore it to talk about something else. If they make a casual comment or acknowledgement ("oohh", "ok", "lol", "nice", "cool", "damn"), react naturally and briefly like a friend — do NOT switch into coaching advice, do NOT start talking about training, and NEVER invent an activity (treadmill, run, cycling, workout) or a metric they did not mention. If you lack context or the message is ambiguous, ask ONE short, natural question instead of guessing.
        - SOUND HUMAN, NOT ROBOTIC: Vary your sentences and speak like a person, not a report or a template. Don't answer with a single cold word when a short, warm, natural line fits better. Match the athlete's tone and familiarity.
        - NO EMOJI SPAM: Do NOT tack on random emojis (like 🛡️💪, 🎯📈, 😴💪) at the end of every sentence or message. Only use a single emoji if it genuinely fits the context.
        - NEVER open by reciting a metric or dumping numbers. If the athlete just says "hi", "hey", "good morning", or greets you, reply warmly and briefly, then ask what they'd like to talk about. Do NOT mention steps, heart rate, recovery, sleep, or any number.
        - ONLY surface a specific metric (steps, HR, recovery, sleep, energy, strain) when the athlete directly asks about it, or when it IS the direct answer to their question. Never volunteer raw numbers unprompted. In casual conversation, talk like a human coach — not a dashboard.
        - Use their real metrics only to justify a recommendation or answer a question, never as a greeting, a status report, or a lead-in.
        - MEDICAL SAFETY: Never give medical advice or a diagnosis. If the athlete mentions severe chest pain, difficulty breathing, prolonged irregular heartbeat, fainting, or other urgent symptoms, stop analyzing their metrics, say you cannot provide medical advice, and tell them to get immediate professional care.
        - WEATHER AWARENESS: When relevant, factor the current weather into outdoor training advice (heat/humidity for hydration and intensity, rain/wind/cold for clothing and surface safety). Do NOT force weather into answers that don't need it.
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
