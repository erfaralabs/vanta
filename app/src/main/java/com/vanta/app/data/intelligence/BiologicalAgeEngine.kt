package com.vanta.app.data.intelligence

import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.UserProfileRecord
import kotlin.math.roundToInt

data class BioAgeFactor(
    val title: String,
    val deltaYears: Double, // e.g. -1.5 (younger) or +0.8 (older)
    val description: String,
    val isPositive: Boolean
)

data class BioAgeResult(
    val biologicalAge: Double,
    val chronologicalAge: Int,
    val deltaYears: Double, // biologicalAge - chronologicalAge (negative = younger)
    val confidenceLabel: String,
    val factors: List<BioAgeFactor>,
    val thirtyDayDelta: Double? = null // e.g. -0.8 years younger than 30 days ago
)

/**
 * VANTA FITNESS AGE engine.
 *
 * Pipeline: baseline history → component trends → raw age estimate → EMA smoothing → displayed age.
 * Five capped factors drive the raw estimate (fitness_capacity / recovery_trend /
 * activity_consistency = moderate; load_balance / body_composition = small).
 * Confidence ramps 14d → Preliminary, 30d → Low, 60d → Medium, 90d+ → High.
 * EMA (0.9 * previous + 0.1 * raw) prevents single bad-day spikes from jarring the display.
 */
object BiologicalAgeEngine {

    fun compute(
        records: List<DailyMetricRecord>,
        chronologicalAge: Int,
        hasRhrBaseline: Boolean,
        core: AdaptiveIntelligenceEngine.AdaptiveCoreResult?,
        history30d: List<Double> = emptyList(),
        profile: UserProfileRecord? = null
    ): BioAgeResult? {
        if (core == null || records.size < 14) return null

        val sortedDesc = records.filter { it.hasRealData() }.sortedByDescending { it.date }
        val recent14 = sortedDesc.take(14)
        val factors = mutableListOf<BioAgeFactor>()
        var ageAdjustment = 0.0

        // ── Factor 1: Fitness capacity (moderate weight) ─────────────────────────
        val fitnessDelta = (if (core.isTrainingMode) {
            (12.0 - core.ctl) * 0.3
        } else {
            (8500.0 - core.avgSteps14d) / 2000.0
        }).coerceIn(-2.5, 2.5)
        ageAdjustment += fitnessDelta
        factors.add(
            BioAgeFactor(
                title = "Fitness capacity",
                deltaYears = fitnessDelta,
                description = if (core.isTrainingMode) {
                    "CTL training base ${"%.1f".format(core.ctl)}"
                } else {
                    "14-day step avg ${"%,d".format(core.avgSteps14d.toLong())}"
                },
                isPositive = fitnessDelta <= 0
            )
        )

        // ── Factor 2: Recovery trend (moderate weight; RHR tone + recovery) ─────
        val rhrParts = mutableListOf<String>()
        var recRhrDelta = 0.0
        if (hasRhrBaseline) {
            val rhrList = recent14.map { it.restingBpm }.filter { it in 35..120 }
            if (rhrList.isNotEmpty()) {
                val avgRhr = rhrList.average()
                val expectedRhr = if (profile?.sex?.equals("female", ignoreCase = true) == true) 65.0 else 62.0
                recRhrDelta = ((avgRhr - expectedRhr) * 0.25).coerceIn(-1.5, 1.5)
                rhrParts.add("RHR ${avgRhr.roundToInt()} bpm")
            }
        }
        val avgRecovery = sortedDesc.take(30).map { it.recovery }.filter { it > 0 }
            .let { if (it.isNotEmpty()) it.average() else 70.0 }
        val recQualityDelta = when {
            avgRecovery >= 80.0 -> -1.4
            avgRecovery >= 68.0 -> -0.7
            avgRecovery >= 55.0 -> 0.0
            else -> 1.2
        }
        val recoveryDelta = (recRhrDelta + recQualityDelta).coerceIn(-2.5, 2.5)
        ageAdjustment += recoveryDelta
        factors.add(
            BioAgeFactor(
                title = "Recovery trend",
                deltaYears = recoveryDelta,
                description = (rhrParts + listOf("recovery ${avgRecovery.roundToInt()}%")).joinToString(" · "),
                isPositive = recoveryDelta <= 0
            )
        )
        // ── Factor 3: Activity consistency (moderate weight) ─────────────────────
        val consistency = core.activityConsistency
        val consistencyDelta = when {
            consistency >= 0.80 -> -2.0
            consistency >= 0.65 -> -1.0
            consistency >= 0.50 -> 0.0
            else -> 1.5
        }.coerceIn(-2.5, 2.5)
        ageAdjustment += consistencyDelta
        factors.add(
            BioAgeFactor(
                title = "Activity consistency",
                deltaYears = consistencyDelta,
                description = "${(consistency * 100).roundToInt()}% active days in 30-day window",
                isPositive = consistencyDelta <= 0
            )
        )

        // ── Factor 4: Load balance (small weight) ────────────────────────────────
        val ratio = core.atlCtlRatio
        val balanceDelta = when {
            ratio in 0.85..1.15 && core.tsb >= -5 -> -0.8
            ratio > 1.30 -> 0.6
            ratio < 0.60 -> 0.5
            else -> 0.0
        }.coerceIn(-1.2, 1.2)
        ageAdjustment += balanceDelta
        factors.add(
            BioAgeFactor(
                title = "Load balance",
                deltaYears = balanceDelta,
                description = "ATL/CTL ${"%.2f".format(ratio)} (${core.loadStatus.label})",
                isPositive = balanceDelta <= 0
            )
        )

        // ── Factor 5: Body composition (small weight; BMI only) ──────────────────
        if (profile != null && profile.heightCm > 100.0 && profile.weightKg > 30.0) {
            val heightM = profile.heightCm / 100.0
            val bmi = profile.weightKg / (heightM * heightM)
            val bmiDelta = when {
                bmi in 20.5..24.5 -> -1.2
                bmi in 18.5..25.9 -> -0.6
                bmi in 26.0..28.5 -> 0.4
                bmi in 28.6..32.0 -> 0.9
                bmi > 32.0 -> 1.4
                else -> 0.6
            }.coerceIn(-1.5, 1.5)
            ageAdjustment += bmiDelta
            factors.add(
                BioAgeFactor(
                    title = "Body composition",
                    deltaYears = bmiDelta,
                    description = "BMI ${"%.1f".format(bmi)}",
                    isPositive = bmiDelta <= 0
                )
            )
        }

        // ── Raw estimate → clamp → EMA smoothing ────────────────────────────────
        val effectiveChronoAge = (profile?.calculatedAge ?: chronologicalAge).coerceAtLeast(1)
        val minAge = (effectiveChronoAge - 15).coerceAtLeast(1).toDouble()
        val maxAge = (effectiveChronoAge + 15).toDouble()
        val rawBioAge = (effectiveChronoAge.toDouble() + ageAdjustment).coerceIn(minAge, maxAge)

        // EMA: newDisplayedAge = 0.9 * previousDisplayedAge + 0.1 * rawAge
        val displayedAge = if (history30d.isNotEmpty()) {
            0.9 * history30d.first() + 0.1 * rawBioAge
        } else {
            rawBioAge
        }

        // 30-day trend delta
        val thirtyDayDelta = if (history30d.size >= 7) {
            val pastAvg = history30d.takeLast(7).average()
            displayedAge - pastAvg
        } else null

        val confidence = when {
            records.size >= 90 -> "High confidence"
            records.size >= 60 -> "Medium confidence"
            records.size >= 30 -> "Low confidence"
            else -> "Preliminary"
        }

        return BioAgeResult(
            biologicalAge = displayedAge,
            chronologicalAge = effectiveChronoAge,
            deltaYears = displayedAge - effectiveChronoAge.toDouble(),
            confidenceLabel = confidence,
            factors = factors,
            thirtyDayDelta = thirtyDayDelta
        )
    }
}

