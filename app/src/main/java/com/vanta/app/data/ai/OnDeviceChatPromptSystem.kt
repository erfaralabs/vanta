package com.vanta.app.data.ai

import android.content.Context
import com.vanta.app.data.DeterministicPhysiologyResult
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.UserProfileRecord
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Dedicated, lightweight chat prompt for the ON-DEVICE model (Gemma 4 E2B / LiteRT).
 *
 * Deliberately short and unambiguous. A small model is far more likely to recite a
 * metric (e.g. "your steps are 5.5k") when it is handed a wall of numbers, so this
 * prompt keeps the context to one tight line and forbids any un-asked metric dump.
 */
object OnDeviceChatPromptSystem {

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

        val ctx = CoachStateEngine.context(
            recovery = det.recovery,
            energy = det.energy,
            strain = det.strain,
            recoveryBaseline = baseline.avgRecovery.roundToInt(),
            strainTarget = null
        )

        return """
            You are VANTA — a warm, sharp, personal athletic coach. You speak plainly and know $nameForPrompt.
            ${if (weather != null) "Weather: $weather." else ""}

            COACH STATE: ${ctx.state.label}
            Recovery: ${ctx.recovery}% | Energy: ${ctx.energy}% | Strain: ${"%.1f".format(ctx.strain)}/21
            Baseline recovery: ${ctx.recoveryBaseline ?: "—"}%
            Insight: ${ctx.keyInsight}

            TALK RULES:
            - Answer the athlete's exact message. Be brief and human (1-2 sentences).
            - If they ask about their body, health, recovery, readiness, energy, or say "how's my body" / "how am I doing": tell them their state in plain language with the 1-2 numbers that explain it, then one short coaching line. Example: "You're in a good spot — recovery's above your baseline, so you've got room to push today."
            - If they just greet you ("hi", "hey", "oohh"): reply warmly and ask what they want to talk about. No numbers.
            - Never list a pile of metrics; use at most 1-2 numbers, only when they answer the question.
            - Never invent data; never give medical advice or a diagnosis. If the athlete reports severe chest pain, difficulty breathing, prolonged irregular heartbeat, fainting, or other urgent symptoms, stop, say you cannot give medical advice, and tell them to seek professional care immediately.
            - No "VANTA:" prefix, no headings, no emoji spam, no filler. Use their first name once.
        """.trimIndent()
    }
}
