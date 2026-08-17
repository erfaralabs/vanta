package com.vanta.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.data.DayStepData
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.HourlyStepData
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.VantaDatabase
import com.vanta.app.ui.components.*
import com.vanta.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun StepsScreen(
    onHealthConnectClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember { HealthConnectManager(context) }
    val aiViewModel: com.vanta.app.ui.viewmodel.VantaAiViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val liveTelemetry by aiViewModel.liveTelemetry.collectAsState()
    val userBaseline by aiViewModel.userBaseline.collectAsState()
    val dbRecords by aiViewModel.historicalRecords.collectAsState()

    val todayStr = remember { LocalDate.now(ZoneId.systemDefault()).toString() }
    val cached = remember { manager.getCached7DaysData?.takeIf { it.firstOrNull()?.isoDate == todayStr } }
    var stepDaysData by remember { mutableStateOf(cached ?: emptyList()) }
    var selectedDayIndex by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(cached == null) }

    // Live physiology engine for today
    val detToday = remember(liveTelemetry, userBaseline) {
        val t = liveTelemetry ?: com.vanta.app.data.HealthConnectTelemetry()
        com.vanta.app.data.VantaDeterministicPhysiologyEngine(context).calculatePhysiology(t, userBaseline)
    }

    // Reactively update today's card if ViewModel gets higher live steps
    LaunchedEffect(liveTelemetry) {
        val cur = liveTelemetry ?: return@LaunchedEffect
        val todayIdx = stepDaysData.indexOfFirst { it.dayLabel.equals("Today", ignoreCase = true) || it.isoDate == todayStr }
        if (todayIdx >= 0) {
            val existing = stepDaysData[todayIdx]
            if (cur.steps.toInt() != existing.totalSteps) {
                stepDaysData = stepDaysData.toMutableList().also {
                    it[todayIdx] = existing.copy(
                        totalSteps = cur.steps.toInt(),
                        caloriesKcal = cur.calories.toInt(),
                        distanceKm = cur.distanceKm.toFloat()
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val liveGoal = manager.currentStepsGoal()
            val dateFormatter = DateTimeFormatter.ofPattern("EEE", java.util.Locale.US)
            val fullDateFormatter = DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)

            // Instant first-paint from Room DB ensuring Today is ALWAYS index 0
            if (stepDaysData.isEmpty()) {
                val db = VantaDatabase.getInstance(context)
                val recentDb = db.dailyMetricsDao().getRecentRecords(7)
                val todayDb = recentDb.find { it.date == todayStr }

                val quickList = mutableListOf<DayStepData>()
                if (todayDb != null) {
                    val parsedDate = runCatching { LocalDate.parse(todayDb.date) }.getOrNull()
                    quickList.add(
                        DayStepData(
                            dayLabel = "Today",
                            dateLabel = parsedDate?.format(fullDateFormatter) ?: todayDb.date,
                            totalSteps = todayDb.steps.toInt(),
                            goalSteps = liveGoal,
                            distanceKm = todayDb.distanceKm.toFloat(),
                            caloriesKcal = todayDb.calories.toInt(),
                            activeTimeMin = (todayDb.steps / 100).toInt(),
                            flightsClimbed = (todayDb.steps / 400).toInt(),
                            avgPaceMinPerKm = "5'20\" /km",
                            peakHourLabel = "--",
                            hourlySteps = emptyList(),
                            isoDate = todayDb.date,
                            strain = todayDb.strain,
                            recovery = todayDb.recovery,
                            energy = todayDb.energy
                        )
                    )
                } else {
                    val t = liveTelemetry
                    val todaySteps = t?.steps?.toInt() ?: 0
                    val todayCals = t?.calories?.toInt() ?: 0
                    val todayDist = t?.distanceKm?.toFloat() ?: 0f
                    quickList.add(
                        DayStepData(
                            dayLabel = "Today",
                            dateLabel = LocalDate.now(ZoneId.systemDefault()).format(fullDateFormatter),
                            totalSteps = todaySteps,
                            goalSteps = liveGoal,
                            distanceKm = todayDist,
                            caloriesKcal = todayCals,
                            activeTimeMin = (todaySteps / 100),
                            flightsClimbed = (todaySteps / 400),
                            avgPaceMinPerKm = "5'20\" /km",
                            peakHourLabel = "--",
                            hourlySteps = emptyList(),
                            isoDate = todayStr,
                            strain = detToday.strain,
                            recovery = detToday.recovery,
                            energy = detToday.energy
                        )
                    )
                }

                recentDb.filter { it.date != todayStr }.forEach { rec ->
                    val parsedDate = runCatching { LocalDate.parse(rec.date) }.getOrNull()
                    val dayLabel = parsedDate?.format(dateFormatter) ?: rec.date
                    val dateLabel = parsedDate?.format(fullDateFormatter) ?: rec.date
                    quickList.add(
                        DayStepData(
                            dayLabel = dayLabel,
                            dateLabel = dateLabel,
                            totalSteps = rec.steps.toInt(),
                            goalSteps = liveGoal,
                            distanceKm = rec.distanceKm.toFloat(),
                            caloriesKcal = rec.calories.toInt(),
                            activeTimeMin = (rec.steps / 100).toInt(),
                            flightsClimbed = (rec.steps / 400).toInt(),
                            avgPaceMinPerKm = "5'20\" /km",
                            peakHourLabel = "--",
                            hourlySteps = emptyList(),
                            isoDate = rec.date,
                            strain = rec.strain,
                            recovery = rec.recovery,
                            energy = rec.energy
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    if (stepDaysData.isEmpty()) {
                        stepDaysData = quickList.take(7)
                        selectedDayIndex = 0
                        isLoading = false
                    }
                }
            }

            val liveData = manager.fetchPast7DaysStepData()
            withContext(Dispatchers.Main) {
                if (liveData.isNotEmpty()) {
                    stepDaysData = liveData.map { it.copy(goalSteps = liveGoal) }
                    val todayIdx = stepDaysData.indexOfFirst { it.dayLabel.equals("Today", ignoreCase = true) || it.isoDate == todayStr }
                    selectedDayIndex = if (todayIdx >= 0) todayIdx else 0

                    if (todayIdx >= 0) {
                        val todayData = stepDaysData[todayIdx]
                        aiViewModel.updateLiveSteps(
                            steps = todayData.totalSteps.toLong(),
                            calories = todayData.caloriesKcal.toLong(),
                            distanceKm = todayData.distanceKm.toDouble()
                        )
                    }
                }
                isLoading = false
            }
        }
    }

    val liveGoal = manager.currentStepsGoal()
    val zeroDay = DayStepData("Today", "", 0, liveGoal, 0f, 0, 0, 0, "--", "--", emptyList(), todayStr)
    val activeDay = stepDaysData.getOrNull(selectedDayIndex) ?: stepDaysData.firstOrNull() ?: zeroDay

    // Look up physiology for selected day directly from Room DB records or live telemetry
    val isSelectedToday = activeDay.dayLabel.equals("Today", ignoreCase = true) || activeDay.isoDate == todayStr
    val activeRecord: DailyMetricRecord? = remember(activeDay.isoDate, dbRecords) {
        dbRecords.find { it.date == activeDay.isoDate }
    }

    val selectedStrain = if (isSelectedToday) detToday.strain else (activeRecord?.strain ?: activeDay.strain)
    val selectedRecovery = if (isSelectedToday) detToday.recovery else (activeRecord?.recovery ?: activeDay.recovery)
    val selectedEnergy = if (isSelectedToday) detToday.energy else (activeRecord?.energy ?: activeDay.energy)

    val currentHour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }

    // On Today, strictly show only elapsed hours up to current time (never future hours)
    val displayHourlySteps = remember(activeDay.hourlySteps, isSelectedToday, currentHour) {
        if (isSelectedToday) {
            activeDay.hourlySteps.filterIndexed { index, _ -> index <= currentHour }
        } else {
            activeDay.hourlySteps
        }
    }

    val dynamicPeakHour = remember(displayHourlySteps) {
        val peak = displayHourlySteps.maxByOrNull { it.steps }
        if (peak != null && peak.steps > 0) peak.hourLabel else "--"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VantaBlack)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 140.dp)
        ) {
            // ── Screen Title Header ───────────────────────────────────────────────
            item(key = "title_header") {
                Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
                    SectionHeader(title = "Daily Activity & Steps")
                    Spacer(Modifier.height(14.dp))
                }
            }

            // Real loading state
            if (isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = StepsViolet,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Loading your activity from Health Connect…",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (!isLoading) {
                // ── 1. Past 7 Days Horizontal Tab Bar ──────────────────────────────
                item(key = "day_tabs") {
                    Past7DaysTabBar(
                        days = stepDaysData,
                        selectedIndex = selectedDayIndex,
                        onDaySelected = { selectedDayIndex = it }
                    )
                    Spacer(Modifier.height(18.dp))
                }

                // ── 2. Senior Designer Tri-Metric Capsule (Strain, Recovery, Energy) ──
                item(key = "tri_metric_capsule", contentType = "physiology_pill") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        DailyPhysiologyPillCard(
                            strain = selectedStrain,
                            recovery = selectedRecovery,
                            energy = selectedEnergy,
                            dayLabel = activeDay.dayLabel
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // ── 3. Hero Steps Ring + Summary Card ──────────────────────────────
                item(key = "hero_card", contentType = "hero") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        StepsHeroCard(dayData = activeDay)
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // ── 4. Quick Stats Row (Distance & Calories) ────────────────────────
                item(key = "stats_row", contentType = "stats") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StepStatBox(
                                icon = "📏",
                                value = "%.1f".format(activeDay.distanceKm),
                                unit = "km",
                                label = "Distance",
                                color = NeonCyan,
                                modifier = Modifier.weight(1f)
                            )
                            StepStatBox(
                                icon = "🔥",
                                value = "%,d".format(activeDay.caloriesKcal),
                                unit = "kcal",
                                label = "Active Energy",
                                color = CaloriesOrange,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }

                // ── 5. Hourly Breakdown Canvas Chart ────────────────────────────────
                item(key = "hourly_chart") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        GlassCard(accentColor = StepsViolet) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Hourly Breakdown",
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Peak Activity: $dynamicPeakHour",
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(StepsViolet.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                    Text(
                                        text = activeDay.dateLabel,
                                        color = StepsViolet,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            HourlyStepBarChart(
                                hourlyData = displayHourlySteps,
                                isToday = isSelectedToday,
                                accentColor = StepsViolet,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }

                // ── 6. 7-Day Weekly Comparison Chart ───────────────────────────────
                item(key = "weekly_chart") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        GlassCard(accentColor = NeonCyan) {
                            Text(
                                text = "Weekly Activity History",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "7-Day Step Progression vs Target",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(16.dp))

                            WeeklyStepBarChart(
                                days = stepDaysData,
                                selectedIndex = selectedDayIndex,
                                onDaySelected = { selectedDayIndex = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Senior Designer Minimalist Tri-Metric Capsule (Direct from DB)
 * Displays STRAIN, RECOVERY, and ENERGY in a bespoke beveled dark capsule.
 */
@Composable
private fun DailyPhysiologyPillCard(
    strain: Double,
    recovery: Int,
    energy: Int,
    dayLabel: String,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(36.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = pillShape,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.7f)
            )
            .clip(pillShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF14171E),
                        Color(0xFF0C0E12)
                    )
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color(0x33FFFFFF),
                        Color(0x11FFFFFF)
                    )
                ),
                shape = pillShape
            )
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 1. STRAIN Dial ──────────────────────────────────────────────
            PhysiologyDialItem(
                valueText = "%.1f".format(strain),
                labelText = "STRAIN",
                progressFraction = (strain / 21.0).toFloat().coerceIn(0f, 1f),
                accentColor = NeonCyan,
                modifier = Modifier.weight(1f)
            )

            // Vertical Separator
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(Color(0x14FFFFFF))
            )

            // ── 2. RECOVERY Dial ────────────────────────────────────────────
            PhysiologyDialItem(
                valueText = "$recovery%",
                labelText = "RECOVERY",
                progressFraction = (recovery / 100f).coerceIn(0f, 1f),
                accentColor = RecoveryGreen,
                modifier = Modifier.weight(1f)
            )

            // Vertical Separator
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(Color(0x14FFFFFF))
            )

            // ── 3. ENERGY Dial ──────────────────────────────────────────────
            PhysiologyDialItem(
                valueText = "$energy",
                labelText = "ENERGY",
                progressFraction = (energy / 100f).coerceIn(0f, 1f),
                accentColor = NeonBlue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Individual circular dial with butter-smooth animated arc.
 */
@Composable
private fun PhysiologyDialItem(
    valueText: String,
    labelText: String,
    progressFraction: Float,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    // 60/120fps smooth animated progress
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "dial_progress_$labelText"
    )

    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(76.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background track and animated progress arc
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                val strokeWidthPx = 4.5.dp.toPx()
                val diameter = size.minDimension
                val arcSize = Size(diameter, diameter)
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)

                // Outer background track
                drawArc(
                    color = Color(0xFF1E232B),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )

                // Glowing animated accent arc (starts at -90deg / 12 o'clock)
                if (animatedProgress > 0.001f) {
                    drawArc(
                        color = accentColor,
                        startAngle = -90f,
                        sweepAngle = animatedProgress * 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                    )
                }
            }

            // Inset circular core well
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F1116)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = valueText,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp,
                        letterSpacing = (-0.3).sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = labelText,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.5.sp,
                letterSpacing = 1.1.sp
            ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Past7DaysTabBar(
    days: List<DayStepData>,
    selectedIndex: Int,
    onDaySelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        days.forEachIndexed { index, day ->
            val isSelected = index == selectedIndex
            val shape = RoundedCornerShape(12.dp)
            val haptics = com.vanta.app.ui.utils.rememberVantaHaptics()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(
                        if (isSelected) StepsViolet.copy(alpha = 0.22f)
                        else Color(0xFF13151A)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) StepsViolet else Color(0x1FFFFFFF),
                        shape = shape
                    )
                    .clickable {
                        haptics.click()
                        onDaySelected(index)
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = day.dayLabel,
                        color = if (isSelected) Color.White else TextSecondary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 11.sp
                        )
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (day.totalSteps > 0) "%,d".format(day.totalSteps) else "--",
                        color = if (isSelected) StepsViolet else TextTertiary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StepsHeroCard(dayData: DayStepData) {
    val progress = (dayData.totalSteps.toFloat() / dayData.goalSteps.toFloat()).coerceIn(0f, 1f)

    GlassCard(accentColor = StepsViolet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dayData.dayLabel.uppercase(),
                    color = StepsViolet,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (dayData.totalSteps == 0 && dayData.dayLabel == "Today") "--" else "%,d".format(dayData.totalSteps),
                    color = Color.White,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 34.sp,
                        letterSpacing = (-1).sp
                    )
                )
                Text(
                    text = "Goal: %,d steps".format(dayData.goalSteps),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.width(16.dp))

            CircularMetricRing(
                label = "Goal",
                value = dayData.totalSteps.toFloat(),
                maxValue = dayData.goalSteps.toFloat(),
                displayValue = "${(progress * 100).roundToInt()}",
                unit = "%",
                accentColor = StepsViolet,
                ringSize = 92.dp,
                strokeWidth = 7.5.dp
            )
        }
    }
}

@Composable
private fun StepStatBox(
    icon: String,
    value: String,
    unit: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val shape = remember { RoundedCornerShape(16.dp) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF101216))
            .border(1.dp, Color(0x1FFFFFFF), shape)
            .padding(14.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
            )
            Text(
                text = unit,
                color = color,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            )
        }
    }
}

@Composable
private fun HourlyStepBarChart(
    hourlyData: List<HourlyStepData>,
    isToday: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    if (hourlyData.isEmpty()) return
    val maxSteps = remember(hourlyData) { hourlyData.maxOf { it.steps }.coerceAtLeast(1) }
    var selectedHourIndex by remember { mutableStateOf<Int?>(null) }
    val haptics = com.vanta.app.ui.utils.rememberVantaHaptics()

    Column(modifier = modifier) {
        // Floating Depth Badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selectedHourIndex != null && selectedHourIndex in hourlyData.indices) {
                val h = hourlyData[selectedHourIndex!!]
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF24153B),
                                    Color(0xFF191029)
                                )
                            )
                        )
                        .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${h.hourLabel}  •  %,d steps".format(h.steps),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.3.sp
                            )
                        )
                    }
                }
            } else {
                Text(
                    text = if (isToday) "Activity recorded up to now (tap to inspect)" else "Tap any hour bar to inspect timeline",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Recessed Inner Chart Well
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0B0D12))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            // Subtle horizontal baseline grid
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineY1 = size.height * 0.33f
                val lineY2 = size.height * 0.66f
                drawLine(
                    color = Color(0x0CFFFFFF),
                    start = Offset(0f, lineY1),
                    end = Offset(size.width, lineY1),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color(0x0CFFFFFF),
                    start = Offset(0f, lineY2),
                    end = Offset(size.width, lineY2),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Recessed Grooves and Gradient Filled 3D Bars
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                hourlyData.forEachIndexed { index, hour ->
                    val isSelected = selectedHourIndex == index
                    val targetFraction = if (hour.steps > 0) (hour.steps.toFloat() / maxSteps.toFloat()).coerceIn(0.10f, 1f) else 0.04f
                    val animatedFraction by animateFloatAsState(
                        targetValue = targetFraction,
                        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
                        label = "hour_fraction_$index"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 1.5.dp)
                            .clickable {
                                haptics.click()
                                selectedHourIndex = if (selectedHourIndex == index) null else index
                            },
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        // Background machined groove channel
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF141720))
                        )

                        // 3D Gradient active step bar
                        if (hour.steps > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(animatedFraction)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.White,
                                                    Color(0xFFE9D5FF),
                                                    accentColor
                                                )
                                            )
                                        } else {
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0xFFD8B4FE),
                                                    accentColor,
                                                    Color(0xFF4C1D95)
                                                )
                                            )
                                        }
                                    )
                            )
                        } else {
                            // Subdued inactive slot cap
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFF1E222D))
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Time axis labels — adaptive for elapsed day or full 24h
        if (isToday) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "12 AM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                if (hourlyData.size > 7) {
                    Text(text = "6 AM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                }
                if (hourlyData.size > 13) {
                    Text(text = "12 PM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                }
                Text(
                    text = "Now (${hourlyData.lastOrNull()?.hourLabel ?: ""})",
                    color = StepsViolet,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "12 AM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                Text(text = "6 AM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                Text(text = "12 PM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                Text(text = "6 PM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                Text(text = "11 PM", color = TextTertiary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
            }
        }
    }
}

@Composable
private fun WeeklyStepBarChart(
    days: List<DayStepData>,
    selectedIndex: Int,
    onDaySelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) return
    val maxDaily = remember(days) { days.maxOf { it.totalSteps }.coerceAtLeast(10000) }
    val haptics = com.vanta.app.ui.utils.rememberVantaHaptics()

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEachIndexed { index, day ->
                val isSelected = index == selectedIndex
                val fraction = (day.totalSteps.toFloat() / maxDaily.toFloat()).coerceIn(0.05f, 1f)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            haptics.click()
                            onDaySelected(index)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(
                                if (isSelected) StepsViolet
                                else if (day.totalSteps >= day.goalSteps) NeonCyan.copy(alpha = 0.85f)
                                else Color(0xFF262A33)
                            )
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            days.forEachIndexed { index, day ->
                val isSelected = index == selectedIndex
                Text(
                    text = day.dayLabel.take(3),
                    color = if (isSelected) StepsViolet else TextSecondary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
