package com.vanta.app.data.worker

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import com.vanta.app.data.baseline.AdaptiveBaselineManager
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.VantaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Manages daily midnight rollover, Room DB archiving, metric resetting, and WorkManager scheduling.
 */
class DailyRolloverManager private constructor(private val context: Context) {

    private val db = VantaDatabase.getInstance(context)
    private val dao = db.dailyMetricsDao()
    private val baselineManager = AdaptiveBaselineManager(context)
    private val physiologyEngine = VantaDeterministicPhysiologyEngine(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("vanta_rollover_prefs", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var INSTANCE: DailyRolloverManager? = null

        fun getInstance(context: Context): DailyRolloverManager {
            return INSTANCE ?: synchronized(this) {
                val instance = DailyRolloverManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private var lastRolloverDate: String?
        get() = prefs.getString("last_rollover_date", null)
        set(value) = prefs.edit().putString("last_rollover_date", value).apply()

    /**
     * Checks if local midnight has passed since last archiving and performs rollover if needed.
     */
    suspend fun checkAndPerformRolloverIfNeeded() = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val todayStr = today.toString()
        val lastDate = lastRolloverDate

        // Automatically sync age based on user's birthdate
        val profile = db.userProfileDao().getUserProfile()
        if (profile != null) {
            physiologyEngine.userAge = profile.calculatedAge
        }

        if (lastDate == null) {
            // First run, mark previous day as baseline start if no record exists
            lastRolloverDate = today.minusDays(1).toString()
            return@withContext
        }

        val lastLocalDate = LocalDate.parse(lastDate)
        if (today.isAfter(lastLocalDate)) {
            // Archive missed days oldest-first, chaining each day's FINAL strain so
            // every archived day's Recovery reflects its real previous-day load
            // (not one stale global pref reused across all missed days).
            var cur = lastLocalDate
            while (cur.isBefore(today)) {
                val dateStr = cur.toString()
                if (dao.getRecordForDate(dateStr) == null) {
                    archiveDayRecord(dateStr)
                }
                persistYesterdayStrain(dateStr)
                cur = cur.plusDays(1)
            }
            lastRolloverDate = today.minusDays(1).toString()
            resetDailyMetrics()
            persistYesterdayStrain(today.minusDays(1).toString())
        }
    }

    /**
     * Executes midnight rollover for the completed day.
     */
    suspend fun performMidnightRollover() = withContext(Dispatchers.IO) {
        val yesterdayStr = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()
        // Preserve yesterday's FINAL strain already persisted during the day; only
        // archive from telemetry if no record exists yet (e.g., app closed all day).
        if (dao.getRecordForDate(yesterdayStr) == null) {
            archiveDayRecord(yesterdayStr)
        }
        lastRolloverDate = yesterdayStr
        resetDailyMetrics()
        persistYesterdayStrain(yesterdayStr)
    }

    /**
     * Archives metrics for a specific date into Room DB.
     */
    suspend fun archiveDayRecord(dateStr: String) = withContext(Dispatchers.IO) {
        val manager = HealthConnectManager(context)
        val profile = db.userProfileDao().getUserProfile()
        val daysAgo = try {
            val date = LocalDate.parse(dateStr)
            val today = LocalDate.now(ZoneId.systemDefault())
            java.time.temporal.ChronoUnit.DAYS.between(date, today)
        } catch (e: Exception) { 1L }

        val telemetry = manager.fetchPastDayData(daysAgo.coerceAtLeast(0L), profile)
        // Never archive a day with zero real telemetry (app closed all day, no
        // watch) — an empty record would fake a training day and inflate the
        // baseline counter. Only days with genuine data enter Room DB.
        val hasRealData = telemetry.steps > 0 || telemetry.calories > 0 ||
            telemetry.avgBpm > 0 || telemetry.peakBpm > 0 ||
            telemetry.distanceKm > 0 || telemetry.exerciseMinutes > 0
        if (!hasRealData) return@withContext

        val baseline = baselineManager.getCurrentBaseline()
        val targetDate = try { LocalDate.parse(dateStr) } catch (_: Exception) { LocalDate.now(ZoneId.systemDefault()) }
        val physResult = physiologyEngine.calculatePhysiology(telemetry, baseline, targetDate)

        val record = DailyMetricRecord.fromPhysiology(
            date = dateStr,
            timestamp = System.currentTimeMillis(),
            restingBpm = physResult.rhrToday,
            avgBpm = physResult.avgHrToday,
            maxBpm = telemetry.peakBpm,
            steps = telemetry.steps,
            calories = telemetry.calories,
            distanceKm = telemetry.distanceKm,
            workoutDurationMin = telemetry.exerciseMinutes,
            phys = physResult
        )

        dao.insertOrUpdate(record)
    }

    /**
     * Resets in-memory daily caches while preserving historical Room DB records & baselines.
     */
    fun resetDailyMetrics() {
        HealthConnectManager.clearCache()
        physiologyEngine.resetTodayStrainAndEnergy()
    }

    /**
     * Persists the FINAL strain of the completed date into the physiology engine's
     * `yesterday_strain` pref so next-day Recovery always uses the actual previous-day strain.
     */
    private suspend fun persistYesterdayStrain(dateStr: String) {
        val record = dao.getRecordForDate(dateStr)
        if (record != null) {
            physiologyEngine.persistYesterdayStrain(record.strain)
        }
    }

    /**
     * Schedules the periodic background telemetry sync (15 min, the WorkManager
     * minimum). Persistent across reboots/app restarts; keeps the dashboard live.
     */
    fun schedulePeriodicTelemetrySync() {
        val request = androidx.work.PeriodicWorkRequestBuilder<PeriodicTelemetrySyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "vanta_periodic_telemetry_sync",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Schedules next local midnight WorkManager trigger.
     */
    fun scheduleMidnightWork() {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val nextMidnight = LocalDateTime.of(LocalDate.now(ZoneId.systemDefault()).plusDays(1), LocalTime.MIDNIGHT)
        val delaySeconds = Duration.between(now, nextMidnight).seconds.coerceAtLeast(60)

        val workRequest = OneTimeWorkRequestBuilder<MidnightRolloverWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "vanta_midnight_rollover",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Recomputes every stored daily record through the CURRENT deterministic engine
     * formula (used once after the strain model changes so history reflects the new
     * intensity-aware math instead of stale values). Inputs come from the records'
     * own stored telemetry — nothing is re-fetched or fabricated. Today's locks are
     * cleared first so recovery re-derives from the recomputed previous-day strain.
     */
    suspend fun recomputeStoredRecords() = withContext(Dispatchers.IO) {
        resetDailyMetrics()
        for (r in dao.getAllRecords().sortedBy { it.date }) {
            val t = HealthConnectTelemetry(
                steps = r.steps,
                calories = r.calories,
                distanceKm = r.distanceKm,
                currentBpm = r.avgBpm,
                avgBpm = r.avgBpm,
                peakBpm = r.maxBpm,
                restingBpm = r.restingBpm,
                exerciseMinutes = r.workoutDurationMin
            )
            val targetDate = try { LocalDate.parse(r.date) } catch (_: Exception) { LocalDate.now(ZoneId.systemDefault()) }
            val phys = physiologyEngine.calculatePhysiology(t, baselineManager.getCurrentBaseline(), targetDate)
            dao.insertOrUpdate(
                DailyMetricRecord.fromPhysiology(
                    date = r.date,
                    timestamp = r.timestamp,
                    restingBpm = phys.rhrToday,
                    avgBpm = phys.avgHrToday,
                    maxBpm = r.maxBpm,
                    steps = r.steps,
                    calories = r.calories,
                    distanceKm = r.distanceKm,
                    workoutDurationMin = r.workoutDurationMin,
                    phys = phys
                )
            )
            physiologyEngine.persistYesterdayStrain(phys.strain)
        }
        // Anchor today's recovery to the REAL previous-day strain.
        val ydayStrain = dao.getRecordForDate(LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString())?.strain
        if (ydayStrain != null) physiologyEngine.persistYesterdayStrain(ydayStrain)
    }

    /**
     * Refills past-day calories from Health Connect (real aggregate when present,
     * otherwise the documented distance+HR estimate). Runs once to repair records
     * that stored 0 kcal because active-calorie aggregation was unavailable.
     */
    suspend fun refreshHistoricalCalories() = withContext(Dispatchers.IO) {
        val manager = HealthConnectManager(context)
        val today = LocalDate.now(ZoneId.systemDefault())
        for (r in dao.getAllRecords()) {
            val daysAgo = try {
                java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(r.date), today)
            } catch (e: Exception) {
                continue
            }
            if (daysAgo <= 0) continue // today's calories come from the live analysis
            val t = manager.fetchPastDayData(daysAgo, db.userProfileDao().getUserProfile())
            if (t.calories > 0) dao.updateCalories(r.date, t.calories)
        }
    }

    /**
     * Re-fetches every stored past-day record from Health Connect with source-overlap
     * deduplication (phone + watch no longer double-counted) and recomputes the
     * physiology. Keeps the same dates — no days are added or removed.
     */
    suspend fun refreshHistoricalRecordsFromHealthConnect() = withContext(Dispatchers.IO) {
        resetDailyMetrics()
        val manager = HealthConnectManager(context)
        val today = LocalDate.now(ZoneId.systemDefault())
        for (r in dao.getAllRecords().sortedBy { it.date }) {
            val daysAgo = try {
                java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(r.date), today)
            } catch (e: Exception) {
                continue
            }
            if (daysAgo <= 0) continue // today's record comes from the live analysis
            val t = manager.fetchPastDayData(daysAgo, db.userProfileDao().getUserProfile())
            if (t.steps <= 0 && t.avgBpm <= 0 && t.calories <= 0 && t.exerciseMinutes <= 0) continue
            val targetDate = try { LocalDate.parse(r.date) } catch (_: Exception) { LocalDate.now(ZoneId.systemDefault()) }
            val phys = physiologyEngine.calculatePhysiology(t, baselineManager.getCurrentBaseline(), targetDate)
            dao.insertOrUpdate(
                DailyMetricRecord.fromPhysiology(
                    date = r.date,
                    timestamp = r.timestamp,
                    restingBpm = if (t.restingBpm in 40..100) t.restingBpm else 0,
                    avgBpm = t.avgBpm,
                    maxBpm = t.peakBpm,
                    steps = t.steps,
                    calories = t.calories,
                    distanceKm = t.distanceKm,
                    workoutDurationMin = t.exerciseMinutes,
                    phys = phys
                )
            )
            physiologyEngine.persistYesterdayStrain(phys.strain)
        }
        val ydayStrain = dao.getRecordForDate(LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString())?.strain
        if (ydayStrain != null) physiologyEngine.persistYesterdayStrain(ydayStrain)
    }

    /**
     * Rebuilds the 7-day history from REAL Health Connect past-day telemetry so the
     * baseline day counter and the AI coach reflect genuine measurements only. Days
     * with no Health Connect data are skipped — never fabricated. Runs once after a
     * data cleanup that removed simulated records.
     */
    suspend fun rebuildHistoryFromHealthConnect() = withContext(Dispatchers.IO) {
        val manager = HealthConnectManager(context)
        val today = LocalDate.now(ZoneId.systemDefault())
        // Oldest → newest so each day's Recovery reads the previous day's real FINAL
        // strain (persisted right after insert) instead of one stale default.
        for (daysAgo in 7L downTo 1L) {
            val dateStr = today.minusDays(daysAgo).toString()
            val t = manager.fetchPastDayData(daysAgo)
            val hasData = t.steps > 0 || t.avgBpm > 0 || t.calories > 0 || t.exerciseMinutes > 0
            if (!hasData) continue // no real telemetry that day — skip, never fabricate
            val baseline = baselineManager.getCurrentBaseline()
            val phys = physiologyEngine.calculatePhysiology(t, baseline, today.minusDays(daysAgo))
            dao.insertOrUpdate(
                DailyMetricRecord.fromPhysiology(
                    date = dateStr,
                    timestamp = today.minusDays(daysAgo)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    restingBpm = if (t.restingBpm in 40..100) t.restingBpm else 0,
                    avgBpm = t.avgBpm,
                    maxBpm = t.peakBpm,
                    steps = t.steps,
                    calories = t.calories,
                    distanceKm = t.distanceKm,
                    workoutDurationMin = t.exerciseMinutes,
                    phys = phys
                )
            )
            physiologyEngine.persistYesterdayStrain(phys.strain)
        }
        // Anchor today's recovery to the REAL previous-day strain (when available).
        val ydayStrain = dao.getRecordForDate(today.minusDays(1).toString())?.strain
        if (ydayStrain != null) physiologyEngine.persistYesterdayStrain(ydayStrain)
    }

    /**
     * Fills any MISSING past-day records from Health Connect without touching
     * existing archived days. Health Connect data often syncs hours/days late —
     * a day archived with no data (or not yet synced) would otherwise be missing
     * from the baseline forever. Runs once per day on app open (idempotent).
     */
    suspend fun fillMissingHistoryFromHealthConnect() = withContext(Dispatchers.IO) {
        val manager = HealthConnectManager(context)
        val today = LocalDate.now(ZoneId.systemDefault())
        for (daysAgo in 7L downTo 1L) {
            val date = today.minusDays(daysAgo)
            val dateStr = date.toString()
            if (dao.getRecordForDate(dateStr) != null) continue // already archived
            val t = manager.fetchPastDayData(daysAgo)
            val hasData = t.steps > 0 || t.avgBpm > 0 || t.calories > 0 ||
                t.distanceKm > 0 || t.exerciseMinutes > 0
            if (!hasData) continue // never fabricate an empty day
            val baseline = baselineManager.getCurrentBaseline()
            val phys = physiologyEngine.calculatePhysiology(t, baseline, date)
            dao.insertOrUpdate(
                DailyMetricRecord.fromPhysiology(
                    date = dateStr,
                    timestamp = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    restingBpm = if (t.restingBpm in 40..100) t.restingBpm else 0,
                    avgBpm = t.avgBpm,
                    maxBpm = t.peakBpm,
                    steps = t.steps,
                    calories = t.calories,
                    distanceKm = t.distanceKm,
                    workoutDurationMin = t.exerciseMinutes,
                    phys = phys
                )
            )
            physiologyEngine.persistYesterdayStrain(phys.strain)
        }
    }

    /**
     * Developer/Evaluator utility to simulate midnight rollover and test 7-day adaptive progression.
     */
    suspend fun simulateNextDayRollover(simulatedTelemetry: HealthConnectTelemetry? = null): DailyMetricRecord = withContext(Dispatchers.IO) {
        val count = dao.getRecordCount()
        val dateStr = LocalDate.now(ZoneId.systemDefault()).minusDays(count.toLong()).toString()
        val dateKey = if (dao.getRecordForDate(dateStr) != null) {
            LocalDate.now(ZoneId.systemDefault()).minusDays((count + 1).toLong()).toString()
        } else dateStr

        val manager = HealthConnectManager(context)
        val t = simulatedTelemetry ?: manager.readTodayTelemetry()

        // Generate plausible sample data if live telemetry is zero
        val finalSteps = if (t.steps > 0) t.steps else (7500..12500).random().toLong()
        val finalCals = if (t.calories > 0) t.calories else (380..720).random().toLong()
        val finalDist = if (t.distanceKm > 0) t.distanceKm else ((finalSteps * 0.00075 * 10).toInt() / 10.0)
        val finalRhr = if (t.restingBpm in 40..100) t.restingBpm else (54..68).random()
        val finalAvgHr = if (t.avgBpm in 50..160) t.avgBpm else (70..85).random()
        val finalMaxHr = if (t.peakBpm > 100) t.peakBpm else (145..175).random()
        val finalDuration = (35..75).random()

        val activeTelemetry = HealthConnectTelemetry(
            steps = finalSteps,
            calories = finalCals,
            distanceKm = finalDist,
            currentBpm = finalAvgHr,
            avgBpm = finalAvgHr,
            peakBpm = finalMaxHr,
            restingBpm = finalRhr,
            spo2Percent = 98.5,
            bodyTempCelsius = 36.6,
            exerciseMinutes = finalDuration
        )

        val currentBaseline = baselineManager.getCurrentBaseline()
        val physResult = physiologyEngine.calculatePhysiology(activeTelemetry, currentBaseline)

        val record = DailyMetricRecord.fromPhysiology(
            date = dateKey,
            timestamp = System.currentTimeMillis(),
            restingBpm = finalRhr,
            avgBpm = finalAvgHr,
            maxBpm = finalMaxHr,
            steps = finalSteps,
            calories = finalCals,
            distanceKm = finalDist,
            workoutDurationMin = finalDuration,
            phys = physResult
        )

        dao.insertOrUpdate(record)
        resetDailyMetrics()
        record
    }

    /**
     * Clears all database records to reset system to Day 0 for testing.
     */
    suspend fun resetAllHistoricalData() = withContext(Dispatchers.IO) {
        dao.deleteAllRecords()
        db.userProfileDao().deleteProfile()
        lastRolloverDate = null
        resetDailyMetrics()
    }

    /**
     * Developer helper: Seeds a pre-configured profile into Room DB and populates past 3 days of telemetry.
     */
    suspend fun seedDevProfileAndPast3DaysData() = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneId.systemDefault())

        val existingProfile = db.userProfileDao().getUserProfile()
        val profile = existingProfile ?: com.vanta.app.data.db.UserProfileRecord(
            id = 1,
            name = "Athlete",
            birthdateStr = "2000-01-01",
            age = 26,
            heightCm = 178.0,
            weightKg = 75.0,
            sex = "Male",
            fitnessGoal = "Build Muscle",
            isOnboardingCompleted = true,
            createdAtTimestamp = System.currentTimeMillis()
        ).also { db.userProfileDao().insertOrUpdateProfile(it) }
        physiologyEngine.userAge = profile.calculatedAge

        // Keep the steps goal in prefs for the non-suspend readers.
        context.getSharedPreferences("vanta_settings", android.content.Context.MODE_PRIVATE)
            .edit().putInt("steps_goal", profile.stepsGoal.coerceIn(1000, 100000)).apply()

        // Simulated telemetry (calibrated so the engine computes the target strains).
        // Iterated OLDEST first so each day's Recovery reads the ACTUAL previous day's
        // strain (persisted right after insert) instead of one stale global value.
        val simulatedDays = listOf(
            3L to HealthConnectTelemetry(
                steps = 1500, calories = 320, distanceKm = 1.5, currentBpm = 62,
                avgBpm = 85, peakBpm = 130, restingBpm = 60, exerciseMinutes = 5
            ), // light         -> Strain ~3.5
            2L to HealthConnectTelemetry(
                steps = 3000, calories = 380, distanceKm = 3.0, currentBpm = 68,
                avgBpm = 95, peakBpm = 145, restingBpm = 60, exerciseMinutes = 25
            ), // moderate      -> Strain ~5.5
            1L to HealthConnectTelemetry(
                steps = 4500, calories = 600, distanceKm = 4.5, currentBpm = 75,
                avgBpm = 108, peakBpm = 165, restingBpm = 60, exerciseMinutes = 55
            )  // hard leg day  -> Strain ~8.3
        )

        // The day before the seeded window drives the oldest day's Recovery. Fall
        // back to the engine's default baseline strain (3.5) when no record exists.
        val chainStart = dao.getRecordForDate(today.minusDays(3).toString())?.strain ?: 3.5
        physiologyEngine.persistYesterdayStrain(chainStart)

        for ((daysAgo, dayTelemetry) in simulatedDays) {
            val dateStr = today.minusDays(daysAgo).toString()
            // NEVER clobber real history with simulated telemetry: if this date
            // already has a record, keep it (the AI coach and baseline must only
            // ever reference genuine measurements).
            if (dao.getRecordForDate(dateStr) != null) continue

            val currentBaseline = baselineManager.getCurrentBaseline()
            val targetDate = try { LocalDate.parse(dateStr) } catch (_: Exception) { LocalDate.now(ZoneId.systemDefault()) }
            val physResult = physiologyEngine.calculatePhysiology(dayTelemetry, currentBaseline, targetDate)

            val record = DailyMetricRecord.fromPhysiology(
                date = dateStr,
                timestamp = System.currentTimeMillis() - (daysAgo * 86400000L),
                restingBpm = dayTelemetry.restingBpm,
                avgBpm = dayTelemetry.avgBpm,
                maxBpm = dayTelemetry.peakBpm,
                steps = dayTelemetry.steps,
                calories = dayTelemetry.calories,
                distanceKm = dayTelemetry.distanceKm,
                workoutDurationMin = dayTelemetry.exerciseMinutes,
                phys = physResult
            )

            dao.insertOrUpdate(record)

            // Chain: the next seeded day's Recovery now uses THIS day's real FINAL
            // strain, so the history shows a genuine recovery progression.
            physiologyEngine.persistYesterdayStrain(physResult.strain)
        }

        lastRolloverDate = today.minusDays(1).toString()
        // Clears today's locked Recovery / Energy so the app recomputes today's
        // values from the freshly-seeded scenario on the next runAnalysis().
        resetDailyMetrics()
        persistYesterdayStrain(today.minusDays(1).toString())
    }

    suspend fun simulateMidnightRollover() = performMidnightRollover()
}
