package com.vanta.app

import com.vanta.app.data.DeterministicPhysiologyResult
import com.vanta.app.data.RecoveryCategory
import com.vanta.app.data.WatchWearMode
import com.vanta.app.data.db.DailyMetricRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that Room DB records are built exclusively from the deterministic
 * engine's calculated Strain/Recovery/Energy — the factory has no parameters
 * for those values, so hardcoded metrics cannot be persisted.
 */
class DailyMetricRecordTest {

    private val phys = DeterministicPhysiologyResult(
        strain = 8.3,
        recovery = 78,
        recoveryCategory = RecoveryCategory.GOOD,
        energy = 62,
        wearMode = WatchWearMode.ALL_DAY_WEAR,
        hrMax = 200,
        rhrBaseline = 60,
        rhrToday = 60,
        avgHrBaseline = 75,
        avgHrToday = 78,
        isLearningPhase = true,
        savedDaysCount = 3,
        baselineSummaryMessage = "baseline msg",
        breakdownExplanation = "breakdown"
    )

    @Test
    fun `factory stores exactly the engine-calculated strain recovery and energy`() {
        val record = DailyMetricRecord.fromPhysiology(
            date = "2026-08-06",
            timestamp = 1234L,
            restingBpm = 60,
            avgBpm = 78,
            maxBpm = 180,
            steps = 9000,
            calories = 500,
            distanceKm = 6.0,
            workoutDurationMin = 45,
            phys = phys
        )

        assertEquals(8.3, record.strain, 1e-9)
        assertEquals(78, record.recovery)
        assertEquals(62, record.energy)

        // Telemetry fields pass through unchanged.
        assertEquals("2026-08-06", record.date)
        assertEquals(1234L, record.timestamp)
        assertEquals(60, record.restingBpm)
        assertEquals(78, record.avgBpm)
        assertEquals(180, record.maxBpm)
        assertEquals(9000L, record.steps)
        assertEquals(500L, record.calories)
        assertEquals(6.0, record.distanceKm, 1e-9)
        assertEquals(45, record.workoutDurationMin)
    }
}
