package com.vanta.app.data.baseline

import android.content.Context
import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.VantaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Manages baseline evaluation from stored Room DB daily metric records.
 * Dynamically computes rolling 7-day averages once 7 consecutive archived records exist.
 * Filters out zero/missing HR samples to ensure baseline accuracy when smartwatch is not worn.
 */
class AdaptiveBaselineManager(private val context: Context) {
    private val db = VantaDatabase.getInstance(context)
    private val dao = db.dailyMetricsDao()
    private val profileDao = db.userProfileDao()

    val baselineFlow: Flow<UserBaseline> = dao.getAllRecordsFlow().map {
        // Use getCurrentBaseline() so biologicalAge is always computed and
        // persisted — the static helper cannot access the DB for bio age history
        // or the user profile, so it would always emit biologicalAge = null.
        getCurrentBaseline()
    }

    suspend fun getCurrentBaseline(): UserBaseline = withContext(Dispatchers.IO) {
        val records = dao.getAllRecords()
        val base = computeBaselineFromRecords(records)

        // Compute Biological Age when VANTIX is active (core != null)
        val core = base.adaptiveCore
        if (core != null) {
            val profile = profileDao.getUserProfile()
            val chronoAge = profile?.calculatedAge ?: 27
            val history30d = dao.getBioAgeHistory(30)

            val bioResult = com.vanta.app.data.intelligence.BiologicalAgeEngine.compute(
                records = records,
                chronologicalAge = chronoAge,
                hasRhrBaseline = base.hasRestingHrBaseline,
                core = core,
                history30d = history30d,
                profile = profile
            )

            if (bioResult != null) {
                // Update today's Room record with the computed biological age
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
                dao.updateBioAge(today, bioResult.biologicalAge)
                return@withContext base.copy(biologicalAge = bioResult)
            }
        }

        base
    }

    companion object {
        internal fun computeBaselineFromRecords(records: List<DailyMetricRecord>): UserBaseline {
        // Count only COMPLETED archived days and never double-count a date, so the
        // "N days archived" / baseline progress can't run ahead of real history
        // (today is still in-progress and must not count as a full tracked day).
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val real = records.filter { it.hasRealData() }
            .filter { it.date < today }
            .distinctBy { it.date }
        val count = real.size
        val isLearningPhase = count < 7

        if (real.isEmpty()) {
            return UserBaseline.Default.copy(savedDaysCount = 0, isLearningPhase = true)
        }

        val window = real.take(7)

        val validRhrs = window.map { it.restingBpm }.filter { it in 35..120 }
        val avgRhr = if (validRhrs.isNotEmpty()) validRhrs.average() else 60.0

        val validAvgHrs = window.map { it.avgBpm }.filter { it in 40..220 }
        val avgAvgHr = if (validAvgHrs.isNotEmpty()) validAvgHrs.average() else 75.0

        val validMaxHrs = window.map { it.maxBpm }.filter { it in 50..220 }
        val avgMaxHr = if (validMaxHrs.isNotEmpty()) validMaxHrs.average() else 120.0

        val avgSteps = window.map { it.steps }.average()
        val avgCals = window.map { it.calories }.average()
        val avgDist = window.map { it.distanceKm }.average()
        val avgDuration = window.map { it.workoutDurationMin }.average()
        val avgStrain = window.map { it.strain }.average()

        val validRecoveries = window.map { it.recovery }.filter { it > 0 }
        val avgRecovery = if (validRecoveries.isNotEmpty()) validRecoveries.average() else 80.0

        val validEnergies = window.map { it.energy }.filter { it > 0 }
        val avgEnergy = if (validEnergies.isNotEmpty()) validEnergies.average() else 75.0

        val personalRecords = real.take(30)
            .filter { it.strain >= 4.0 && it.restingBpm in 35..120 }
        val personalStrainFactor = VantaDeterministicPhysiologyEngine.personalStrainFactor(
            strains = personalRecords.map { it.strain },
            restingBpms = personalRecords.map { it.restingBpm.toDouble() },
            baselineRestingBpm = avgRhr
        )

        val core = com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine.compute(records)

        return UserBaseline(
            savedDaysCount = count,
            isLearningPhase = isLearningPhase,
            avgRestingBpm = avgRhr,
            avgAvgBpm = avgAvgHr,
            avgMaxBpm = avgMaxHr,
            avgSteps = avgSteps,
            avgCalories = avgCals,
            avgDistanceKm = avgDist,
            avgWorkoutDurationMin = avgDuration,
            avgStrain = avgStrain,
            avgRecovery = avgRecovery,
            avgEnergy = avgEnergy,
            personalStrainFactor = personalStrainFactor,
            hasRestingHrBaseline = validRhrs.isNotEmpty(),
            adaptiveCore = core
        )
    }
    } // end companion object

    suspend fun getHistoricalRecords(): List<DailyMetricRecord> = withContext(Dispatchers.IO) {
        dao.getAllRecords()
    }
}
