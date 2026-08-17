package com.vanta.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Main Room Database instance for Vanta.
 * Stores historical daily metrics and user profiles on-device.
 */
@Database(
    entities = [DailyMetricRecord::class, UserProfileRecord::class],
    version = 7,
    exportSchema = false
)
abstract class VantaDatabase : RoomDatabase() {
    abstract fun dailyMetricsDao(): DailyMetricsDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile
        private var INSTANCE: VantaDatabase? = null

        /** v4 → v5: adds the user-configurable daily steps goal column. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN stepsGoal INTEGER NOT NULL DEFAULT 10000")
            }
        }

        /** v5 → v6: adds the avatar selection column. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN avatarKey TEXT NOT NULL DEFAULT 'avatar1'")
            }
        }

        /** v6 → v7: adds the biologicalAge column to daily_metrics table. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_metrics ADD COLUMN biologicalAge REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun getInstance(context: Context): VantaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VantaDatabase::class.java,
                    "vanta_database.db"
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(false).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
