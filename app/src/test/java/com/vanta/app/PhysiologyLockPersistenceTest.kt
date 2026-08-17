package com.vanta.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import com.vanta.app.data.baseline.UserBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Regression tests for the per-day lock integrity in the physiology engine:
 * rolling over / backfilling past dates must NEVER wipe today's locked
 * Recovery / Strain / Energy — those locks are the monotonicity guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhysiologyLockPersistenceTest {

    private lateinit var engine: VantaDeterministicPhysiologyEngine
    private lateinit var prefs: android.content.SharedPreferences
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("vanta_physiology_baseline", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        engine = VantaDeterministicPhysiologyEngine(context)
        engine.userAge = 30
    }

    private fun telemetry(steps: Long, restingBpm: Int = 0, sleepMinutes: Int = 0) =
        HealthConnectTelemetry(
            steps = steps,
            restingBpm = restingBpm,
            sleepMinutes = sleepMinutes
        )

    @Test
    fun `archiving past dates does not wipe today's locked recovery or energy`() {
        // Hard day → locks today's recovery + energy floor.
        val todayResult = engine.calculatePhysiology(telemetry(steps = 15000), UserBaseline.Default, today)
        val lockedRecovery = prefs.getInt("locked_recovery_score", -1)
        val lockedEnergy = prefs.getInt("today_min_energy", -1)
        assertNotEquals(-1, lockedRecovery)
        assertNotEquals(-1, lockedEnergy)

        // Backfill / rollover: compute a run of PAST dates.
        for (d in 1..7) {
            engine.calculatePhysiology(telemetry(steps = 2000), UserBaseline.Default, today.minusDays(d.toLong()))
        }

        // Today's locks must survive untouched.
        assertEquals("recovery lock must survive backfill", lockedRecovery, prefs.getInt("locked_recovery_score", -2))
        assertEquals("energy floor must survive backfill", lockedEnergy, prefs.getInt("today_min_energy", -2))
        assertEquals(today.toString(), prefs.getString("locked_recovery_date", ""))
    }

    @Test
    fun `today's recovery lock is honored across repeated calls`() {
        val first = engine.calculatePhysiology(telemetry(steps = 15000), UserBaseline.Default, today)
        val locked = prefs.getInt("locked_recovery_score", -1)

        // Recompute with MORE data later in the day — recovery must not change.
        val second = engine.calculatePhysiology(telemetry(steps = 15000, restingBpm = 95, sleepMinutes = 200), UserBaseline.Default, today)
        assertEquals(locked, prefs.getInt("locked_recovery_score", -2))
        assertEquals(first.recovery, second.recovery)

        // Energy must never increase within the same day.
        assertTrue("energy must stay monotonic", second.energy <= first.energy)
    }
}
