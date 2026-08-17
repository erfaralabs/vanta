package com.vanta.app.ui.utils

import android.content.Context
import com.vanta.app.data.AiOverviewCache
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * Time-of-day & physiological home hero subtitle engine.
 *
 * Harmonizes the current time of day (Morning, Afternoon, Evening, Night)
 * with real physiological telemetry (Recovery %, Strain, Energy %, Steps).
 */
object VantaSubtitle {

    // ── Time-of-Day Pools ──────────────────────────────────────────────────────
    private val morningFreshPool = listOf(
        "A fresh day starts here.",
        "Ready when you are.",
        "The tank is full.",
        "Fresh legs, clear head."
    )

    private val morningEasyPool = listOf(
        "Take it easy today.",
        "Pace yourself this morning.",
        "Focus on recovery today.",
        "Light movement only."
    )

    private val afternoonActivePool = listOf(
        "Keep the momentum going.",
        "You're moving well today.",
        "Solid load building up.",
        "Looking good so far."
    )

    private val eveningBankedPool = listOf(
        "Work's done. Rest now.",
        "Solid day banked.",
        "Protect what you built.",
        "Keep the evening relaxed."
    )

    private val nightRechargePool = listOf(
        "Recharge for tomorrow.",
        "Time to wind down.",
        "Bank good rest tonight.",
        "Rest up and recharge."
    )

    /**
     * Computes the live home subtitle based on Time of Day & Physiology.
     */
    fun pick(
        context: Context,
        recovery: Int,
        strain: Double,
        energy: Int,
        steps: Long,
        exerciseMinutes: Int,
    ): String {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()

        // 1. Check AI Coach recommendation if generated today
        val aiCache = AiOverviewCache(context).load()
        if (aiCache != null && aiCache.date == today && aiCache.recommendation.isNotBlank()) {
            val rec = aiCache.recommendation.trim()
            val words = rec.split("\\s+".toRegex())
            if (words.size <= 7) {
                return rec
            } else if (rec.contains("—")) {
                val clause = rec.split("—").first().trim()
                val clauseWords = clause.split("\\s+".toRegex())
                if (clauseWords.size in 2..7) return clause
            }
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 2. Select Pool based on Time of Day & Physiological State
        val pool = when {
            // Night (9 PM - 4 AM): Winding down & recharging for tomorrow
            hour >= 21 || hour < 5 -> nightRechargePool

            // Low recovery (< 55%): Rest framing across all daytime hours
            recovery > 0 && recovery <= 54 -> morningEasyPool

            // Evening (5 PM - 8 PM): Evening relaxation & load protection
            hour in 17..20 -> eveningBankedPool

            // Afternoon (12 PM - 4 PM): Active momentum & progress
            hour in 12..16 -> afternoonActivePool

            // Morning (5 AM - 11 AM): Fresh morning start
            else -> morningFreshPool
        }

        // 3. Stable date + time block seed (constant throughout the time-of-day window)
        val timeBlock = when (hour) {
            in 5..11 -> 0
            in 12..16 -> 1
            in 17..20 -> 2
            else -> 3
        }
        val seed = Math.abs(today.hashCode() + timeBlock + (recovery % 7))
        val idx = seed % pool.size

        return pool[idx]
    }
}
