package com.vanta.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for accessing and storing daily health telemetry and physiological scores in Room DB.
 */
@Dao
interface DailyMetricsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: DailyMetricRecord)

    @Query("SELECT * FROM daily_metrics ORDER BY date DESC")
    fun getAllRecordsFlow(): Flow<List<DailyMetricRecord>>

    @Query("SELECT * FROM daily_metrics ORDER BY date DESC")
    suspend fun getAllRecords(): List<DailyMetricRecord>

    @Query("SELECT * FROM daily_metrics ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int = 7): List<DailyMetricRecord>

    @Query("SELECT COUNT(*) FROM daily_metrics")
    suspend fun getRecordCount(): Int

    @Query("SELECT * FROM daily_metrics WHERE date = :date LIMIT 1")
    suspend fun getRecordForDate(date: String): DailyMetricRecord?

    @Query("DELETE FROM daily_metrics")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM daily_metrics WHERE date < :date")
    suspend fun deleteRecordsBefore(date: String)

    @Query("UPDATE daily_metrics SET calories = :calories WHERE date = :date")
    suspend fun updateCalories(date: String, calories: Long)

    /**
     * Fixes HR display fields for days with no valid HR samples: clears any stale
     * baseline-filled avg HR where the day had no HR at all (maxBpm == 0). Touches
     * ONLY the HR columns — strain/recovery/energy are never overwritten.
     */
    @Query("UPDATE daily_metrics SET avgBpm = 0 WHERE maxBpm = 0")
    suspend fun clearAvgHrWhereNoHr()

    /**
     * One-time data cleanup: nulls out legacy resting-HR values written before the
     * app stopped trusting RHR without sleep tracking. Old versions fabricated RHR
     * from daytime HR minima or a hardcoded 60, so those rows are untrustworthy.
     * Genuine overnight readings (gated by sleep tracking) repopulate going forward.
     */
    @Query("UPDATE daily_metrics SET restingBpm = 0")
    suspend fun clearLegacyRestingBpm()

    /**
     * Updates the calculated biological age for a specific day.
     */
    @Query("UPDATE daily_metrics SET biologicalAge = :age WHERE date = :date")
    suspend fun updateBioAge(date: String, age: Double)

    /**
     * Queries recent historical biological age entries to compute rolling 30-day / 90-day trend.
     */
    @Query("SELECT biologicalAge FROM daily_metrics WHERE biologicalAge > 0.0 ORDER BY date DESC LIMIT :limit")
    suspend fun getBioAgeHistory(limit: Int = 30): List<Double>
}
