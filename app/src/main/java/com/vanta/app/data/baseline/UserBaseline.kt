package com.vanta.app.data.baseline

import com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine

/**
 * Encapsulates the user's adaptive baseline computed from archived daily records in Room DB.
 * Tracks whether the user is in the 7-day Learning Phase or Personalized Baseline Phase.
 * `personalStrainFactor` personalizes the recovery strain penalty from the user's own
 * objective history (RHR response to strain). 1.0 = the unmodified base algorithm.
 * `adaptiveCore` is non-null once >= 14 real training days have been archived — it
 * carries ATL/CTL/TSB signals that continuously fine-tune recovery and AI coaching.
 */
data class UserBaseline(
    val savedDaysCount: Int,
    val isLearningPhase: Boolean,
    val avgRestingBpm: Double,
    val avgAvgBpm: Double,
    val avgMaxBpm: Double,
    val avgSteps: Double,
    val avgCalories: Double,
    val avgDistanceKm: Double,
    val avgWorkoutDurationMin: Double,
    val avgStrain: Double,
    val avgRecovery: Double = 80.0,
    val avgEnergy: Double = 75.0,
    val personalStrainFactor: Double = 1.0,
    /** False when no genuine overnight resting-HR readings exist (sleep not tracked). */
    val hasRestingHrBaseline: Boolean = false,
    /**
     * VANTA Adaptive Core result — non-null when >= 14 real days have been archived.
     * Contains ATL, CTL, TSB, load status, readiness trend, and user mode classification.
     */
    val adaptiveCore: AdaptiveIntelligenceEngine.AdaptiveCoreResult? = null,
    /**
     * VANTIX Biological Age result — non-null when >= 14 real days have been archived.
     */
    val biologicalAge: com.vanta.app.data.intelligence.BioAgeResult? = null
) {
    companion object {
        /**
         * Default population baseline used during initial setup or before data accumulates.
         */
        val Default = UserBaseline(
            savedDaysCount = 0,
            isLearningPhase = true,
            avgRestingBpm = 60.0,
            avgAvgBpm = 74.0,
            avgMaxBpm = 160.0,
            avgSteps = 8000.0,
            avgCalories = 450.0,
            avgDistanceKm = 5.0,
            avgWorkoutDurationMin = 45.0,
            avgStrain = 10.0,
            avgRecovery = 80.0,
            avgEnergy = 75.0,
            personalStrainFactor = 1.0
        )
    }

    val phaseLabel: String
        get() = if (isLearningPhase) {
            "Learning Phase (Day ${savedDaysCount.coerceAtLeast(1)} of 7)"
        } else {
            "Personalized Baseline (7-Day Window)"
        }

    val subtleStatusMessage: String
        get() = when {
            isLearningPhase -> "🌱 Building your personalized baseline — Day ${savedDaysCount.coerceAtLeast(1)} of 7 completed."
            adaptiveCore != null -> "⚡ Adaptive Core active."
            else -> "✨ Baseline active — calibrating toward Adaptive Core at 14 days."
        }
}
