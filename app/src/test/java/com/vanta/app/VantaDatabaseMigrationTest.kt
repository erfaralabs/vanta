package com.vanta.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.vanta.app.data.db.VantaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates every Room migration (v4 → v7) against the exported schema:
 *  - old data survives the migration untouched,
 *  - new columns are added with the correct defaults,
 *  - the final schema matches what Room expects for the current entities.
 * Runs on the JVM via Robolectric (no device/emulator needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VantaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VantaDatabase::class.java
    )

    /**
     * Creates a real v4 database file by hand. The v4 schema was never exported
     * (schema export started at v7), so MigrationTestHelper can't create it for us;
     * we reproduce it with raw SQL and stamp PRAGMA user_version = 4.
     */
    private fun createV4Database(name: String): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_profile` (" +
                "`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `birthdateStr` TEXT NOT NULL, " +
                "`age` INTEGER NOT NULL, `heightCm` REAL NOT NULL, `weightKg` REAL NOT NULL, " +
                "`sex` TEXT NOT NULL, `fitnessGoal` TEXT NOT NULL, " +
                "`isOnboardingCompleted` INTEGER NOT NULL, `createdAtTimestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_metrics` (" +
                "`date` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `restingBpm` INTEGER NOT NULL, " +
                "`avgBpm` INTEGER NOT NULL, `maxBpm` INTEGER NOT NULL, `steps` INTEGER NOT NULL, " +
                "`calories` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, " +
                "`workoutDurationMin` INTEGER NOT NULL, `strain` REAL NOT NULL, " +
                "`recovery` INTEGER NOT NULL, `energy` INTEGER NOT NULL, " +
                "PRIMARY KEY(`date`))"
        )
        db.execSQL(
            "INSERT INTO user_profile (id, name, birthdateStr, age, heightCm, weightKg, sex, " +
                "fitnessGoal, isOnboardingCompleted, createdAtTimestamp) VALUES " +
                "(1, 'Tester', '1995-05-05', 30, 178.0, 75.0, 'Male', 'General Fitness', 1, 1234567890)"
        )
        db.execSQL(
            "INSERT INTO daily_metrics (date, timestamp, restingBpm, avgBpm, maxBpm, steps, " +
                "calories, distanceKm, workoutDurationMin, strain, recovery, energy) VALUES " +
                "('2026-08-10', 1000, 58, 68, 142, 8420, 520, 8.42, 45, 12.4, 74, 68)"
        )
        db.execSQL("PRAGMA user_version = 4")
        db.close()
        return name
    }

    @Test
    fun migrateV4ToV7_preservesDataAndAddsColumns() {
        val migrated = helper.runMigrationsAndValidate(
            createV4Database("migration-test-db"),
            7,
            true,
            VantaDatabase.MIGRATION_4_5,
            VantaDatabase.MIGRATION_5_6,
            VantaDatabase.MIGRATION_6_7
        )

        // Profile row survived; new columns exist with correct defaults.
        migrated.query("SELECT name, stepsGoal, avatarKey FROM user_profile WHERE id = 1").use { c ->
            assertTrue("profile row must survive migration", c.moveToFirst())
            assertEquals("Tester", c.getString(0))
            assertEquals(10000, c.getInt(1))
            assertEquals("avatar1", c.getString(2))
        }

        // Daily metrics row survived; biologicalAge defaults to 0.0.
        migrated.query("SELECT date, strain, recovery, biologicalAge FROM daily_metrics").use { c ->
            assertTrue("metrics row must survive migration", c.moveToFirst())
            assertEquals("2026-08-10", c.getString(0))
            assertEquals(12.4, c.getDouble(1), 0.001)
            assertEquals(74, c.getInt(2))
            assertEquals(0.0, c.getDouble(3), 0.001)
        }

        migrated.close()
    }
}

