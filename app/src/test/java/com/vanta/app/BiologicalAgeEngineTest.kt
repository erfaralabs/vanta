package com.vanta.app

import com.vanta.app.data.intelligence.BiologicalAgeEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Validates the Fitness Age trend-comparison window selection: prefer a 30-day
 * baseline, fall back to the last 15 days, and disable the comparison entirely
 * when neither is available.
 */
class BiologicalAgeEngineTest {

    @Test
    fun `uses 30-day window when exactly 30 days of history exist`() {
        assertEquals(30, BiologicalAgeEngine.trendWindowDays(30))
    }

    @Test
    fun `uses 30-day window when more than 30 days exist`() {
        assertEquals(30, BiologicalAgeEngine.trendWindowDays(45))
    }

    @Test
    fun `falls back to 15-day window when 15 to 29 days exist`() {
        assertEquals(15, BiologicalAgeEngine.trendWindowDays(15))
        assertEquals(15, BiologicalAgeEngine.trendWindowDays(16))
        assertEquals(15, BiologicalAgeEngine.trendWindowDays(29))
    }

    @Test
    fun `disables comparison when fewer than 15 days exist`() {
        assertEquals(0, BiologicalAgeEngine.trendWindowDays(14))
        assertEquals(0, BiologicalAgeEngine.trendWindowDays(1))
        assertEquals(0, BiologicalAgeEngine.trendWindowDays(0))
    }
}
