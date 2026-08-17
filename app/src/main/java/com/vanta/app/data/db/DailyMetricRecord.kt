package com.vanta.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vanta.app.data.DeterministicPhysiologyResult

/**
 * Room DB Entity for completed daily health telemetry & physiological readiness scores.
 * Keys by local ISO date string "YYYY-MM-DD".
 * Stores WHOOP-inspired 0.0–21.0 Strain score as a Double.
 */
@Entity(tableName = "daily_metrics")
data class DailyMetricRecord(
    @PrimaryKey
    val date: String,
    val timestamp: Long,
    val restingBpm: Int,
    val avgBpm: Int,
    val maxBpm: Int,
    val steps: Long,
    val calories: Long,
    val distanceKm: Double,
    val workoutDurationMin: Int,
    val strain: Double, // WHOOP-inspired 0.0–21.0 scale
    val recovery: Int,
    val energy: Int,
    val biologicalAge: Double = 0.0
) {
    /**
     * True when this day has genuinely recorded telemetry (steps, HR, calories,
     * distance or a workout). Empty days — app closed, watch not worn — must
     * never count as training days or skew the baseline.
     */
    fun hasRealData(): Boolean =
        steps > 0 || calories > 0 || avgBpm > 0 || maxBpm > 0 ||
            distanceKm > 0 || workoutDurationMin > 0

    companion object {
        /**
         * Single source of truth for persisting a daily record. Strain / Recovery /
         * Energy are taken EXCLUSIVELY from the deterministic engine's calculated
         * result — this factory has no parameters for them, so a hardcoded or
         * fabricated value can never be written to Room DB by construction.
         * All Room DB writes should go through this factory.
         */
        fun fromPhysiology(
            date: String,
            timestamp: Long,
            restingBpm: Int,
            avgBpm: Int,
            maxBpm: Int,
            steps: Long,
            calories: Long,
            distanceKm: Double,
            workoutDurationMin: Int,
            phys: DeterministicPhysiologyResult
        ): DailyMetricRecord = DailyMetricRecord(
            date = date,
            timestamp = timestamp,
            restingBpm = restingBpm,
            avgBpm = avgBpm,
            maxBpm = maxBpm,
            steps = steps,
            calories = calories,
            distanceKm = distanceKm,
            workoutDurationMin = workoutDurationMin,
            strain = phys.strain,
            recovery = phys.recovery,
            energy = phys.energy
        )
    }
}
