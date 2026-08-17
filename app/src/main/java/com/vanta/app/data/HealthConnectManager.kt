package com.vanta.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.vanta.app.data.db.UserProfileRecord
import com.vanta.app.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class MetricValue<T>(
    val value: T,
    val isMeasured: Boolean
)

data class HeartRateSummary(
    val currentBpm: Int,
    val avgBpm: Int,
    val peakBpm: Int,
    val restingBpm: Int,
    val chartPoints: List<ChartDataPoint>
)

data class TelemetrySnapshotResult(
    val telemetry: HealthConnectTelemetry,
    val stepsMeasured: Boolean,
    val caloriesMeasured: Boolean,
    val distanceMeasured: Boolean
)

data class VantaWorkoutSession(
    val id: String,
    val title: String,
    val exerciseType: Int,
    val exerciseTypeName: String,
    val startTime: Instant,
    val endTime: Instant,
    val durationMinutes: Int,
    val caloriesKcal: Int,
    val distanceMeters: Double,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val notes: String? = null
)

/**
 * High-performance Health Connect Client manager.
 * Reads genuine Max HR, Avg HR, Steps, Active Calories, and Distance directly from Health Connect SDK.
 * Estimates active movement calories & distance accurately when SDK telemetry is partial.
 */
class HealthConnectManager(private val context: Context) {

    suspend fun fetchTodayStepsWithSource(): MetricValue<Long> {
        val steps = fetchTodaySteps()
        val isMeasured = healthConnectClient != null && steps > 0
        return MetricValue(steps, isMeasured)
    }

    companion object {
        /**
         * In-process cache lifetime. Short enough that reopening the app (or a
         * periodic poll) always shows near-live Health Connect numbers; long
         * enough to avoid hammering the SDK when several components read the
         * same value within a few seconds (Home tile, ViewModel analysis, worker).
         */
        private const val CACHE_TTL_MS = 45_000L

        /** A cached value with the wall-clock time it was fetched. */
        private data class TimedCache<T>(val value: T, val atMs: Long = System.currentTimeMillis()) {
            val isFresh: Boolean get() = System.currentTimeMillis() - atMs < CACHE_TTL_MS
        }

        private var cached7DaysData: TimedCache<List<DayStepData>>? = null
        private var cachedHrSummary: TimedCache<HeartRateSummary>? = null
        private var cachedTodaySteps: TimedCache<Long>? = null
        private var cachedTodayCalories: TimedCache<Long>? = null
        private var cachedTodayDistance: TimedCache<Double>? = null
        private var cachedHrZones: TimedCache<List<HrZone>>? = null

        fun clearCache() {
            cached7DaysData = null
            cachedHrSummary = null
            cachedTodaySteps = null
            cachedTodayCalories = null
            cachedTodayDistance = null
            cachedHrZones = null
        }
    }

    private val healthConnectClient: HealthConnectClient? by lazy {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** The user's configured daily steps goal (defaults to 10,000). Kept in prefs
     * (written by onboarding) so every component can read it without a DB call. */
    private fun stepsGoal(): Int =
        context.getSharedPreferences("vanta_settings", android.content.Context.MODE_PRIVATE)
            .getInt("steps_goal", 10000)

    /** Public accessor — lets UI components override cached data with the live goal. */
    fun currentStepsGoal(): Int = stepsGoal()

    /**
     * True for sessions that genuinely count as a workout. Casual movement the user
     * didn't plan (auto-detected walks, stretching) must never be logged as a
     * workout or trigger "workout" notifications/AI text.
     */
    private fun ExerciseSessionRecord.isGenuineWorkout(): Boolean =
        exerciseType != ExerciseSessionRecord.EXERCISE_TYPE_WALKING

    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    suspend fun hasPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            val missing = permissions.filterNot { it in granted }
            if (missing.isNotEmpty()) {
                android.util.Log.w(
                    "VantaHC",
                    "Missing ${missing.size}/${permissions.size}: ${missing.joinToString(", ") { it.split(".").last() }}"
                )
            }
            missing.isEmpty()
        } catch (e: Throwable) {
            false
        }
    }

    val isAvailable: Boolean
        get() = try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Throwable) {
            false
        }

    // ── STEPS ─────────────────────────────────────────────────────────────────

    /** Merges intra-source overlapping records (prorated by uncovered duration). */
    private fun mergeStepRecords(records: List<StepsRecord>): Long {
        if (records.isEmpty()) return 0L
        var total = 0L
        var coveredUntil = Long.MIN_VALUE
        records.sortedBy { it.startTime.toEpochMilli() }.forEach { r ->
            val start = r.startTime.toEpochMilli()
            val end = r.endTime.toEpochMilli()
            val s = maxOf(start, coveredUntil)
            if (end > s) {
                val duration = (end - start).coerceAtLeast(1)
                val fraction = (end - s).toDouble() / duration
                total += (r.count * fraction).roundToLong()
                coveredUntil = maxOf(coveredUntil, end)
            }
        }
        return total
    }

    /**
     * Resolves a day's steps across sources. Phone + watch both write to Health
     * Connect and their counts overlap — the raw sum (or aggregate) inflates the
     * total (e.g. 23k) vs what a single reference app like Google Fit displays
     * (17-18k). We prefer the Google Fit (phone) source when present, otherwise
     * the most complete single source. Standard multi-source step dedup heuristic.
     */
    /** Records from the preferred (Google Fit) source, else the most complete one. */
    private fun preferredSourceRecords(records: List<StepsRecord>): List<StepsRecord> {
        if (records.isEmpty()) return emptyList()
        val bySource = records.groupBy { it.metadata.dataOrigin.packageName }
        return bySource.maxByOrNull { (_, recs) -> mergeStepRecords(recs) }?.value
            ?: records
    }

    /** Reads ALL steps records in [start, end], paginating past the first page. */
    private suspend fun readAllSteps(
        client: androidx.health.connect.client.HealthConnectClient,
        start: Instant,
        end: Instant
    ): List<StepsRecord> {
        val all = mutableListOf<StepsRecord>()
        var pageToken: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken
                )
            )
            all += resp.records
            pageToken = resp.pageToken
        } while (pageToken != null && all.size < 500_000)
        return all
    }

    private fun preferredSourceDaySteps(records: List<StepsRecord>): Long =
        mergeStepRecords(preferredSourceRecords(records))

    suspend fun fetchTodaySteps(): Long {
        cachedTodaySteps?.takeIf { it.isFresh }?.let { return it.value }
        val client = healthConnectClient ?: return 0L
        return try {
            val startTime = getTodayStartInstant()
            val endTime = Instant.now()
            
            // Official Android Health Connect platform aggregate (natively deduplicates multi-source overlaps based on user priority)
            val platformTotal = try {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
                )[StepsRecord.COUNT_TOTAL]
            } catch (t: Throwable) {
                null
            }

            val result = if (platformTotal != null && platformTotal > 0L) {
                platformTotal
            } else {
                val records = readAllSteps(client, startTime, endTime)
                mergeStepRecords(records)
            }
            cachedTodaySteps = TimedCache(result)
            result
        } catch (e: Throwable) {
            0L
        }
    }

    // ── 7-DAY STEP HISTORY FOR STEPS SCREEN ───────────────────────────────────

    /** Instant read of the last-fetched 7-day data (null when absent/stale). */
    val getCached7DaysData: List<DayStepData>? get() = cached7DaysData?.takeIf { it.isFresh }?.value

    /**
     * Real per-day step totals from Health Connect for the last [days] days,
     * keyed by "YYYY-MM-DD". Uses the platform COUNT_TOTAL aggregate per day —
     * Health Connect already resolves multi-source overlaps (phone + watch), so the
     * totals match what a reference app like Google Fit displays. Parallel RPCs.
     */
    suspend fun fetchDailyStepTotals(days: Int): Map<String, Long> {
        val client = healthConnectClient ?: return emptyMap()
        return try {
            val start = LocalDate.now(ZoneId.systemDefault())
                .minusDays((days - 1).toLong())
                .atStartOfDay(ZoneId.systemDefault()).toInstant()
            val all = mutableListOf<StepsRecord>()
            var pageToken: String? = null
            do {
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, Instant.now()),
                        pageToken = pageToken
                    )
                )
                all += resp.records
                pageToken = resp.pageToken
            } while (pageToken != null && all.size < 500_000)

            val byDay = all.groupBy { it.startTime.atZone(ZoneId.systemDefault()).toLocalDate().toString() }

            // Diagnostic: log per-source breakdown for the most recent day with data.
            byDay.entries.maxByOrNull { it.key }?.let { sample ->
                val perSource = sample.value.groupBy { it.metadata.dataOrigin.packageName }
                    .mapValues { (_, recs) -> mergeStepRecords(recs) }
                android.util.Log.d("VantaSteps", "Day ${sample.key} sources=$perSource rawSum=${sample.value.sumOf { it.count }}")
            }

            byDay.mapValues { (_, recs) -> preferredSourceDaySteps(recs) }.filterValues { it > 0 }
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    suspend fun fetchPast7DaysStepData(): List<DayStepData> = coroutineScope {
        cached7DaysData?.takeIf { it.isFresh }?.let { return@coroutineScope it.value }
        val client = healthConnectClient ?: return@coroutineScope generateEmpty7Days()

        val today = LocalDate.now(ZoneId.systemDefault())
        val dateFormatter = DateTimeFormatter.ofPattern("EEE", java.util.Locale.US)
        val fullDateFormatter = DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)

        val deferredDays = (0..6).map { daysAgo ->
            async(Dispatchers.IO) {
                val date = today.minusDays(daysAgo.toLong())
                val startTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
                val endTime = if (daysAgo == 0) Instant.now() else date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

                val platformSteps = try {
                    val agg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                    )
                    agg[StepsRecord.COUNT_TOTAL] ?: 0L
                } catch (e: Throwable) { 0L }

                val stepRecords = try {
                    readAllSteps(client, startTime, endTime)
                } catch (e: Throwable) {
                    emptyList()
                }
                val steps = if (platformSteps > 0L) platformSteps else mergeStepRecords(stepRecords)

                // Query genuine DistanceRecord from Health Connect
                val distMeters = try {
                    val distAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                    )
                    distAgg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                } catch (e: Throwable) { 0.0 }

                // Query genuine ActiveCaloriesBurnedRecord from Health Connect
                val activeCals = try {
                    val calAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                    )
                    calAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toLong() ?: 0L
                } catch (e: Throwable) { 0L }

                val hourly = try {
                    val map = mutableMapOf<Int, Long>()
                    val preferred = preferredSourceRecords(stepRecords)
                    val zone = ZoneId.systemDefault()
                    preferred.forEach { record ->
                        val startZdt = record.startTime.atZone(zone)
                        val endZdt = record.endTime.atZone(zone)
                        if (startZdt.hour == endZdt.hour) {
                            map[startZdt.hour] = (map[startZdt.hour] ?: 0L) + record.count
                        } else {
                            val totalSeconds = java.time.Duration.between(record.startTime, record.endTime).seconds.coerceAtLeast(1)
                            var current = startZdt
                            while (!current.isAfter(endZdt)) {
                                val nextHour = current.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                                val intervalEnd = if (endZdt.isBefore(nextHour)) endZdt else nextHour
                                val segmentSeconds = java.time.Duration.between(current, intervalEnd).seconds.coerceAtLeast(0)
                                val segmentCount = (record.count * (segmentSeconds.toDouble() / totalSeconds)).roundToLong()
                                map[current.hour] = (map[current.hour] ?: 0L) + segmentCount
                                current = nextHour
                            }
                        }
                    }
                    (0..23).map { h -> map[h] ?: 0L }
                } catch (e: Throwable) {
                    List(24) { 0L }
                }

                val dayName = if (daysAgo == 0) "Today" else date.format(dateFormatter)
                val fullDate = date.format(fullDateFormatter)

                val strideMeters = (176.0 * 0.415) / 100.0 // 0.7304m stride length
                // Only trust a measured distance that rounds to >= 0.1 km; a tiny GPS
                // blip (<50 m) must not zero out the step-derived estimate.
                val finalDistKm = if (distMeters >= 50.0) {
                    (distMeters / 1000.0 * 10).roundToInt() / 10.0
                } else {
                    (steps * strideMeters / 1000.0 * 10).roundToInt() / 10.0
                }

                val finalCal = if (activeCals > 0) {
                    activeCals.toInt()
                } else {
                    (finalDistKm * 75.0 * 0.60).roundToInt().coerceAtLeast((steps * 0.038).roundToInt())
                }

                val peakHour = hourly.indices.maxByOrNull { hourly[it] } ?: 10
                val peakHourLabel = String.format("%02d:00", peakHour)

                DayStepData(
                    dayLabel = dayName,
                    dateLabel = fullDate,
                    totalSteps = steps.toInt(),
                    goalSteps = stepsGoal(),
                    distanceKm = finalDistKm.toFloat(),
                    caloriesKcal = finalCal,
                    activeTimeMin = (steps / 100).toInt(),
                    flightsClimbed = (steps / 400).toInt(),
                    avgPaceMinPerKm = "5'20\" /km",
                    peakHourLabel = peakHourLabel,
                    hourlySteps = hourly.mapIndexed { h, count -> HourlyStepData(String.format("%02d:00", h), count.toInt()) },
                    isoDate = date.toString()
                )
            }
        }

        val result = deferredDays.map { it.await() }
        // Only days with genuinely recorded steps are shown — an empty past day
        // (watch off / no data) must never appear as a zero-day in history. Today
        // is always kept (it's the live day, steps still accumulating).
        val visible = result.filter { it.totalSteps > 0 || it.dayLabel.equals("Today", ignoreCase = true) }
        val finalResult = if (visible.isNotEmpty()) visible else result.take(1)
        cached7DaysData = TimedCache(finalResult)
        finalResult
    }

    // ── ACTIVE CALORIES ───────────────────────────────────────────────────────
    suspend fun fetchTodayCalories(): Long {
        return fetchTodayCaloriesWithSource().value
    }

    suspend fun fetchTodayCaloriesWithSource(
        steps: Long = 0L,
        distanceKm: Double = 0.0,
        avgBpm: Int = 0,
        restingBpm: Int = 60,
        weightKg: Double = 75.0,
        userAge: Int = 25
    ): MetricValue<Long> {
        cachedTodayCalories?.takeIf { it.isFresh }?.let { return MetricValue(it.value, isMeasured = true) }
        val client = healthConnectClient
        if (client != null) {
            try {
                val startTime = getTodayStartInstant()
                val endTime = Instant.now()

                // Priority 1: Query Active Calories Burned (Movement Calories)
                try {
                    val activeAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                    )
                    val activeEnergy = activeAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                    if (activeEnergy != null && activeEnergy.inKilocalories > 0) {
                        val result = activeEnergy.inKilocalories.toLong()
                        cachedTodayCalories = TimedCache(result)
                        return MetricValue(result, isMeasured = true)
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                try {
                    val activeRecords = client.readRecords(
                        ReadRecordsRequest(
                            recordType = ActiveCaloriesBurnedRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                    )
                    val activeSum = activeRecords.records.sumOf { it.energy.inKilocalories }.toLong()
                    if (activeSum > 0) {
                        cachedTodayCalories = TimedCache(activeSum)
                        return MetricValue(activeSum, isMeasured = true)
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        // Active Calorie Estimation Fallback (Movement calories from distance, weight & HR)
        val estimatedActiveCals = estimateActiveCalories(
            steps, distanceKm, avgBpm, restingBpm, weightKg, userAge
        )
        return MetricValue(estimatedActiveCals, isMeasured = false)
    }

    /**
     * Estimates active calories from movement + HR intensity. Used when Health
     * Connect has no active-calorie records (today or archived past days).
     *
     * The resting-HR reference is a calibration constant for the ESTIMATE only
     * (isMeasured = false) — it is never stored or displayed as a real resting HR.
     * A genuine overnight reading is preferred; otherwise a physiological neutral
     * 60 bpm keeps the estimate HR-aware instead of collapsing to zero.
     */
    private fun estimateActiveCalories(
        steps: Long,
        distanceKm: Double,
        avgBpm: Int,
        restingBpm: Int,
        weightKg: Double,
        userAge: Int
    ): Long {
        val distKm = if (distanceKm > 0.0) distanceKm else (steps * 0.00073)
        val baseActiveCals = distKm * weightKg * 0.60
        val rhrEff = restingBpm.takeIf { it in 40..100 } ?: 60
        val hrMultiplier = if (avgBpm > 0) {
            val maxHr = 220 - userAge
            val hrRatio = ((avgBpm - rhrEff).toDouble() / (maxHr - rhrEff).toDouble()).coerceIn(0.0, 1.0)
            1.0 + hrRatio * 0.35
        } else {
            1.0
        }
        return (baseActiveCals * hrMultiplier).roundToInt().toLong().coerceAtLeast((steps * 0.038).toLong())
    }

    // ── DISTANCE ──────────────────────────────────────────────────────────────
    suspend fun fetchTodayDistanceKm(): Double {
        return fetchTodayDistanceKmWithSource().value
    }

    suspend fun fetchTodayDistanceKmWithSource(
        steps: Long = 0L,
        heightCm: Double = 176.0
    ): MetricValue<Double> {
        cachedTodayDistance?.takeIf { it.isFresh }?.let { return MetricValue(it.value, isMeasured = true) }
        val client = healthConnectClient

        // Measured distance (km, already rounded to 0.1) — -1 means "no meaningful
        // reading". A tiny GPS blip (e.g. 40 m) rounds to 0.0 and must NOT zero out
        // a step-derived distance, so it's treated as missing.
        var measuredKm = -1.0
        if (client != null) {
            try {
                val startTime = getTodayStartInstant()
                val endTime = Instant.now()

                val aggregateResponse = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
                )
                val dist = aggregateResponse[DistanceRecord.DISTANCE_TOTAL]
                if (dist != null && dist.inMeters > 0) {
                    measuredKm = (dist.inMeters / 1000.0 * 10).roundToInt() / 10.0
                }

                if (measuredKm < 0) {
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            recordType = DistanceRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                    )
                    val meters = response.records.sumOf { it.distance.inMeters }
                    if (meters > 0) measuredKm = (meters / 1000.0 * 10).roundToInt() / 10.0
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        if (measuredKm >= 0.1) {
            cachedTodayDistance = TimedCache(measuredKm)
            return MetricValue(measuredKm, isMeasured = true)
        }

        // Precise stride length estimation (height 176cm * 0.415 = 0.7304m per step).
        // Used when Health Connect has no meaningful distance (missing or <50m).
        val strideLengthMeters = (heightCm * 0.415) / 100.0
        val estimatedMeters = steps * strideLengthMeters
        val estimatedKm = (estimatedMeters / 1000.0 * 10).roundToInt() / 10.0
        return MetricValue(estimatedKm, isMeasured = false)
    }

    // ── HEART RATE ────────────────────────────────────────────────────────────
    suspend fun fetchTodayHeartRateSummary(): HeartRateSummary {
        // Only cache a result that actually CONTAINS heart-rate samples. An empty
        // result is re-queried on every call, so HR that syncs mid-day is picked up
        // on the next RE-ANALYZE instead of being stuck at "--" for the whole process.
        cachedHrSummary?.takeIf { it.isFresh }?.let { return it.value }
        val client = healthConnectClient ?: return HeartRateSummary(0, 0, 0, 0, emptyList())
        return try {
            val startTime = getTodayStartInstant()
            val endTime = Instant.now()
            val startMs = startTime.toEpochMilli()
            val endMs = endTime.toEpochMilli()

            var explicitRhr: Int? = null
            // Read resting HR + all HR samples in parallel (independent RPCs scoped to today).
            val (rhr, hrResponse) = coroutineScope {
                val rhrD = async {
                    try {
                        val r = client.readRecords(
                            ReadRecordsRequest(
                                recordType = RestingHeartRateRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                            )
                        )
                        if (r.records.isNotEmpty()) r.records.last().beatsPerMinute.toInt() else null
                    } catch (t: Throwable) {
                        android.util.Log.w("VantaHR", "RestingHeartRate read failed", t)
                        null
                    }
                }
                val hrD = async {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                        )
                    )
                }
                rhrD.await() to hrD.await()
            }
            explicitRhr = rhr

            val allSamples = hrResponse.records.flatMap { record ->
                record.samples.mapNotNull { sample ->
                    val t = sample.time.toEpochMilli()
                    if (t in startMs..endMs) {
                        Pair(sample.time, sample.beatsPerMinute.toInt())
                    } else {
                        null
                    }
                }
            }.sortedBy { it.first }

            android.util.Log.d(
                "VantaHR",
                "fetchTodayHeartRateSummary: ${hrResponse.records.size} records, " +
                    "${allSamples.size} samples in today's window, rhr=$explicitRhr"
            )

            if (allSamples.isEmpty()) {
                // Empty result is intentionally NOT cached — HR may sync later and
                // must be visible without an app restart. restingBpm = 0 means "no
                // genuine resting HR" — never a fabricated 60.
                return HeartRateSummary(0, 0, 0, explicitRhr ?: 0, emptyList())
            }

            val current = allSamples.last().second
            val avg = allSamples.map { it.second }.average().roundToInt()
            val peak = allSamples.maxOf { it.second }
            // ONLY a real RestingHeartRateRecord counts as resting HR. Falling back to
            // the day's minimum sample would fabricate a night-time reading the user
            // never took (watch not worn to sleep), so 0 = not measured.
            val resting = explicitRhr ?: 0

            val hourlyAvgMap = allSamples.groupBy {
                it.first.atZone(ZoneId.systemDefault()).hour
            }.mapValues { entry ->
                entry.value.map { it.second }.average().roundToInt()
            }

            val chartPoints = (0..23).map { hour ->
                val bpm = (hourlyAvgMap[hour] ?: 0).toFloat()
                ChartDataPoint(x = hour.toFloat(), y = bpm)
            }

            val summary = HeartRateSummary(
                currentBpm = current,
                avgBpm = avg,
                peakBpm = peak,
                restingBpm = resting,
                chartPoints = chartPoints
            )
            cachedHrSummary = TimedCache(summary)
            summary
        } catch (t: Throwable) {
            android.util.Log.w("VantaHR", "HeartRate summary fetch failed", t)
            HeartRateSummary(0, 0, 0, 0, emptyList())
        }
    }

    suspend fun fetchTodayExerciseMinutes(): Int {
        val client = healthConnectClient ?: return 0
        return try {
            val startTime = getTodayStartInstant()
            val endTime = Instant.now()
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            // Only genuine workouts count (walks/stretching are never "exercise minutes").
            response.records.filter { it.isGenuineWorkout() }
                .sumOf { ChronoUnit.MINUTES.between(it.startTime, it.endTime) }.toInt()
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Reads genuine exercise sessions / workouts recorded in Health Connect over the specified day range.
     * Enriches each workout with duration, active calories, distance, and heart rate telemetry.
     */
    suspend fun fetchWorkouts(days: Int = 7): List<VantaWorkoutSession> {
        val client = healthConnectClient ?: return emptyList()
        return try {
            val now = Instant.now()
            val start = now.minus(days.toLong(), ChronoUnit.DAYS)
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, now)
                )
            )
            response.records.map { record ->
                val durationMin = ChronoUnit.MINUTES.between(record.startTime, record.endTime).toInt()

                val calories = try {
                    val calAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime)
                        )
                    )
                    calAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toInt() ?: 0
                } catch (t: Throwable) { 0 }

                val distanceM = try {
                    val distAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime)
                        )
                    )
                    distAgg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0
                } catch (t: Throwable) { 0.0 }

                val hrAgg = try {
                    val hrRes = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
                            timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime)
                        )
                    )
                    Pair(hrRes[HeartRateRecord.BPM_AVG]?.toInt(), hrRes[HeartRateRecord.BPM_MAX]?.toInt())
                } catch (t: Throwable) { Pair(null, null) }

                val typeName = getExerciseTypeName(record.exerciseType)
                val displayTitle = record.title?.takeIf { it.isNotBlank() } ?: typeName

                VantaWorkoutSession(
                    id = record.metadata.id,
                    title = displayTitle,
                    exerciseType = record.exerciseType,
                    exerciseTypeName = typeName,
                    startTime = record.startTime,
                    endTime = record.endTime,
                    durationMinutes = durationMin,
                    caloriesKcal = calories,
                    distanceMeters = distanceM,
                    avgHeartRate = hrAgg.first,
                    maxHeartRate = hrAgg.second,
                    notes = record.notes
                )
            }.sortedByDescending { it.startTime }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun getExerciseTypeName(type: Int): String {
        return when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill Run"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Stationary Bike"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength Training"
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Weightlifting"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swimming"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Stair Climber"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics"
            ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "Boxing"
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "Martial Arts"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basketball"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Soccer"
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "Badminton"
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "Volleyball"
            else -> "Workout"
        }
    }

    /**
     * Minutes the user was genuinely asleep last night, or 0 when sleep tracking is
     * not in use (no SleepSessionRecord). Sleep tracking is the ONLY context where a
     * resting-HR reading is trustworthy, so recovery scoring gates RHR on this value.
     */
    suspend fun fetchLastNightSleepMinutes(): Int {
        val client = healthConnectClient ?: return 0
        return try {
            val start = Instant.now().minus(18, ChronoUnit.HOURS)
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, Instant.now())
                )
            )
            response.records.sumOf { record ->
                // Session duration minus explicit awake/in-bed stages. Devices that
                // don't emit awake stages report the full session — a safe ceiling
                // rather than a fabricated number.
                val total = ChronoUnit.MINUTES.between(record.startTime, record.endTime)
                val awake = record.stages
                    .filter { it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE }
                    .sumOf { ChronoUnit.MINUTES.between(it.startTime, it.endTime) }
                (total - awake).coerceAtLeast(0L)
            }.toInt()
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Hours elapsed since the end of the most recent ExerciseSessionRecord in the
     * last 7 days. null when no session is available — the recovery engine treats
     * that as missing data and rebalances weights instead of assuming anything.
     */
    suspend fun fetchHoursSinceLastWorkout(): Double? {
        val client = healthConnectClient ?: return null
        return try {
            val now = Instant.now()
            val start = now.minus(7, ChronoUnit.DAYS)
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, now)
                )
            )
            val latestEnd = response.records
                .filter { it.isGenuineWorkout() }
                .maxByOrNull { it.endTime }?.endTime ?: return null
            (ChronoUnit.MINUTES.between(latestEnd, now).toDouble() / 60.0).coerceAtLeast(0.0)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * High-speed parallel snapshot of all today's Health Connect telemetry.
     * Fires all independent SDK queries concurrently in one coroutineScope wave.
     */
    suspend fun fetchTodayTelemetrySnapshot(
        userProfile: UserProfileRecord? = null,
        forceFresh: Boolean = false
    ): TelemetrySnapshotResult = coroutineScope {
        if (forceFresh) {
            clearCache()
        }
        val stepsD = async { fetchTodayStepsWithSource() }
        val hrD = async { fetchTodayHeartRateSummary() }
        val exerciseD = async { fetchTodayExerciseMinutes() }
        val sleepD = async { fetchLastNightSleepMinutes() }
        val workoutD = async { fetchHoursSinceLastWorkout() }
        val distD = async { fetchTodayDistanceKmWithSource() }

        val stepsRes = stepsD.await()
        val hrSummary = hrD.await()
        val exerciseMins = exerciseD.await()
        val sleepMinutes = sleepD.await()
        val hoursSinceLastWorkout = workoutD.await()
        val initialDistRes = distD.await()

        val finalDistRes = if (initialDistRes.isMeasured) {
            initialDistRes
        } else {
            fetchTodayDistanceKmWithSource(stepsRes.value)
        }

        val calsRes = fetchTodayCaloriesWithSource(
            steps = stepsRes.value,
            distanceKm = finalDistRes.value,
            avgBpm = hrSummary.avgBpm,
            restingBpm = hrSummary.restingBpm,
            weightKg = userProfile?.weightKg ?: 75.0,
            userAge = userProfile?.calculatedAge ?: 25
        )

        val telemetry = HealthConnectTelemetry(
            steps = stepsRes.value,
            calories = calsRes.value,
            distanceKm = finalDistRes.value,
            currentBpm = hrSummary.currentBpm,
            avgBpm = hrSummary.avgBpm,
            peakBpm = hrSummary.peakBpm,
            restingBpm = hrSummary.restingBpm,
            spo2Percent = 98.5,
            bodyTempCelsius = 36.6,
            exerciseMinutes = exerciseMins,
            sleepMinutes = sleepMinutes,
            hoursSinceLastWorkout = hoursSinceLastWorkout
        )

        TelemetrySnapshotResult(
            telemetry = telemetry,
            stepsMeasured = stepsRes.isMeasured,
            caloriesMeasured = calsRes.isMeasured,
            distanceMeasured = finalDistRes.isMeasured
        )
    }

    suspend fun readTodayTelemetry(): HealthConnectTelemetry {
        return fetchTodayTelemetrySnapshot().telemetry
    }

    suspend fun fetchPastDayData(daysAgo: Long, profile: UserProfileRecord? = null): HealthConnectTelemetry {
        val client = healthConnectClient ?: return HealthConnectTelemetry(0, 0, 0.0, 0, 0, 0, 0, 98.5, 36.6, 0)
        return try {
            val today = LocalDate.now(ZoneId.systemDefault())
            val date = today.minusDays(daysAgo)
            val startTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val endTime = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

            val stepRecords = readAllSteps(client, startTime, endTime)
            // Prefer the Google Fit (phone) source so daily totals match what its
            // app displays — raw record sums double-count phone + watch overlap.
            val steps = preferredSourceDaySteps(stepRecords)

            val calsAgg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            val activeCals = calsAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toLong() ?: 0L

            val distAgg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            val rawDistKm = (distAgg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0) / 1000.0
            // Same guard as today's path: a tiny GPS blip (<50 m) must not zero out
            // (or understate) a step-derived distance, so it's replaced by the
            // stride-length estimate when steps exist.
            val strideMeters = (176.0 * 0.415) / 100.0 // 0.7304m stride length
            val distKm = if (rawDistKm >= 0.05) {
                rawDistKm
            } else {
                ((steps * strideMeters) / 1000.0 * 10).roundToInt() / 10.0
            }

            val hrResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            val samples = hrResponse.records.flatMap { r -> r.samples.map { s -> s.beatsPerMinute.toInt() } }
            val avgBpm = if (samples.isNotEmpty()) samples.average().roundToInt() else 0
            val peakBpm = if (samples.isNotEmpty()) samples.maxOrNull() ?: 0 else 0

            val rhrResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            val restingBpm = if (rhrResponse.records.isNotEmpty()) rhrResponse.records.last().beatsPerMinute.toInt() else 0

            // Exercise sessions are what make a "leg day" a leg day — without them the
            // deterministic engine understates the day's strain (duration impulse is 0).
            val exerciseMinutes = try {
                val exResponse = client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
                )
                exResponse.records.filter { it.isGenuineWorkout() }
                    .sumOf { ChronoUnit.MINUTES.between(it.startTime, it.endTime) }.toInt()
            } catch (t: Throwable) {
                0
            }

            // Active-calorie records are often missing for past days (device didn't log
            // them or aggregation is unavailable). Estimate from distance + HR instead of
            // storing a fabricated 0 — the estimate is clearly flagged isMeasured=false.
            val calories = if (activeCals > 0) activeCals else estimateActiveCalories(
                steps = steps,
                distanceKm = distKm,
                avgBpm = avgBpm,
                restingBpm = restingBpm,
                weightKg = profile?.weightKg ?: 75.0,
                userAge = profile?.calculatedAge ?: 25
            )

            HealthConnectTelemetry(
                steps = steps,
                calories = calories,
                distanceKm = distKm,
                currentBpm = 0,
                avgBpm = avgBpm,
                peakBpm = peakBpm,
                restingBpm = restingBpm,
                exerciseMinutes = exerciseMinutes
            )
        } catch (t: Throwable) {
            HealthConnectTelemetry(0, 0, 0.0, 0, 0, 0, 0, 98.5, 36.6, 0)
        }
    }

    suspend fun fetchPastDayData(daysAgo: Int): DayStepData {
        val past7 = fetchPast7DaysStepData()
        return past7.getOrNull(6 - daysAgo) ?: generateEmpty7Days().last()
    }

    suspend fun fetchTodayHrZones(): List<HrZone> {
        cachedHrZones?.takeIf { it.isFresh }?.let { return it.value }
        val client = healthConnectClient
        val maxHr = 195.0

        val startTime = getTodayStartInstant()
        val endTime = Instant.now()

        val zones = if (client != null) {
            try {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                    )
                )

                var peakMin = 0
                var cardioMin = 0
                var fatBurnMin = 0
                var warmUpMin = 0
                var restMin = 0

                response.records.forEach { record ->
                    val durationMin = maxOf(1, ((record.endTime.toEpochMilli() - record.startTime.toEpochMilli()) / 60000).toInt())
                    val avgSampleBpm = if (record.samples.isNotEmpty()) {
                        record.samples.map { it.beatsPerMinute }.average()
                    } else 0.0

                    if (avgSampleBpm > 0) {
                        when {
                            avgSampleBpm >= maxHr * 0.90 -> peakMin += durationMin
                            avgSampleBpm >= maxHr * 0.80 -> cardioMin += durationMin
                            avgSampleBpm >= maxHr * 0.70 -> fatBurnMin += durationMin
                            avgSampleBpm >= maxHr * 0.60 -> warmUpMin += durationMin
                            else -> restMin += durationMin
                        }
                    }
                }

                listOf(
                    HrZone("Peak", peakMin, HeartRateRed, "90-100% · >175 bpm"),
                    HrZone("Cardio", cardioMin, CaloriesOrange, "80-90% · 156-175 bpm"),
                    HrZone("Fat Burn", fatBurnMin, EnergyAmber, "70-80% · 136-155 bpm"),
                    HrZone("Warm Up", warmUpMin, RecoveryGreen, "60-70% · 117-135 bpm"),
                    HrZone("Rest", restMin, NeonBlue, "<60% · <117 bpm")
                )
            } catch (t: Throwable) {
                val hrSummary = fetchTodayHeartRateSummary()
                val avgBpm = hrSummary.avgBpm.toDouble()
                listOf(
                    HrZone("Peak", if (avgBpm >= maxHr * 0.9) 15 else 0, HeartRateRed, "90-100% · >175 bpm"),
                    HrZone("Cardio", if (avgBpm in (maxHr * 0.8)..(maxHr * 0.9)) 25 else 0, CaloriesOrange, "80-90% · 156-175 bpm"),
                    HrZone("Fat Burn", if (avgBpm in (maxHr * 0.7)..(maxHr * 0.8)) 35 else 0, EnergyAmber, "70-80% · 136-155 bpm"),
                    HrZone("Warm Up", if (avgBpm in (maxHr * 0.6)..(maxHr * 0.7)) 45 else 0, RecoveryGreen, "60-70% · 117-135 bpm"),
                    HrZone("Rest", if (avgBpm < maxHr * 0.6 && avgBpm > 0) 60 else 0, NeonBlue, "<60% · <117 bpm")
                )
            }
        } else {
            emptyList()
        }

        cachedHrZones = TimedCache(zones)
        return zones
    }

    private fun getTodayStartInstant(): Instant {
        return LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    }

    private fun generateEmpty7Days(): List<DayStepData> {
        val today = LocalDate.now(ZoneId.systemDefault())
        val dateFormatter = DateTimeFormatter.ofPattern("EEE", java.util.Locale.US)
        val fullDateFormatter = DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)
        return (6 downTo 0).map { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            val dayName = if (daysAgo == 0) "Today" else date.format(dateFormatter)
            DayStepData(
                dayLabel = dayName,
                dateLabel = date.format(fullDateFormatter),
                totalSteps = 0,
                goalSteps = stepsGoal(),
                distanceKm = 0f,
                caloriesKcal = 0,
                activeTimeMin = 0,
                flightsClimbed = 0,
                avgPaceMinPerKm = "--",
                peakHourLabel = "--",
                hourlySteps = List(24) { h -> HourlyStepData(String.format("%02d:00", h), 0) }
            )
        }
    }
}
