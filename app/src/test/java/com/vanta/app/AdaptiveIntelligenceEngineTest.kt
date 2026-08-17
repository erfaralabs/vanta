package com.vanta.app

import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine
import org.junit.Assert.*
import org.junit.Test

class AdaptiveIntelligenceEngineTest {

    private fun makeRecord(date: String, strain: Double, steps: Long = 8000L, recovery: Int = 75, workoutMin: Int = 30): DailyMetricRecord {
        return DailyMetricRecord(
            date = date,
            timestamp = System.currentTimeMillis(),
            restingBpm = 58,
            avgBpm = 72,
            maxBpm = 145,
            steps = steps,
            calories = 2200L,
            distanceKm = 6.2,
            workoutDurationMin = workoutMin,
            strain = strain,
            recovery = recovery,
            energy = 80
        )
    }

    @Test
    fun `returns null if records are less than minimum threshold`() {
        val records = (1..10).map { i ->
            makeRecord("2026-08-%02d".format(i), strain = 10.0)
        }
        val result = AdaptiveIntelligenceEngine.compute(records)
        assertNull(result)
    }

    @Test
    fun `computes valid ATL, CTL, and TSB with 14 or more days`() {
        val records = (1..20).map { i ->
            makeRecord("2026-08-%02d".format(i), strain = 12.0, workoutMin = 45)
        }
        val result = AdaptiveIntelligenceEngine.compute(records)
        assertNotNull(result)

        // Steady strain of 12 should converge ATL and CTL close to 12
        assertEquals(12.0, result!!.atl, 0.5)
        assertEquals(12.0, result.ctl, 1.5)
        // Ratio should be close to 1.0 (Optimal)
        assertTrue(result.atlCtlRatio in 0.8..1.3)
        assertEquals(AdaptiveIntelligenceEngine.LoadStatus.OPTIMAL, result.loadStatus)
        assertTrue(result.isTrainingMode)
    }

    @Test
    fun `detects overreaching when acute strain spikes above chronic base`() {
        // 35 days of light training (strain 5), then 7 days of intense spike (strain 18)
        val records = mutableListOf<DailyMetricRecord>()
        for (i in 1..35) {
            records.add(makeRecord("2026-07-%02d".format(i), strain = 5.0, workoutMin = 20))
        }
        for (i in 1..7) {
            records.add(makeRecord("2026-08-%02d".format(i), strain = 18.0, workoutMin = 90))
        }

        val result = AdaptiveIntelligenceEngine.compute(records)
        assertNotNull(result)

        // ATL should be much higher than CTL
        assertTrue("ATL (${result!!.atl}) must be > CTL (${result.ctl})", result.atl > result.ctl)
        assertTrue("ACWR ratio (${result.atlCtlRatio}) should exceed 1.3", result.atlCtlRatio > 1.3)
        assertEquals(AdaptiveIntelligenceEngine.LoadStatus.OVERREACHING, result.loadStatus)
        // TSB should be strongly negative (fatigued)
        assertTrue("TSB (${result.tsb}) should be negative", result.tsb < 0)
    }

    @Test
    fun `detects underloaded status when training load drops`() {
        // 35 days of hard training (strain 15), then 10 days of almost rest (strain 2)
        val records = mutableListOf<DailyMetricRecord>()
        for (i in 1..35) {
            records.add(makeRecord("2026-07-%02d".format(i), strain = 15.0, workoutMin = 60))
        }
        for (i in 1..10) {
            records.add(makeRecord("2026-08-%02d".format(i), strain = 2.0, workoutMin = 0))
        }

        val result = AdaptiveIntelligenceEngine.compute(records)
        assertNotNull(result)

        // ATL drops faster than CTL, so ratio < 0.8
        assertTrue("ACWR ratio (${result!!.atlCtlRatio}) should be < 0.8", result.atlCtlRatio < 0.8)
        assertEquals(AdaptiveIntelligenceEngine.LoadStatus.UNDERLOADED, result.loadStatus)
        // TSB should be positive (fresh / detraining)
        assertTrue("TSB (${result.tsb}) should be positive", result.tsb > 0)
    }

    @Test
    fun `classifies daily mover when no structured workouts exist`() {
        val records = (1..20).map { i ->
            makeRecord("2026-08-%02d".format(i), strain = 3.0, steps = 6000L, workoutMin = 0)
        }
        val result = AdaptiveIntelligenceEngine.compute(records)
        assertNotNull(result)
        assertFalse(result!!.isTrainingMode)
        assertEquals(AdaptiveIntelligenceEngine.LoadStatus.DAILY_MOVER, result.loadStatus)
    }
}
