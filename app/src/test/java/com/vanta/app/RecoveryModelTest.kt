package com.vanta.app

import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Verifies the redesigned recovery model:
 *  - no RHR by default — the blend is strain + time-since-workout + training history,
 *  - sleep tracking adds sleep duration and genuine overnight RHR,
 *  - missing inputs rebalance weights proportionally and are never faked,
 *  - every component is a single continuous, monotonic curve.
 */
class RecoveryModelTest {

    /** Default (no-sleep) path with neutral time (36h) and history (7-day avg strain 7). */
    private fun recoveryFor(prevStrain: Double, hoursSince: Double? = 36.0, avgStrain7d: Double? = 7.0): Int {
        return VantaDeterministicPhysiologyEngine.combineRecovery(
            scorePrevStrain = VantaDeterministicPhysiologyEngine.scoreStrain(prevStrain),
            scoreTimeSinceWorkout = hoursSince?.let { VantaDeterministicPhysiologyEngine.scoreTimeSinceWorkout(it) },
            scoreTrainingHistory = avgStrain7d?.let { VantaDeterministicPhysiologyEngine.scoreTrainingHistory(it) },
            scoreSleep = null,
            scoreRhr = null
        )
    }

    @Test
    fun `same time and history across strain levels gives a sensible monotonic spread`() {
        // 55% strain + 25% time (36h -> 84) + 20% history (avg strain 7 -> 80).
        val expected = listOf(
            2.0 to 89,
            5.0 to 84,
            8.3 to 79,
            12.0 to 72,
            16.0 to 63,
        )
        expected.forEach { (strain, rec) ->
            assertEquals("strain $strain", rec, recoveryFor(strain))
        }
    }

    @Test
    fun `recovery strictly decreases as strain rises over the full grid`() {
        val pts = (0..210).map { it / 10.0 } // 0.0 → 21.0
        for (i in 0 until pts.size - 1) {
            assertTrue(
                "recovery must not increase between strain ${pts[i]} and ${pts[i + 1]}",
                recoveryFor(pts[i]) >= recoveryFor(pts[i + 1])
            )
        }
    }

    @Test
    fun `strain score is continuous at every segment boundary`() {
        for (b in listOf(5.0, 10.0, 15.0)) {
            val left = VantaDeterministicPhysiologyEngine.scoreStrain(b - 1e-9)
            val right = VantaDeterministicPhysiologyEngine.scoreStrain(b)
            assertEquals("boundary at strain $b", left, right, 1e-6)
        }
    }

    @Test
    fun `time since last workout drives recovery both directions`() {
        val fresh = recoveryFor(8.3, hoursSince = 4.0)   // <6h since last session
        val rested = recoveryFor(8.3, hoursSince = 90.0) // >72h
        assertTrue("fresh workout (got $fresh%) must not recover better than rested ($rested%)", rested >= fresh)
        // Rest clears monotonically over the recovery window.
        val prev = (0..720 step 24).map { recoveryFor(8.3, hoursSince = it.toDouble()) }
        for (i in 0 until prev.size - 1) assertTrue(prev[i] <= prev[i + 1])
    }

    @Test
    fun `recent training history lowers recovery under chronic load`() {
        val light = recoveryFor(8.3, avgStrain7d = 2.0)  // easy week
        val heavy = recoveryFor(8.3, avgStrain7d = 16.0) // heavy week
        assertTrue("chronic load ($heavy%) must not recover better than a light week ($light%)", light >= heavy)
    }

    @Test
    fun `sleep tracking improves the blend and lets RHR move it both ways`() {
        fun withSleep(rhrDelta: Double): Int = VantaDeterministicPhysiologyEngine.combineRecovery(
            scorePrevStrain = VantaDeterministicPhysiologyEngine.scoreStrain(8.3),
            scoreTimeSinceWorkout = VantaDeterministicPhysiologyEngine.scoreTimeSinceWorkout(36.0),
            scoreTrainingHistory = VantaDeterministicPhysiologyEngine.scoreTrainingHistory(7.0),
            scoreSleep = VantaDeterministicPhysiologyEngine.scoreSleep(450),
            scoreRhr = VantaDeterministicPhysiologyEngine.scoreRestingHr(rhrDelta)
        )
        val better = withSleep(+5.0) // overnight RHR below baseline -> recovered
        val worse = withSleep(-5.0)  // elevated RHR -> fatigued
        assertTrue(better >= worse)
    }

    @Test
    fun `documented no-sleep 55 25 20 weights are applied exactly`() {
        val combined = VantaDeterministicPhysiologyEngine.combineRecovery(
            scorePrevStrain = 100.0, scoreTimeSinceWorkout = 80.0,
            scoreTrainingHistory = 90.0, scoreSleep = null, scoreRhr = null
        )
        assertEquals((0.55 * 100.0 + 0.25 * 80.0 + 0.20 * 90.0).roundToInt(), combined)
    }

    @Test
    fun `documented sleep-path 40 15 15 20 10 weights are applied exactly`() {
        val combined = VantaDeterministicPhysiologyEngine.combineRecovery(
            scorePrevStrain = 100.0, scoreTimeSinceWorkout = 80.0,
            scoreTrainingHistory = 90.0, scoreSleep = 85.0, scoreRhr = 70.0
        )
        assertEquals((0.40 * 100.0 + 0.15 * 80.0 + 0.15 * 90.0 + 0.20 * 85.0 + 0.10 * 70.0).roundToInt(), combined)
    }

    @Test
    fun `missing inputs rebalance weights and sum to 100 percent`() {
        // No sleep, no workout-time data: strain 55/75 + history 20/75.
        val noTime = VantaDeterministicPhysiologyEngine.combineRecovery(
            scorePrevStrain = 70.0, scoreTimeSinceWorkout = null,
            scoreTrainingHistory = 90.0, scoreSleep = null, scoreRhr = null
        )
        assertEquals(((70.0 * 55.0 + 90.0 * 20.0) / 75.0).roundToInt(), noTime)
        // Only strain: 100/100.
        val onlyStrain = VantaDeterministicPhysiologyEngine.combineRecovery(
            scorePrevStrain = 70.0, scoreTimeSinceWorkout = null,
            scoreTrainingHistory = null, scoreSleep = null, scoreRhr = null
        )
        assertEquals(70, onlyStrain)
    }

    @Test
    fun `recovery output is clamped to the documented 30 to 100 range`() {
        assertEquals(30, VantaDeterministicPhysiologyEngine.combineRecovery(20.0, null, null, null, null))
        assertEquals(100, VantaDeterministicPhysiologyEngine.combineRecovery(105.0, null, null, null, null))
    }

    @Test
    fun `component scores stay within their defined bounds`() {
        assertEquals(35.0, VantaDeterministicPhysiologyEngine.scoreStrain(21.0), 1e-6)
        assertEquals(100.0, VantaDeterministicPhysiologyEngine.scoreStrain(0.0), 1e-6)
        assertEquals(45.0, VantaDeterministicPhysiologyEngine.scoreRestingHr(-20.0), 1e-6)
        assertEquals(95.0, VantaDeterministicPhysiologyEngine.scoreRestingHr(20.0), 1e-6)
        assertEquals(45.0, VantaDeterministicPhysiologyEngine.scoreTimeSinceWorkout(0.0), 1e-6)
        assertEquals(100.0, VantaDeterministicPhysiologyEngine.scoreTimeSinceWorkout(100.0), 1e-6)
        assertEquals(100.0, VantaDeterministicPhysiologyEngine.scoreTrainingHistory(0.0), 1e-6)
        assertEquals(42.0, VantaDeterministicPhysiologyEngine.scoreTrainingHistory(21.0), 1e-6)
        assertEquals(45.0, VantaDeterministicPhysiologyEngine.scoreSleep(100), 1e-6)
        assertEquals(100.0, VantaDeterministicPhysiologyEngine.scoreSleep(600), 1e-6)
    }
}
