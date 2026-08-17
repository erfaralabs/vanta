package com.vanta.app

import com.vanta.app.data.baseline.AdaptiveBaselineManager
import com.vanta.app.data.db.DailyMetricRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the 7-day pipeline end-to-end at the computation layer:
 *  - <7 archived days  -> Learning Phase, generic defaults
 *  - >=7 archived days -> Personalized Baseline from the REAL 7-day averages
 *  - the personal strain factor calibrates from the user's own strain→RHR history
 *
 * This is the exact function the app runs on every launch and every analysis, so
 * these assertions prove the "after 7 days it analyzes your data and improves
 * calibration" behavior without needing a device.
 */
class SevenDayBaselineTest {

    private fun record(
        date: String,
        strain: Double,
        restingBpm: Int,
        avgBpm: Int,
        steps: Long,
        calories: Long,
        duration: Int
    ): DailyMetricRecord = DailyMetricRecord(
        date = date,
        timestamp = 0L,
        restingBpm = restingBpm,
        avgBpm = avgBpm,
        maxBpm = 165,
        steps = steps,
        calories = calories,
        distanceKm = 6.0,
        workoutDurationMin = duration,
        strain = strain,
        recovery = 80,
        energy = 70
    )

    /** Newest-first, exactly as the Room DAO returns (ORDER BY date DESC). */
    private fun sevenDays(): List<DailyMetricRecord> = listOf(
        record("2026-08-06", 5.5, 62, 95, 8000, 520, 40),
        record("2026-08-05", 8.0, 64, 105, 9000, 610, 55),
        record("2026-08-04", 4.0, 61, 90, 7000, 470, 30),
        record("2026-08-03", 6.5, 63, 100, 8500, 560, 45),
        record("2026-08-02", 3.0, 60, 88, 6000, 420, 20),
        record("2026-08-01", 7.0, 65, 102, 9500, 590, 50),
        record("2026-07-31", 5.0, 62, 92, 7500, 500, 35)
    )

    @Test
    fun `six or fewer days stays in learning phase`() {
        val b = AdaptiveBaselineManager.computeBaselineFromRecords(sevenDays().take(6))
        assertTrue("must still be learning phase with 6 days", b.isLearningPhase)
        assertEquals(6, b.savedDaysCount)
    }

    @Test
    fun `seven days activates personalized baseline computed from the real 7-day window`() {
        val b = AdaptiveBaselineManager.computeBaselineFromRecords(sevenDays())

        assertFalse("7 days must exit learning phase", b.isLearningPhase)
        assertEquals(7, b.savedDaysCount)

        // Averages must match the actual 7-day window, not hardcoded defaults.
        val avgStrain = (5.5 + 8.0 + 4.0 + 6.5 + 3.0 + 7.0 + 5.0) / 7.0
        val avgSteps = (8000 + 9000 + 7000 + 8500 + 6000 + 9500 + 7500) / 7.0
        val avgRhr = (62 + 64 + 61 + 63 + 60 + 65 + 62) / 7.0
        val avgCalories = (520 + 610 + 470 + 560 + 420 + 590 + 500) / 7.0
        val avgDuration = (40 + 55 + 30 + 45 + 20 + 50 + 35) / 7.0

        assertEquals(avgStrain, b.avgStrain, 1e-9)
        assertEquals(avgSteps, b.avgSteps, 1e-9)
        assertEquals(avgRhr, b.avgRestingBpm, 1e-9)
        assertEquals(avgCalories, b.avgCalories, 1e-9)
        assertEquals(avgDuration, b.avgWorkoutDurationMin, 1e-9)
    }

    @Test
    fun `user whose RHR tracks strain calibrates to a higher penalty factor`() {
        // Strain rises 4 -> 10 while resting HR rises too (sensitive response).
        val sensitive = listOf(
            record("2026-08-06", 10.0, 67, 115, 10000, 700, 60),
            record("2026-08-05", 9.0, 66, 112, 9500, 680, 55),
            record("2026-08-04", 8.0, 65, 108, 9000, 650, 50),
            record("2026-08-03", 7.0, 64, 105, 8500, 620, 45),
            record("2026-08-02", 6.0, 63, 100, 8000, 590, 40),
            record("2026-08-01", 5.0, 62, 96, 7500, 560, 35),
            record("2026-07-31", 4.0, 61, 92, 7000, 520, 30)
        )
        val b = AdaptiveBaselineManager.computeBaselineFromRecords(sensitive)
        assertTrue("sensitive user must get factor > 1, got ${b.personalStrainFactor}", b.personalStrainFactor > 1.0)
    }

    @Test
    fun `user whose RHR stays flat keeps the base penalty`() {
        // Same rising strain but a stable resting HR -> resilient response.
        val flat = listOf(
            record("2026-08-06", 10.0, 61, 115, 10000, 700, 60),
            record("2026-08-05", 9.0, 62, 112, 9500, 680, 55),
            record("2026-08-04", 8.0, 61, 108, 9000, 650, 50),
            record("2026-08-03", 7.0, 62, 105, 8500, 620, 45),
            record("2026-08-02", 6.0, 61, 100, 8000, 590, 40),
            record("2026-08-01", 5.0, 62, 96, 7500, 560, 35),
            record("2026-07-31", 4.0, 61, 92, 7000, 520, 30)
        )
        val b = AdaptiveBaselineManager.computeBaselineFromRecords(flat)
        assertTrue("resilient user must stay near/under factor 1, got ${b.personalStrainFactor}", b.personalStrainFactor <= 1.0)
    }
}
