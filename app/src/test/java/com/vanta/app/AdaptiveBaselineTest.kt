package com.vanta.app

import com.vanta.app.data.baseline.UserBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBaselineTest {

    @Test
    fun testLearningPhaseWhenDaysLessThan7() {
        val baseline = UserBaseline(
            savedDaysCount = 4,
            isLearningPhase = true,
            avgRestingBpm = 62.0,
            avgAvgBpm = 75.0,
            avgMaxBpm = 160.0,
            avgSteps = 8500.0,
            avgCalories = 500.0,
            avgDistanceKm = 6.0,
            avgWorkoutDurationMin = 45.0,
            avgStrain = 40.0
        )

        assertTrue(baseline.isLearningPhase)
        assertEquals(4, baseline.savedDaysCount)
        assertTrue(baseline.subtleStatusMessage.contains("Building your personalized baseline"))
        assertTrue(baseline.phaseLabel.contains("Learning Phase"))
    }

    @Test
    fun testPersonalizedPhaseWhenDays7OrMore() {
        val baseline = UserBaseline(
            savedDaysCount = 7,
            isLearningPhase = false,
            avgRestingBpm = 58.0,
            avgAvgBpm = 72.0,
            avgMaxBpm = 165.0,
            avgSteps = 10200.0,
            avgCalories = 620.0,
            avgDistanceKm = 7.5,
            avgWorkoutDurationMin = 50.0,
            avgStrain = 48.0
        )

        assertFalse(baseline.isLearningPhase)
        assertEquals(7, baseline.savedDaysCount)
        assertTrue(baseline.subtleStatusMessage.contains("Baseline active"))
        assertTrue(baseline.phaseLabel.contains("Personalized Baseline"))
    }
}
