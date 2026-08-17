package com.vanta.app

import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies recovery personalization:
 *  - the SAME strain can produce DIFFERENT recovery for different users,
 *  - it is driven only by each user's own objective history (strain + RHR),
 *  - it ramps in gradually and equals the base model for new users (factor 1.0).
 */
class PersonalizationTest {

    private fun recoveryFor(prevStrain: Double, factor: Double): Int {
        val strain = VantaDeterministicPhysiologyEngine.scoreStrain(prevStrain, factor)
        // Neutral time (80) + neutral history (80), no sleep (default no-RHR path).
        return VantaDeterministicPhysiologyEngine.combineRecovery(strain, 80.0, 80.0, null, null)
    }

    @Test
    fun `same strain but different users produce different recovery`() {
        // Identical objective inputs (strain 8.3, neutral time, neutral history).
        // Only the user-specific history factor differs.
        val sensitive = recoveryFor(8.3, 1.15)
        val base = recoveryFor(8.3, 1.0)
        val resilient = recoveryFor(8.3, 0.85)
        assertEquals(78, base)          // base model, identical for everyone
        assertEquals(76, sensitive)     // sensitive user recovers lower
        assertEquals(80, resilient)     // resilient user recovers higher
    }

    @Test
    fun `personal factor scales only the strain penalty not the baseline`() {
        val base = VantaDeterministicPhysiologyEngine.scoreStrain(8.3, 1.0)
        val sensitive = VantaDeterministicPhysiologyEngine.scoreStrain(8.3, 1.15)
        val resilient = VantaDeterministicPhysiologyEngine.scoreStrain(8.3, 0.85)
        assertTrue("sensitive score ${"%.2f".format(sensitive)} must be below base ${"%.2f".format(base)}", sensitive < base)
        assertTrue("resilient score ${"%.2f".format(resilient)} must be above base ${"%.2f".format(base)}", resilient > base)
        // No penalty at strain 0, so the factor must not affect rest days.
        assertEquals(100.0, VantaDeterministicPhysiologyEngine.scoreStrain(0.0, 1.15), 1e-9)
        assertEquals(100.0, VantaDeterministicPhysiologyEngine.scoreStrain(0.0, 0.85), 1e-9)
    }

    @Test
    fun `sensitive user whose RHR rises with strain gets factor above 1`() {
        val factor = VantaDeterministicPhysiologyEngine.personalStrainFactor(
            strains = listOf(8.0, 6.0, 5.0, 9.0),
            restingBpms = listOf(66.0, 63.0, 61.0, 68.0), // RHR tracks strain
            baselineRestingBpm = 60.0
        )
        assertTrue("expected factor > 1, got $factor", factor > 1.0)
    }

    @Test
    fun `resilient user whose RHR stays flat gets factor below 1`() {
        val factor = VantaDeterministicPhysiologyEngine.personalStrainFactor(
            strains = listOf(8.0, 6.0, 5.0, 9.0),
            restingBpms = listOf(58.0, 59.0, 61.0, 57.0), // RHR does not track strain
            baselineRestingBpm = 60.0
        )
        assertTrue("expected factor < 1, got $factor", factor < 1.0)
    }

    @Test
    fun `no personalization until enough training days exist`() {
        // Fewer than 3 training days → factor 1.0 (base algorithm).
        assertEquals(
            1.0,
            VantaDeterministicPhysiologyEngine.personalStrainFactor(
                strains = listOf(8.0, 6.0),
                restingBpms = listOf(66.0, 63.0),
                baselineRestingBpm = 60.0
            ),
            1e-9
        )
        // Constant strain (zero variance) → no slope → factor 1.0.
        assertEquals(
            1.0,
            VantaDeterministicPhysiologyEngine.personalStrainFactor(
                strains = listOf(8.0, 8.0, 8.0),
                restingBpms = listOf(66.0, 64.0, 65.0),
                baselineRestingBpm = 60.0
            ),
            1e-9
        )
    }

    @Test
    fun `personalization ramps in gradually with more training days`() {
        val strains = listOf(8.0, 6.0, 5.0, 9.0, 7.0, 6.5, 8.5, 5.5, 9.5, 7.5)
        val bpms = listOf(66.0, 63.0, 61.0, 68.0, 64.0, 62.0, 67.0, 61.0, 69.0, 65.0)
        val low = VantaDeterministicPhysiologyEngine.personalStrainFactor(
            strains = strains.take(4), restingBpms = bpms.take(4), baselineRestingBpm = 60.0
        )
        val full = VantaDeterministicPhysiologyEngine.personalStrainFactor(
            strains = strains, restingBpms = bpms, baselineRestingBpm = 60.0
        )
        assertTrue("10 days ($full) must personalize more than 4 days ($low)", full > low)
        assertTrue("factor must stay inside its clamp", full in 0.85..1.15)
    }
}
