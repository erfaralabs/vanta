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
 * VANTIX Biological Age Engine — computes physiological age using 6 evidence-based factors:
 * 1. Resting Heart Rate & Autonomic Tone
 * 2. Cardiorespiratory Fitness Capacity (CTL or 14-day Step Volume)
 * 3. Body Composition & Metabolic Index (BMI from user Height & Weight)
 * 4. Activity Consistency & Regularity
 * 5. Recovery Quality & Autonomic Sleep Readiness
 * 6. Training Stress Balance & Workload Efficiency
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
        // Bio Age is gated to VANTIX activation (>= 14 days)
        if (core == null || records.size < 14) return null

        val sortedDesc = records.filter { it.hasRealData() }.sortedByDescending { it.date }
        val recent14 = sortedDesc.take(14)
        val factors = mutableListOf<BioAgeFactor>()
        var ageAdjustment = 0.0

        // ── Factor 1: Resting Heart Rate & Cardiovascular Tone ─────────────────
        if (hasRhrBaseline) {
            val rhrList = recent14.map { it.restingBpm }.filter { it in 35..120 }
            if (rhrList.isNotEmpty()) {
                val avgRhr = rhrList.average()
                // Normative reference: ~62 bpm baseline for healthy adults (adjusted slightly for sex)
                val expectedRhr = if (profile?.sex?.equals("female", ignoreCase = true) == true) 65.0 else 62.0
                val rhrDelta = ((avgRhr - expectedRhr) * 0.25).coerceIn(-4.0, 4.0)
                ageAdjustment += rhrDelta
                factors.add(
                    BioAgeFactor(
                        title = "Resting Heart Rate",
                        deltaYears = rhrDelta,
                        description = "Overnight RHR avg: ${avgRhr.roundToInt()} bpm",
                        isPositive = rhrDelta <= 0
                    )
                )
            }
        }

        // ── Factor 2: Cardiorespiratory Fitness Proxy (CTL / Step Volume) ──────
        val fitnessDelta = if (core.isTrainingMode) {
            // Training mode: CTL base is the VO2max proxy
            ((12.0 - core.ctl) * 0.3).coerceIn(-3.0, 3.0)
        } else {
            // Daily mover mode: 14d avg steps vs 8500 step norm
            ((8500.0 - core.avgSteps14d) / 2000.0).coerceIn(-3.0, 3.0)
        }
        ageAdjustment += fitnessDelta
        factors.add(
            BioAgeFactor(
                title = if (core.isTrainingMode) "Fitness Capacity (CTL)" else "Daily Step Volume",
                deltaYears = fitnessDelta,
                description = if (core.isTrainingMode) "CTL load base: ${"%.1f".format(core.ctl)}" else "14-day step avg: ${"%,d".format(core.avgSteps14d.toLong())}",
                isPositive = fitnessDelta <= 0
            )
        )

        // ── Factor 3: Body Composition & Metabolic Index (BMI & Weight) ────────
        if (profile != null && profile.heightCm > 100.0 && profile.weightKg > 30.0) {
            val heightM = profile.heightCm / 100.0
            val bmi = profile.weightKg / (heightM * heightM)
            val bmiDelta = when {
                bmi in 20.5..24.5 -> -1.2 // Optimal athletic/healthy metabolic zone
                bmi in 18.5..25.9 -> -0.6 // Healthy normal zone
                bmi in 26.0..28.5 -> 0.5  // Mild metabolic load
                bmi in 28.6..32.0 -> 1.4  // Elevated metabolic strain
                bmi > 32.0 -> 2.2         // High metabolic load
                else -> 0.8               // Underweight deficit
            }
            ageAdjustment += bmiDelta
            factors.add(
                BioAgeFactor(
                    title = "Body Mass Index (BMI)",
                    deltaYears = bmiDelta,
                    description = "BMI: ${"%.1f".format(bmi)} (${profile.weightKg.roundToInt()} kg / ${profile.heightCm.roundToInt()} cm)",
                    isPositive = bmiDelta <= 0
                )
            )
        }

        // ── Factor 4: Activity Consistency ────────────────────────────────────
        val consistency = core.activityConsistency
        val consistencyDelta = when {
            consistency >= 0.80 -> -2.0
            consistency >= 0.65 -> -1.0
            consistency >= 0.50 -> 0.0
            else -> 1.5
        }
        ageAdjustment += consistencyDelta
        factors.add(
            BioAgeFactor(
                title = "Activity Consistency",
                deltaYears = consistencyDelta,
                description = "${(consistency * 100).roundToInt()}% active days in 30-day window",
                isPositive = consistencyDelta <= 0
            )
        )

        // ── Factor 5: Recovery Quality (30-day rolling avg) ───────────────────
        val avgRecovery = sortedDesc.take(30).map { it.recovery }.filter { it > 0 }.let { if (it.isNotEmpty()) it.average() else 70.0 }
        val recoveryDelta = when {
            avgRecovery >= 80.0 -> -2.0
            avgRecovery >= 68.0 -> -1.0
            avgRecovery >= 55.0 -> 0.0
            else -> 1.8
        }
        ageAdjustment += recoveryDelta
        factors.add(
            BioAgeFactor(
                title = "Recovery Quality",
                deltaYears = recoveryDelta,
                description = "30-day recovery avg: ${avgRecovery.roundToInt()}%",
                isPositive = recoveryDelta <= 0
            )
        )

        // ── Factor 6: Training Stress Balance & Workload Efficiency ───────────
        val ratio = core.atlCtlRatio
        val balanceDelta = when {
            ratio in 0.85..1.15 && core.tsb >= -5 -> -1.2
            ratio > 1.30 -> 1.0 // overreaching penalty
            ratio < 0.60 -> 0.8 // underload drift penalty
            else -> 0.0
        }
        ageAdjustment += balanceDelta
        factors.add(
            BioAgeFactor(
                title = "Training Load Balance",
                deltaYears = balanceDelta,
                description = "ATL/CTL ratio: ${"%.2f".format(ratio)} (${core.loadStatus.label})",
                isPositive = balanceDelta <= 0
            )
        )

        // ── Biological Age Derivation with EWMA Smoothing ─────────────────────
        val effectiveChronoAge = (profile?.calculatedAge ?: chronologicalAge).coerceAtLeast(1)
        val minAge = (effectiveChronoAge - 15).coerceAtLeast(1).toDouble()
        val maxAge = (effectiveChronoAge + 15).toDouble()
        val rawBioAge = (effectiveChronoAge.toDouble() + ageAdjustment).coerceIn(minAge, maxAge)

        // Exponential EWMA smoothing with past history to prevent daily noise
        val smoothedBioAge = if (history30d.isNotEmpty()) {
            val alpha = 0.15
            (alpha * rawBioAge) + ((1.0 - alpha) * history30d.first())
        } else {
            rawBioAge
        }

        // 30-day trend delta
        val thirtyDayDelta = if (history30d.size >= 7) {
            val pastAvg = history30d.takeLast(7).average()
            smoothedBioAge - pastAvg
        } else {
            null
        }

        val confidence = when {
            records.size >= 30 -> "HIGH CONFIDENCE (30d+)"
            records.size >= 21 -> "MEDIUM CONFIDENCE (21-29d)"
            else -> "CALIBRATED (Day ${records.size})"
        }

        return BioAgeResult(
            biologicalAge = smoothedBioAge,
            chronologicalAge = effectiveChronoAge,
            deltaYears = smoothedBioAge - effectiveChronoAge.toDouble(),
            confidenceLabel = confidence,
            factors = factors,
            thirtyDayDelta = thirtyDayDelta
        )
    }
}
