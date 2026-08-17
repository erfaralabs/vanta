package com.vanta.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanta.app.data.ChartDataPoint
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.PersonalRecord
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.ui.components.*
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.viewmodel.VantaAiViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun AnalyticsScreen(
    modifier: Modifier = Modifier,
    aiViewModel: VantaAiViewModel = viewModel()
) {
    val context = LocalContext.current
    val manager = remember { HealthConnectManager(context) }

    val userBaseline by aiViewModel.userBaseline.collectAsState()
    val historicalRecords by aiViewModel.historicalRecords.collectAsState()

    // ── Real values derived from the archived daily records (Health Connect truth) ──
    val weeklyRecords = remember(historicalRecords) { historicalRecords.take(7) }
    val weeklyStepsSum = remember(weeklyRecords) { weeklyRecords.sumOf { it.steps } }
    val weeklyCaloriesSum = remember(weeklyRecords) { weeklyRecords.sumOf { it.calories } }
    val weeklyDistanceSum = remember(weeklyRecords) {
        (weeklyRecords.sumOf { it.distanceKm } * 10).roundToInt() / 10.0
    }
    val avgHeartRate = remember(weeklyRecords) {
        val vals = weeklyRecords.map { it.avgBpm }.filter { it in 40..220 }
        if (vals.isNotEmpty()) vals.average().roundToInt() else 0
    }

    val maxStepsRecord = remember(historicalRecords) {
        "%,d".format(historicalRecords.maxOfOrNull { it.steps } ?: 0)
    }
    val maxDistanceRecord = remember(historicalRecords) {
        "%.1f".format(historicalRecords.maxOfOrNull { it.distanceKm } ?: 0.0)
    }
    val peakHrRecord = remember(historicalRecords) {
        historicalRecords.mapNotNull { it.maxBpm.takeIf { b -> b in 50..220 } }.maxOrNull()?.toString() ?: "--"
    }
    val restingHrRecord = remember(historicalRecords) {
        historicalRecords.mapNotNull { it.restingBpm.takeIf { b -> b in 35..100 } }.minOrNull()?.toString() ?: "--"
    }

    var isLiveHealthConnect by remember { mutableStateOf(false) }

    // Health Connect 7-Day interactive charts
    var hrChartPoints by remember { mutableStateOf<List<ChartDataPoint>>(emptyList()) }
    var caloriesPoints by remember { mutableStateOf<List<ChartDataPoint>>(emptyList()) }
    var stepsPoints by remember { mutableStateOf<List<ChartDataPoint>>(emptyList()) }
    var liveZones by remember { mutableStateOf<List<com.vanta.app.data.HrZone>>(emptyList()) }
    var weekLabels by remember { mutableStateOf(listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Today")) }
    var avgHr by remember { mutableIntStateOf(0) }
    var peakHr by remember { mutableIntStateOf(0) }

    // Real 4-week per-day step totals from Health Connect & Room DB history.
    var stepTotals by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(historicalRecords) {
        withContext(Dispatchers.IO) {
            coroutineScope {
                val hrDeferred = async { manager.fetchTodayHeartRateSummary() }
                val daysDeferred = async { manager.fetchPast7DaysStepData() }
                val zonesDeferred = async { manager.fetchTodayHrZones() }
                val hcTotalsDeferred = async { runCatching { manager.fetchDailyStepTotals(35) }.getOrDefault(emptyMap()) }

                val hrSummary = hrDeferred.await()
                val pastDays = daysDeferred.await()
                val zones = zonesDeferred.await()
                val hcTotals = hcTotalsDeferred.await()

                val merged = hcTotals.toMutableMap()
                historicalRecords.forEach { rec ->
                    if (rec.steps > 0) {
                        merged[rec.date] = maxOf(merged[rec.date] ?: 0L, rec.steps)
                    }
                }

                withContext(Dispatchers.Main) {
                    stepTotals = merged
                    if (hrSummary.chartPoints.isNotEmpty()) {
                        hrChartPoints = hrSummary.chartPoints
                        avgHr = hrSummary.avgBpm
                        peakHr = hrSummary.peakBpm
                    }
                    if (pastDays.isNotEmpty()) {
                        weekLabels = pastDays.map { it.dayLabel }
                        caloriesPoints = pastDays.mapIndexed { idx, day ->
                            ChartDataPoint(idx.toFloat(), day.caloriesKcal.toFloat())
                        }
                        stepsPoints = pastDays.mapIndexed { idx, day ->
                            ChartDataPoint(idx.toFloat(), day.totalSteps.toFloat())
                        }
                    }
                    if (zones.isNotEmpty()) {
                        liveZones = zones
                    }
                    isLiveHealthConnect = true
                }
            }
        }
    }

    // Availability flags so the grid swaps in real metrics instead of dead "--" cards.
    val hasPeakHr = remember(historicalRecords) { historicalRecords.any { it.maxBpm in 50..220 } }
    val hasRestingHr = remember(historicalRecords) { historicalRecords.any { it.restingBpm in 35..100 } }
    val longestWorkoutRecord = remember(historicalRecords) {
        historicalRecords.maxOfOrNull { it.workoutDurationMin }?.takeIf { it > 0 }?.toString() ?: "--"
    }
    val mostCaloriesRecord = remember(historicalRecords) {
        "%,d".format(historicalRecords.maxOfOrNull { it.calories }?.takeIf { it > 0 } ?: 0)
    }

    // Health Records Highs — smart fallback: if a metric has no real data (e.g.
    // Resting HR when sleep isn't tracked), it is replaced by the next available
    // real record. No duplicates, no dead placeholders.
    val healthRecords = remember(
        maxStepsRecord, peakHrRecord, restingHrRecord, maxDistanceRecord,
        longestWorkoutRecord, mostCaloriesRecord, hasPeakHr, hasRestingHr
    ) {
        buildList {
            add(PersonalRecord("Max Daily Steps", maxStepsRecord, "STEPS", "This Week", "👣", StepsViolet))
            if (hasPeakHr) add(PersonalRecord("Peak Heart Rate", peakHrRecord, "BPM", "This Week", "💓", HeartRateRed))
            if (maxDistanceRecord != "0.0") add(PersonalRecord("Max Single Distance", maxDistanceRecord, "KM", "This Week", "📏", NeonBlue))
            if (hasRestingHr) add(PersonalRecord("Lowest Resting HR", restingHrRecord, "BPM", "This Week", "🫀", RecoveryGreen))
            if (longestWorkoutRecord != "--") add(PersonalRecord("Longest Workout", "$longestWorkoutRecord", "MIN", "This Week", "🏋️", StrainColor))
            if (mostCaloriesRecord != "0") add(PersonalRecord("Most Calories", mostCaloriesRecord, "KCAL", "This Week", "🔥", CaloriesOrange))
        }.take(4)
    }

    val recordRows = remember(healthRecords) { healthRecords.chunked(2) }

    val badgeShape = remember { RoundedCornerShape(8.dp) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(VantaBlack),
        contentPadding = PaddingValues(bottom = 160.dp)
    ) {
        // ── Page Header ──────────────────────────────────────────────────────
        item(key = "header") {
            val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Spacer(Modifier.height(topPadding + 20.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text  = "Insights",
                            color = TextPrimary,
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight    = FontWeight.ExtraBold,
                                letterSpacing = (-1).sp
                            )
                        )
                        Text(
                            text  = "7-Day Adaptive Learning & Health Connect",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // ── 7-Day Adaptive Baseline Card ──────────────────────────────────────
        item(key = "adaptive_baseline_card") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "7-Day Adaptive Learning Baseline")
                Spacer(Modifier.height(12.dp))

                InsightCard {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = userBaseline.phaseLabel.uppercase(),
                                color = if (userBaseline.isLearningPhase) EnergyAmber else RecoveryGreen,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                            )
                            Text(
                                text = "${minOf(userBaseline.savedDaysCount, 7)}/7 Days Archived",
                                color = TextTertiary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Progress Bar for Learning Phase
                        val progressFraction = (userBaseline.savedDaysCount / 7f).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (userBaseline.isLearningPhase) EnergyAmber else RecoveryGreen,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = userBaseline.subtleStatusMessage,
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp)
                        )

                        Spacer(Modifier.height(14.dp))

                        // Baseline Stats Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BaselineStatChip(
                                "Avg Rest HR",
                                if (userBaseline.hasRestingHrBaseline) "${userBaseline.avgRestingBpm.roundToInt()} bpm" else "— bpm",
                                RecoveryGreen
                            )
                            BaselineStatChip("Avg HR", "${userBaseline.avgAvgBpm.roundToInt()} bpm", HeartRateRed)
                            BaselineStatChip("Avg Steps", "%,d".format(userBaseline.avgSteps.roundToInt()), StepsViolet)
                            BaselineStatChip("Avg Calories", "${userBaseline.avgCalories.roundToInt()} kcal", CaloriesOrange)
                        }

                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        // Weekly summary banner
        item(key = "weekly_banner") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                WeeklySummaryBanner(
                    stepsSum = weeklyStepsSum,
                    caloriesSum = weeklyCaloriesSum,
                    distanceSum = weeklyDistanceSum,
                    avgHr = avgHeartRate
                )
                Spacer(Modifier.height(20.dp))
            }
        }

        // ── Charts ────────────────────────────────────────────────────────────
        item(key = "hc_charts_header") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "Charts")
                Spacer(Modifier.height(12.dp))
            }
        }

        // 1. Daily Steps History
        item(key = "chart_steps") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                InsightCard {
                    Text(
                        text = "DAILY STEPS HISTORY",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Last 7 days movement telemetry",
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 10.5.sp,
                        letterSpacing = 0.2.sp
                    )
                    Spacer(Modifier.height(14.dp))

                    if (stepsPoints.isNotEmpty()) {
                        GlowBarChart(
                            data = stepsPoints,
                            accentColor = StepsViolet,
                            labels = weekLabels,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        WeekLabelRow(labels = weekLabels)
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Syncing Steps...", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // 2. Active Calories Burned
        item(key = "chart_calories") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                InsightCard {
                    Text(
                        text = "ACTIVE CALORIES BURNED (KCAL)",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Last 7 days movement calories",
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 10.5.sp,
                        letterSpacing = 0.2.sp
                    )
                    Spacer(Modifier.height(14.dp))

                    if (caloriesPoints.isNotEmpty()) {
                        GlowBarChart(
                            data = caloriesPoints,
                            accentColor = CaloriesOrange,
                            labels = weekLabels,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        WeekLabelRow(labels = weekLabels)
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Syncing Calories...", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // 3. Heart Rate Trend
        item(key = "chart_hr") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                InsightCard {
                    Text(
                        text = "HEART RATE TREND",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (avgHr > 0) "Today (Avg: $avgHr bpm | Peak: $peakHr bpm)" else "Today continuous heart rate",
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 10.5.sp,
                        letterSpacing = 0.2.sp
                    )
                    Spacer(Modifier.height(14.dp))

                    if (hrChartPoints.isNotEmpty() && (avgHr > 0 || peakHr > 0)) {
                        GlowLineChart(
                            data = hrChartPoints,
                            accentColor = HeartRateRed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Heart rate not available", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // 4. Heart Rate Zones Breakdown
        item(key = "chart_hr_zones") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                InsightCard {
                    val totalMinutes = remember(liveZones) { liveZones.sumOf { it.minutes } }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "HEART RATE ZONES",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.6.sp
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Time spent per intensity zone today",
                                color = Color.White.copy(alpha = 0.3f),
                                fontSize = 10.5.sp,
                                letterSpacing = 0.2.sp
                            )
                        }
                        if (totalMinutes > 0) {
                            Text(
                                text = "Total: $totalMinutes min",
                                color = TextTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    if (liveZones.isNotEmpty()) {
                        // Multi-segment proportional intensity bar
                        if (totalMinutes > 0) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                            ) {
                                liveZones.forEach { zone ->
                                    if (zone.minutes > 0) {
                                        Box(
                                            modifier = Modifier
                                                .weight(zone.minutes.toFloat())
                                                .fillMaxHeight()
                                                .background(zone.color)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            liveZones.forEach { zone ->
                                HrZoneRow(zone = zone, totalMinutes = totalMinutes)
                            }
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(70.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No heart rate zones recorded today", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        // Personal records header
        item(key = "records_header") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "Health Records Highs")
                Spacer(Modifier.height(12.dp))
            }
        }

        // Personal records grid
        recordRows.forEachIndexed { idx, rowPrs ->
            item(key = "record_row_$idx") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowPrs.forEach { pr ->
                        Box(modifier = Modifier.weight(1f)) {
                            PersonalRecordCard(pr = pr)
                        }
                    }
                    if (rowPrs.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Activity consistency
        item(key = "consistency") {
            Spacer(Modifier.height(10.dp))
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "Daily Activity Consistency")
                Spacer(Modifier.height(12.dp))
                ConsistencyGrid(stepTotals)
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun BaselineStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label.uppercase(),
            color = Color.White.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        )
    }
}

/**
 * Premium AMOLED surface matching the Home dashboard cards: pure #111111 surface,
 * hairline gradient border (bright top edge → inner-shadow bottom), and a soft
 * black elevation. No gradients, no oversized icons, no clutter.
 */
@Composable
private fun InsightCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = remember { RoundedCornerShape(18.dp) }
    val borderBrush = remember {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.16f),
            0.5f to Color.White.copy(alpha = 0.05f),
            1f to Color.Black.copy(alpha = 0.38f)
        )
    }
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
            .clip(shape)
            .background(Color(0xFF111111))
            .border(1.dp, borderBrush, shape)
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun WeeklySummaryBanner(
    stepsSum: Long,
    caloriesSum: Long,
    distanceSum: Double,
    avgHr: Int
) {
    InsightCard {
        Text(
            text  = "THIS WEEK",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            WeeklyStatItem("%,d".format(stepsSum), "Steps", StepsViolet)
            WeeklyStatItem("%,d".format(caloriesSum), "Calories", CaloriesOrange)
            WeeklyStatItem("%.1f".format(distanceSum), "Km", NeonBlue)
            WeeklyStatItem(if (avgHr > 0) "$avgHr" else "—", "Avg HR", HeartRateRed)
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.06f))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text  = "Seven-day totals straight from Health Connect.",
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 10.5.sp,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
private fun WeeklyStatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text  = value,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-0.8).sp,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.85f))
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text  = label.uppercase(),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
        }
    }
}

@Composable
private fun PersonalRecordCard(pr: PersonalRecord) {
    InsightCard {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(text = pr.icon, fontSize = 17.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(pr.color.copy(alpha = 0.9f))
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text  = "RECORD",
                    color = pr.color.copy(alpha = 0.9f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text  = pr.value,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp,
                maxLines = 1
            )
            if (pr.unit.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = pr.unit,
                    color = pr.color.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text  = pr.metric.uppercase(),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.1.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text  = pr.date,
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun ConsistencyGrid(stepTotals: Map<String, Long>) {
    val days  = 7
    val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
    val gridStart = today.minusWeeks(3).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

    val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
    val allWeekLabels = listOf("3w ago", "2w ago", "Last wk", "This wk")

    // Find which of the 4 weeks actually have logged step telemetry (> 0 steps)
    val activeWeekIndices = (0..3).filter { w ->
        (0 until days).any { d ->
            val date = gridStart.plusDays((w * days + d).toLong()).toString()
            (stepTotals[date] ?: 0L) > 0
        }
    }.ifEmpty { listOf(3) } // Fallback to "This wk" if empty

    InsightCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text  = "DAILY STEPS ACTIVITY",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp
            )

            Spacer(Modifier.height(16.dp))

            // Week Headers (Columns for active weeks only)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(22.dp)) // Offset for day-of-week labels
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    activeWeekIndices.forEach { wIdx ->
                        Text(
                            text = allWeekLabels[wIdx].uppercase(),
                            color = TextTertiary,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 7 Days (Rows) x Active Weeks (Columns) Heatmap Matrix
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (d in 0 until days) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Day of week label (M, T, W, T, F, S, S)
                        Text(
                            text = dayLabels[d],
                            color = TextTertiary,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(20.dp)
                        )

                        // Active week activity pills (full height 12dp for full days, half height 6dp for partial days)
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            activeWeekIndices.forEach { w ->
                                val date = gridStart.plusDays((w * days + d).toLong()).toString()
                                val steps = stepTotals[date] ?: 0L
                                val (pillHeight, alpha) = when {
                                    steps >= 8000 -> 12.dp to 1.0f
                                    steps >= 5000 -> 12.dp to 0.70f
                                    steps >= 2000 -> 6.dp to 0.50f
                                    steps > 0    -> 6.dp to 0.30f
                                    else          -> 3.dp to 0.05f
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(pillHeight)
                                        .padding(horizontal = 3.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            if (steps > 0) NeonCyan.copy(alpha = alpha)
                                            else Color.White.copy(alpha = 0.05f)
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Heatmap Legend
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(text = "Less", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
                listOf(0.05f, 0.25f, 0.45f, 0.70f, 1.0f).forEach { a ->
                    Spacer(Modifier.width(3.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(NeonCyan.copy(alpha = a))
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(text = "More", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WeekLabelRow(labels: List<String>) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        labels.forEach { label ->
            Text(
                text  = label,
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
    }
}

@Composable
private fun HrZoneRow(zone: com.vanta.app.data.HrZone, totalMinutes: Int) {
    val percent = if (totalMinutes > 0 && zone.minutes > 0) {
        ((zone.minutes.toDouble() / totalMinutes.toDouble()) * 100).roundToInt()
    } else {
        0
    }

    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(28.dp)
                .background(zone.color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = zone.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                )
                if (percent > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(zone.color.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "$percent%",
                            color = zone.color,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(1.dp))
            Text(
                text  = zone.bpm,
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp)
            )
        }

        Text(
            text  = "${zone.minutes} min",
            color = if (zone.minutes > 0) zone.color else TextTertiary,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp
            )
        )
    }
}
