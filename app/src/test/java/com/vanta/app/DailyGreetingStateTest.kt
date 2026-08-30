package com.vanta.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vanta.app.data.notification.DailyGreetingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates cross-mechanism greeting dedupe (DailyGreetingState):
 *  - each mechanism's reason collapses to one canonical daily slot,
 *  - non-greeting events never touch the flag,
 *  - claiming a slot once suppresses the other source for that day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyGreetingStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `morning mechanisms collapse to one morning slot`() {
        assertEquals("morning", DailyGreetingState.slotForReason("recovery"))
        assertEquals("morning", DailyGreetingState.slotForReason("morning"))
    }

    @Test
    fun `afternoon maps to its own slot`() {
        assertEquals("afternoon", DailyGreetingState.slotForReason("afternoon"))
    }

    @Test
    fun `evening and night share the wind-down slot`() {
        assertEquals("evening", DailyGreetingState.slotForReason("evening"))
        assertEquals("evening", DailyGreetingState.slotForReason("night"))
    }

    @Test
    fun `non-greeting events never dedupe on a greeting slot`() {
        assertEquals(null, DailyGreetingState.slotForReason("workout"))
        assertEquals(null, DailyGreetingState.slotForReason("achievement"))
        assertEquals(null, DailyGreetingState.slotForReason("weekly"))
        assertEquals(null, DailyGreetingState.slotForReason("strain"))
    }

    @Test
    fun `claiming a slot once suppresses the other source that day`() {
        val day = "2099-01-01"
        assertFalse(DailyGreetingState.alreadyGreeted(context, day, "recovery"))
        DailyGreetingState.markGreeted(context, day, "recovery")
        assertTrue(DailyGreetingState.alreadyGreeted(context, day, "morning"))

        // A different slot is independent.
        assertFalse(DailyGreetingState.alreadyGreeted(context, day, "afternoon"))

        // The slot resets on a fresh day.
        assertFalse(DailyGreetingState.alreadyGreeted(context, "2099-01-02", "recovery"))
    }
}
