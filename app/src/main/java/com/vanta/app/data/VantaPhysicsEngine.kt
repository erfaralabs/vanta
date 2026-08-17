package com.vanta.app.data

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Mathematical Physics & Physiological Engine:
 * Calculates Strain, Recovery %, and Energy Readiness using logarithmic heart rate impulse integration.
 */
object VantaPhysicsEngine {

    /**
     * Calculates mathematical Strain (0.0 – 21.0):
     * Logarithmic integral of cardiovascular effort weighted by HR zones.
     */
    fun calculateStrain(hrZones: List<HrZone>): Float {
        var effortSum = 0.0
        hrZones.forEachIndexed { index, zone ->
            val weight = (index + 1) * 1.6
            effortSum += zone.minutes * weight
        }
        val strain = (ln(1.0 + effortSum / 12.0) * 5.2).toFloat()
        return (strain.coerceIn(0.0f, 21.0f) * 10).roundToInt() / 10.0f
    }

    /**
     * Calculates mathematical Recovery Score (0% – 100%):
     * Derived from Resting Heart Rate (RHR) deviation from physiological baseline (60 bpm).
     */
    fun calculateRecovery(restingHr: Int = 62): Int {
        val baselineRhr = 60
        val rhrDelta = baselineRhr - restingHr
        val score = 82.0f + (rhrDelta * 4.5f)
        return score.roundToInt().coerceIn(10, 100)
    }

    /**
     * Calculates Energy Readiness Score (0% – 100%):
     * Derived from Recovery % & Strain ratio physics.
     */
    fun calculateEnergy(recoveryPercent: Int, strainScore: Float): Int {
        val strainPenalty = (strainScore / 21.0f) * 20.0f
        val energy = recoveryPercent - strainPenalty
        return energy.roundToInt().coerceIn(10, 100)
    }
}
