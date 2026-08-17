package com.vanta.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.ui.components.FloatingAreaChart
import com.vanta.app.ui.components.GlassCard
import com.vanta.app.ui.components.MetricTile
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.utils.rememberVantaHaptics
import com.vanta.app.ui.viewmodel.VantaAiUiState
import com.vanta.app.ui.viewmodel.VantaAiViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The three WHOOP-style readiness metrics. Color + gradient config live here. */
enum class PhysiologyMetric(
    val label: String,
    val unit: String,
    val icon: String,
    val ringColor: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val maxValue: Float,
) {
    RECOVERY("Recovery", "%", "🛡", RingRecovery, Color(0xFF2FBF8F), Color(0xFF8CFFD0), 100f),
    STRAIN("Strain", "", "⚡", RingStrain, Color(0xFFFF9F0A), Color(0xFFFF3D2E), 21f),
    ENERGY("Energy", "%", "🔋", RingEnergy, Color(0xFF2F9BFF), Color(0xFF00F5FF), 100f),
}

private data class DetailTile(
    val label: String,
    val value: String,
    val unit: String,
    val accent: Color,
    val supporting: String = "",
)

/**
 * WHOOP-grade deep-dive page for a single readiness metric.
 *
 * Tapping any ring on the dashboard lands here. The page shows a large gradient
 * score ring, a floating 7-day trend chart with peak/low markers, live mini
 * tiles, a deterministic AI insight grounded in today's telemetry, and a guided
 * breathing CTA — all updating live every 60s.
 */
@Composable
fun PhysiologyDetailScreen(
    metric: PhysiologyMetric,
    onBackClick: () -> Unit,
    onBreatheClick: () -> Unit,
    aiViewModel: VantaAiViewModel = viewModel(),
) {
    val haptics = rememberVantaHaptics()
    val uiState by aiViewModel.uiState.collectAsState()
    val history by aiViewModel.historicalRecords.collectAsState()
    val telemetry by aiViewModel.liveTelemetry.collectAsState()
    val baseline by aiViewModel.userBaseline.collectAsState()

    // Live value from the most recent analysis (falls back to last-known).
    var lastKnown by remember { mutableStateOf<Float?>(null) }
    val liveValue = when (val s = uiState) {
        is VantaAiUiState.Success -> when (metric) {
            PhysiologyMetric.RECOVERY -> s.analysis.recovery.toFloat()
            PhysiologyMetric.STRAIN -> s.analysis.strain.toFloat()
            PhysiologyMetric.ENERGY -> s.analysis.energy.toFloat()
        }.also { lastKnown = it }
        else -> lastKnown ?: 0f
    }
    val liveAnalysis = (uiState as? VantaAiUiState.Success)?.analysis
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isDetailedCoachAvailable by aiViewModel.isDetailedCoachAvailable.collectAsState()

    var asyncInlineInsight by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var isVantaCoachExpanded by remember { mutableStateOf(false) }
    var vantaCoachDeepInsight by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var streamedCoachInsightText by remember { mutableStateOf("") }
    var isVantaCoachLoading by remember { mutableStateOf(false) }

    // Non-blocking async inline detail insight fetch (short, verdict-first) — only when AI is available
    LaunchedEffect(metric, liveValue, telemetry, baseline, history, isDetailedCoachAvailable) {
        if (isDetailedCoachAvailable) {
            val result = aiViewModel.getDetailInsight(metric, context)
            if (result.second) {
                asyncInlineInsight = result
            }
            val cachedDeep = aiViewModel.getCachedVantaCoachInsight(metric)
            if (cachedDeep != null) {
                vantaCoachDeepInsight = cachedDeep to true
            }
        } else {
            asyncInlineInsight = null
            vantaCoachDeepInsight = null
            isVantaCoachExpanded = false
        }
    }

    // 7-day trend: DB history (date DESC → oldest-first), forced to end on the
    // live value so the last dot is always "now".
    val today = LocalDate.now(ZoneId.systemDefault())
    val chartData = remember(history, liveValue) {
        val chrono = history.take(7).reversed()
        val fmt = DateTimeFormatter.ofPattern("EEE", Locale.US)
        val labels = chrono.map { rec ->
            val d = runCatching { LocalDate.parse(rec.date) }.getOrNull()
            if (d == today) "Now" else d?.format(fmt) ?: "--"
        }
        val values = chrono.map { rec -> when (metric) {
            PhysiologyMetric.RECOVERY -> rec.recovery.toFloat()
            PhysiologyMetric.STRAIN -> rec.strain.toFloat()
            PhysiologyMetric.ENERGY -> rec.energy.toFloat()
        } }
        when {
            values.isEmpty() -> emptyList<Float>() to emptyList<String>()
            values.last() != liveValue -> (values.dropLast(1) + liveValue) to (labels.dropLast(1) + "Now")
            else -> values to labels
        }
    }

    // Live mini tiles, derived entirely from Health Connect + DB (never mocked).
    val tiles = buildDetailTiles(metric, telemetry, history, baseline, liveValue)

    val status = when (metric) {
        PhysiologyMetric.RECOVERY -> recoveryStatus(liveValue)
        PhysiologyMetric.STRAIN -> strainStatus(liveValue)
        PhysiologyMetric.ENERGY -> energyStatus(liveValue)
    }

    Box(modifier = Modifier.fillMaxSize().background(VantaBlack)) {
        // Immersive ambient gradient wash from the metric's ring color.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            metric.ringColor.copy(alpha = 0.16f),
                            metric.ringColor.copy(alpha = 0.05f),
                            Color.Transparent,
                        )
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // ── Top bar: back + title + LIVE badge ─────────────────────────────
            item(key = "top_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(VantaSurface2)
                            .clickable {
                                haptics.tick()
                                onBackClick()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = metric.label.uppercase(),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            MetricSleekIcon(
                                metric = metric,
                                tint = metric.ringColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${metric.label} Details",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
                            )
                        }
                    }
                }
            }

            // ── Hero gradient score ring ───────────────────────────────────────
            item(key = "hero") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MetricGradientRing(
                        progress = (liveValue / metric.maxValue).coerceIn(0f, 1f),
                        displayValue = when (metric) {
                            PhysiologyMetric.RECOVERY -> liveValue.roundToInt().toString()
                            PhysiologyMetric.STRAIN -> "%.1f".format(liveValue)
                            PhysiologyMetric.ENERGY -> liveValue.roundToInt().toString()
                        },
                        unit = metric.unit,
                        metric = metric,
                        ringSize = 224.dp,
                        strokeWidth = 16.dp
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = status,
                        color = TextPrimary,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = metricSubtitle(metric, liveValue, telemetry, baseline),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 36.dp),
                        maxLines = 2
                    )
                }
            }

            // ── Minimal Seamless "Ask Vanta Coach" (No bulky card, no box) ───────────
            if (isDetailedCoachAvailable) {
                item(key = "ai_insight") {
                    val safeTelemetry = telemetry ?: HealthConnectTelemetry()
                    val infiniteTransition = rememberInfiniteTransition(label = "vanta_coach_shimmer")
                    val shimmerAlpha by infiniteTransition.animateFloat(
                        initialValue = if (isVantaCoachLoading) 0.35f else 1f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "shimmer_alpha"
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Seamless text button floating directly on the background
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptics.tick()
                                    if (isVantaCoachLoading) return@clickable

                                    if (isVantaCoachExpanded) {
                                        isVantaCoachExpanded = false
                                    } else if (vantaCoachDeepInsight != null || streamedCoachInsightText.isNotBlank()) {
                                        isVantaCoachExpanded = true
                                    } else {
                                        isVantaCoachExpanded = true
                                        isVantaCoachLoading = true
                                        streamedCoachInsightText = ""
                                        coroutineScope.launch {
                                            aiViewModel.streamVantaCoachInsight(metric, context).collect { chunk ->
                                                streamedCoachInsightText += chunk
                                            }
                                            if (streamedCoachInsightText.isNotBlank()) {
                                                vantaCoachDeepInsight = streamedCoachInsightText to true
                                            }
                                            isVantaCoachLoading = false
                                        }
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = metric.ringColor.copy(alpha = if (isVantaCoachLoading) shimmerAlpha else 0.9f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isVantaCoachLoading) "Consulting Vanta Coach..." else if (isVantaCoachExpanded) "Vanta Coach" else "Ask Vanta Coach",
                                color = metric.ringColor.copy(alpha = if (isVantaCoachLoading) shimmerAlpha else 0.95f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isVantaCoachExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = metric.ringColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Expanded strictly word-limited insight (verdict-first, tight 2-3 sentences)
                        AnimatedVisibility(
                            visible = isVantaCoachExpanded && (isVantaCoachLoading || vantaCoachDeepInsight != null || streamedCoachInsightText.isNotBlank()),
                            enter = expandVertically(animationSpec = tween(280)) + fadeIn(animationSpec = tween(280)),
                            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
                        ) {
                            val rawCoachText = if (streamedCoachInsightText.isNotBlank()) streamedCoachInsightText else (vantaCoachDeepInsight?.first ?: "")
                            val coachText = remember(rawCoachText, liveValue) {
                                when (metric) {
                                    PhysiologyMetric.STRAIN -> {
                                        val str = "%.1f".format(liveValue)
                                        rawCoachText.replace(Regex("(\\d+\\.\\d+)\\s*/\\s*21(\\.0)?"), "$str / 21.0")
                                            .replace(Regex("(\\d+\\.\\d+)\\s+strain"), "$str strain")
                                    }
                                    PhysiologyMetric.RECOVERY -> {
                                        val rec = liveValue.roundToInt()
                                        rawCoachText.replace(Regex("(\\d+)%\\s+recovery"), "$rec% recovery")
                                            .replace(Regex("Recovery\\s+(?:dipped to|reached|holds at|sits at|at)\\s+(\\d+)%"), "Recovery holds at $rec%")
                                    }
                                    PhysiologyMetric.ENERGY -> {
                                        val nrg = liveValue.roundToInt()
                                        rawCoachText.replace(Regex("(\\d+)%\\s+energy"), "$nrg% energy")
                                            .replace(Regex("Energy\\s+(?:reserves are reduced at|reserves sit high at|is balanced at|at)\\s+(\\d+)%"), "Energy is balanced at $nrg%")
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(VantaSurface2.copy(alpha = 0.6f))
                                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                com.vanta.app.ui.components.StreamingTextEffect(
                                    targetText = if (coachText.isNotBlank()) coachText else null,
                                    isGenerating = isVantaCoachLoading,
                                    style = androidx.compose.ui.text.TextStyle(
                                        color = TextPrimary.copy(alpha = 0.92f),
                                        fontFamily = InterFontFamily,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 13.5.sp,
                                        lineHeight = 20.sp
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(color = metric.ringColor, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Consulting Vanta Coach...", color = TextSecondary, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Floating 7-day trend chart ──────────────────────────────────────
            item(key = "trend_chart") {
                GlassCard(
                    accentColor = metric.ringColor,
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "7-DAY TREND",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "When ${metric.label.lowercase()} ran high & low",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "live",
                            color = metric.ringColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    if (chartData.first.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Building your 7-day baseline from real data…",
                                color = TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        FloatingAreaChart(
                            values = chartData.first,
                            labels = chartData.second,
                            accentColor = metric.ringColor,
                            valueFormat = { v ->
                                when (metric) {
                                    PhysiologyMetric.STRAIN -> "%.1f".format(v)
                                    else -> "${v.roundToInt()}${metric.unit}"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── Live mini tiles (2×2) ──────────────────────────────────────────
            item(key = "live_tiles") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = "LIVE TELEMETRY",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    tiles.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { t ->
                                MetricTile(
                                    label = t.label,
                                    value = t.value,
                                    unit = t.unit,
                                    accentColor = t.accent,
                                    supporting = t.supporting,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── Guided breathing CTA (Recovery screen only) ───────────────────────
            if (metric == PhysiologyMetric.RECOVERY) {
                item(key = "breathe_cta") {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        metric.gradientStart.copy(alpha = 0.22f),
                                        metric.gradientEnd.copy(alpha = 0.10f),
                                        VantaSurface2,
                                    )
                                )
                            )
                            .clickable {
                                haptics.tick()
                                onBreatheClick()
                            }
                            .padding(18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(metric.ringColor.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                BreathingPulse(metric.ringColor)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Breathing Exercise",
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Guided · Box & 4-7-8",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Start →",
                                color = metric.ringColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Hero gradient score ring ──────────────────────────────────────────────────
@Composable
private fun MetricGradientRing(
    progress: Float,
    displayValue: String,
    unit: String,
    metric: PhysiologyMetric,
    ringSize: Dp = 224.dp,
    strokeWidth: Dp = 16.dp,
) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        animated.animateTo(
            progress,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
    }
    Box(modifier = Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            val strokePx = strokeWidth.toPx()
            val diameter = size.minDimension - strokePx
            val topLeft = Offset(strokePx / 2, strokePx / 2)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            val sweep = 360f * animated.value

            // Faint full track
            drawArc(
                color = Color.White.copy(alpha = 0.06f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round)
            )

            if (sweep > 0f) {
                // Gradient sweep — colors[0] sits at 3 o'clock, so rotate the draw
                // scope -90° to start the gradient at 12 o'clock.
                val brush = Brush.sweepGradient(
                    colors = listOf(metric.gradientStart, metric.ringColor, metric.gradientEnd)
                )
                // Soft outer glow
                rotate(degrees = -90f) {
                    drawArc(
                        brush = brush,
                        startAngle = 0f, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize, alpha = 0.30f,
                        style = Stroke(strokePx * 1.9f, cap = StrokeCap.Round)
                    )
                    // Crisp gradient arc
                    drawArc(
                        brush = brush,
                        startAngle = 0f, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(strokePx, cap = StrokeCap.Round)
                    )
                }
                // Rounded white cap at the current position
                val radius = diameter / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val rad = Math.toRadians(-90.0 + sweep)
                val capX = cx + (radius * cos(rad)).toFloat()
                val capY = cy + (radius * sin(rad)).toFloat()
                drawCircle(Color.White, radius = strokePx * 0.34f, center = Offset(capX, capY))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = displayValue,
                color = Color.White,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 52.sp,
                letterSpacing = (-2).sp
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = unit,
                    color = metric.ringColor.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = metric.label.uppercase(),
                color = TextTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp
            )
        }
    }
}

// ── Live badge (pulsing dot) ──────────────────────────────────────────────────
@Composable
private fun LiveBadge() {
    val pulse = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        while (true) {
            pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
            pulse.animateTo(0.4f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x1A39FF80))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(RecoveryGreen.copy(alpha = pulse.value))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "LIVE",
            color = RecoveryGreen,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp
        )
    }
}

// ── Breathing icon (slowly pulsing concentric rings) ─────────────────────────
@Composable
private fun BreathingPulse(color: Color) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            t.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
            t.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
        }
    }
    Canvas(modifier = Modifier.size(34.dp)) {
        val r = (size.minDimension / 2f) * (0.6f + 0.4f * t.value)
        drawCircle(color.copy(alpha = 0.25f), radius = r, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color.copy(alpha = 0.45f), radius = r * 0.7f, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 3.5.dp.toPx())
    }
}


// ── Status labels (WHOOP vocabulary) ──────────────────────────────────────────
private fun recoveryStatus(v: Float): String = when {
    v >= 85 -> "Excellent"
    v >= 70 -> "Great"
    v >= 60 -> "Moderate"
    v >= 40 -> "Fatigued"
    else -> "Depleted"
}

private fun strainStatus(v: Float): String = when {
    v >= 16 -> "Extreme"
    v >= 12 -> "Very High"
    v >= 8 -> "High"
    v >= 4 -> "Moderate"
    else -> "Light"
}

private fun energyStatus(v: Float): String = when {
    v >= 85 -> "High"
    v >= 70 -> "Ready"
    v >= 50 -> "Moderate"
    v >= 30 -> "Low"
    else -> "Drained"
}

// ── Hero subtitle ─────────────────────────────────────────────────────────────
private fun metricSubtitle(
    metric: PhysiologyMetric,
    liveValue: Float,
    telemetry: HealthConnectTelemetry?,
    baseline: com.vanta.app.data.baseline.UserBaseline,
): String = when (metric) {
    PhysiologyMetric.RECOVERY -> {
        val sleep = telemetry?.sleepMinutes ?: 0
        if (sleep > 0) "${sleep} min asleep last night"
        else "Recovery reflects last night's rest and today's activity"
    }
    PhysiologyMetric.STRAIN -> {
        val t = telemetry
        when {
            t == null -> "Today's load, refreshed live from Health Connect"
            t.steps > 0 -> "%,d steps and %,d kcal so far today".format(t.steps, t.calories)
            else -> "Movement is still getting going today"
        }
    }
    PhysiologyMetric.ENERGY -> {
        val sleep = telemetry?.sleepMinutes ?: 0
        val mins = telemetry?.exerciseMinutes ?: 0
        when {
            sleep > 0 && mins > 0 -> "${sleep} min asleep · ${mins} min training today"
            sleep > 0 -> "${sleep} min asleep last night"
            mins > 0 -> "${mins} min of training in today"
            else -> "Energy recharges through sleep and easy movement"
        }
    }
}

// ── Live mini tiles ───────────────────────────────────────────────────────────
private fun buildDetailTiles(
    metric: PhysiologyMetric,
    telemetry: HealthConnectTelemetry?,
    history: List<com.vanta.app.data.db.DailyMetricRecord>,
    baseline: com.vanta.app.data.baseline.UserBaseline,
    liveValue: Float,
): List<DetailTile> = when (metric) {
    PhysiologyMetric.RECOVERY -> {
        val rhr = telemetry?.restingBpm ?: 0
        val sleep = telemetry?.sleepMinutes ?: 0
        val todayStr = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val ydayStrain = history.find { it.date != todayStr }?.strain ?: 0.0
        val avg7 = if (history.isNotEmpty()) history.take(7).map { it.recovery }.average().roundToInt() else 0
        listOf(
            DetailTile("RESTING HR", if (rhr > 0) "$rhr" else "--", "bpm", RingRecovery,
                if (baseline.hasRestingHrBaseline) "vs ${baseline.avgRestingBpm.roundToInt()} norm" else "no overnight reading"),
            DetailTile("SLEEP", if (sleep > 0) "$sleep" else "--", "min", RingRecovery, "last night"),
            DetailTile("7D AVG", "$avg7", "%", RingRecovery, "your baseline"),
            DetailTile("YESTERDAY STRAIN", "%.1f".format(ydayStrain), "", RingRecovery, "previous day"),
        )
    }
    PhysiologyMetric.STRAIN -> {
        val t = telemetry
        listOf(
            DetailTile("STEPS", if (t != null) "%,d".format(t.steps) else "--", "steps", RingStrain, "today"),
            DetailTile("CALORIES", if (t != null) "%,d".format(t.calories) else "--", "kcal", RingStrain, "active burn"),
            DetailTile("DISTANCE", if (t != null) "%.1f".format(t.distanceKm) else "--", "km", RingStrain, "today"),
            DetailTile("AVG HR", if (t != null && t.avgBpm > 0) "${t.avgBpm}" else "--", "bpm", RingStrain, "today's average"),
        )
    }
    PhysiologyMetric.ENERGY -> {
        val t = telemetry
        val rhr = t?.restingBpm ?: 0
        val sleep = t?.sleepMinutes ?: 0
        val hours = t?.hoursSinceLastWorkout
        listOf(
            DetailTile("SLEEP", if (sleep > 0) "$sleep" else "--", "min", RingEnergy, "last night"),
            DetailTile("RESTING HR", if (rhr > 0) "$rhr" else "--", "bpm", RingEnergy, "overnight"),
            DetailTile("TRAINING", if (t != null) "${t.exerciseMinutes}" else "--", "min", RingEnergy, "today"),
            DetailTile("SINCE WORKOUT", if (hours != null) "%.1f".format(hours) else "--", "hrs", RingEnergy, "last session"),
        )
    }
}


// ── AI insight (deterministic, natural coach voice) ─────────────────────────
// Ground rules: never mention a missing metric (especially HR), never list data
// for its own sake, and always frame the state in terms of what it means today.
private fun buildMetricInsight(
    metric: PhysiologyMetric,
    liveValue: Float,
    telemetry: HealthConnectTelemetry?,
    baseline: com.vanta.app.data.baseline.UserBaseline,
    history: List<com.vanta.app.data.db.DailyMetricRecord>,
    analysis: com.vanta.app.data.GemmaAiAnalysis?,
): String {
    val avg7 = if (history.isNotEmpty()) history.take(7).map { it.recovery }.average() else 0.0
    return when (metric) {
        PhysiologyMetric.RECOVERY -> recoveryInsight(liveValue, telemetry, baseline, avg7)
        PhysiologyMetric.STRAIN -> strainInsight(liveValue, telemetry)
        PhysiologyMetric.ENERGY -> energyInsight(liveValue, telemetry)
    }
}

private fun recoveryInsight(
    liveValue: Float,
    t: HealthConnectTelemetry?,
    baseline: com.vanta.app.data.baseline.UserBaseline,
    avg7: Double,
): String {
    val r = liveValue.roundToInt()
    val sleep = t?.sleepMinutes ?: 0
    val rhr = t?.restingBpm ?: 0

    val opening = when {
        sleep > 0 -> "You're at $r% recovery after ${sleep} minutes of sleep."
        r >= 70 -> "You're at $r% recovery — a strong place to start."
        r >= 55 -> "Recovery sits at $r% today. The foundation's there; the depth comes tonight."
        else -> "Recovery's at $r% — the body is asking for a patient day."
    }

    // Resting HR only enters the story when it's genuinely measured and useful.
    val rhrPart = if (rhr in 40..100 && baseline.hasRestingHrBaseline) {
        val delta = rhr - baseline.avgRestingBpm.roundToInt()
        when {
            delta <= -2 -> " A resting heart rate ${-delta} bpm below normal is a strong sign yesterday's load was absorbed."
            delta >= 2 -> " A resting heart rate ${delta} bpm above normal says yesterday's work is still settling."
            else -> " Your resting heart rate is sitting right where it usually does."
        }
    } else ""

    val trendPart = if (avg7 > 0) {
        val diff = r - avg7.roundToInt()
        when {
            diff >= 5 -> " That's ${diff} points above your usual ${avg7.roundToInt()}%."
            diff <= -5 -> " That's ${-diff} points under your usual ${avg7.roundToInt()}%."
            else -> " You're tracking close to your usual ${avg7.roundToInt()}%."
        }
    } else ""

    val action = when {
        r >= 85 -> " Today is a go day — push the intensity while your body's on your side."
        r >= 70 -> " Train with intent today; the engine's ready."
        r >= 55 -> " Keep today's session honest — solid work, no heroics."
        else -> " Keep it light and make sleep tonight the priority."
    }
    return "$opening$rhrPart$trendPart$action"
}

private fun strainInsight(
    liveValue: Float,
    t: HealthConnectTelemetry?,
): String {
    val fmt = "%.1f".format(liveValue)
    val steps = t?.steps ?: 0
    val workout = t?.exerciseMinutes ?: 0

    return when {
        liveValue < 1.0 && steps < 500 && workout == 0 ->
            "Strain is $fmt today. A quiet start — let's see how the day develops."
        liveValue < 3.0 -> {
            val movement = if (steps > 0) " You've covered $steps steps so far." else ""
            "Strain sits at $fmt.$movement You've got plenty of room today — build gradually."
        }
        liveValue < 7.0 -> {
            val work = when {
                workout > 0 -> " A ${workout}-minute session is in the books."
                steps > 0 -> " The day's building with $steps steps behind you."
                else -> ""
            }
            "$fmt strain so far.$work Keep the effort measured — there's more day ahead."
        }
        liveValue < 10.0 -> {
            val work = if (workout > 0) " after today's ${workout}-minute session" else ""
            "Strain's at $fmt$work. That's a real day's work — protect the rest of it."
        }
        else -> {
            val work = if (workout > 0) " You logged a ${workout}-minute session to get there." else ""
            "$fmt strain today.$work The load's banked — recovery matters more than another set now."
        }
    }
}

private fun energyInsight(
    liveValue: Float,
    t: HealthConnectTelemetry?,
): String {
    val e = liveValue.roundToInt()
    val sleep = t?.sleepMinutes ?: 0
    val hours = t?.hoursSinceLastWorkout

    val sleepPart = when {
        sleep >= 480 -> " With ${sleep} minutes of sleep, you're entering the day genuinely recharged."
        sleep in 360 until 480 -> " ${sleep} minutes of sleep leaves you in solid shape."
        sleep > 0 -> " ${sleep} minutes of sleep is on the lighter side, so today's ceiling is a little lower."
        else -> " Without a sleep session tracked, your tank mostly reflects how the day's been going."
    }

    val freshnessPart = when {
        hours != null && hours < 24 -> " You trained ${"%.1f".format(hours)} hours ago, so keep today's output measured."
        else -> " Fresh legs — there's energy available to spend."
    }

    val action = when {
        e >= 75 -> " Front-load the hardest work while it's there."
        e >= 50 -> " Hit the main lifts clean and skip the extras."
        else -> " The win today is easy movement and a good night's sleep."
    }
    return "$e% energy right now.$sleepPart $freshnessPart $action"
}

private data class ActionableAdvice(
    val headline: String,
    val primaryAction: String,
    val secondaryAction: String,
    val icon: String
)

private fun buildActionableAdvice(
    metric: PhysiologyMetric,
    liveValue: Float,
    telemetry: HealthConnectTelemetry?,
    baseline: com.vanta.app.data.baseline.UserBaseline
): ActionableAdvice {
    val hour = java.time.LocalTime.now(java.time.ZoneId.systemDefault()).hour
    val isMorning = hour in 5..11
    val isAfternoon = hour in 12..16
    val isEvening = hour in 17..20
    val isNight = hour >= 21 || hour < 5

    return when (metric) {
        PhysiologyMetric.RECOVERY -> {
            when {
                liveValue >= 67f -> when {
                    isMorning -> ActionableAdvice(
                        headline = "Max Capacity Protocol",
                        primaryAction = "Training Window: High autonomic readiness — optimal timing for compound lifting, intervals, or tempo work.",
                        secondaryAction = "Hydration & Fuel: Start with 500ml water + electrolytes to support cellular hydration.",
                        icon = "⚡"
                    )
                    isAfternoon -> ActionableAdvice(
                        headline = "High Output Execution",
                        primaryAction = "Training: Complete demanding physical tasks before evening to allow autonomic tone to settle.",
                        secondaryAction = "Post-Session: Refuel with 30g protein + complex carbs within 90 minutes of exertion.",
                        icon = "⚡"
                    )
                    isEvening -> ActionableAdvice(
                        headline = "Evening Recovery Protocol",
                        primaryAction = "Nutrition: Consume a balanced dinner 2–3 hours before bed to stabilize nighttime glucose.",
                        secondaryAction = "Circadian Setup: Dim overhead lights by 9:00 PM and cool your room to ~19°C (66°F) for deep slow-wave sleep.",
                        icon = "🎯"
                    )
                    else -> ActionableAdvice(
                        headline = "Restorative Sleep Protocol",
                        primaryAction = "Sleep Target: Aim for 7.5–8.5 hours in a dark, quiet room to maximize cellular tissue repair.",
                        secondaryAction = "Down-Regulation: Eliminate blue-light screen exposure 30 mins before sleep to support melatonin.",
                        icon = "🛡"
                    )
                }
                liveValue >= 34f -> ActionableAdvice(
                    headline = "Balanced Pacing Protocol",
                    primaryAction = "Movement Target: Keep training within Zone 2 aerobic base or moderate volume; avoid all-out failure.",
                    secondaryAction = "Restoration: Hydrate consistently and add 5–10 mins of light mobility or foam rolling.",
                    icon = "🎯"
                )
                else -> ActionableAdvice(
                    headline = "Active Restoration Protocol",
                    primaryAction = "Training Modification: Swap high-intensity workouts for a 20–30 min gentle walk or easy yoga.",
                    secondaryAction = "Recovery Boost: Supplement with magnesium glycinate, hydrate thoroughly, and get to bed 45 mins earlier.",
                    icon = "🛡"
                )
            }
        }
        PhysiologyMetric.STRAIN -> {
            when {
                liveValue >= 14f -> ActionableAdvice(
                    headline = "Post-Strain Recovery Protocol",
                    primaryAction = "Glycogen & Protein: Ingest 30–40g complete protein and complex carbs to accelerate muscular rebuild.",
                    secondaryAction = "Down-Regulation: Take a warm shower followed by light static stretching to lower sympathetic heart rate.",
                    icon = "🔥"
                )
                liveValue >= 8f -> ActionableAdvice(
                    headline = "Stimulus Banked Protocol",
                    primaryAction = "Daily Effort: Ideal training load achieved — cap further heavy physical exertion for the day.",
                    secondaryAction = "Hydration: Rehydrate with water and electrolytes to replace sweat mineral losses.",
                    icon = "🎯"
                )
                else -> when {
                    isMorning || isAfternoon -> ActionableAdvice(
                        headline = "Active Exertion Protocol",
                        primaryAction = "Training Window: Ample physical capacity remaining — schedule a 30–45 min structured session.",
                        secondaryAction = "Movement Goal: Accumulate movement earlier in the day to optimize nighttime sleep pressure.",
                        icon = "⚡"
                    )
                    else -> ActionableAdvice(
                        headline = "Evening Movement Protocol",
                        primaryAction = "Light Stimulus: Take a relaxing 15–20 min evening walk to aid digestion and sleep onset.",
                        secondaryAction = "Night Rest: Keep workouts light to maintain a low resting core temperature before bed.",
                        icon = "🌙"
                    )
                }
            }
        }
        PhysiologyMetric.ENERGY -> {
            when {
                liveValue >= 70f -> when {
                    isMorning || isAfternoon -> ActionableAdvice(
                        headline = "Peak Performance Protocol",
                        primaryAction = "Focus Blocks: Dedicate 90-minute deep focus sprints to your hardest mental or physical tasks.",
                        secondaryAction = "Caffeine Curfew: Cut off coffee and stimulants by 2:00 PM to protect tonight's sleep latency.",
                        icon = "🔋"
                    )
                    else -> ActionableAdvice(
                        headline = "Energy Taper Protocol",
                        primaryAction = "Evening Transition: Shift from intense cognitive work into relaxing, low-stimulation activities.",
                        secondaryAction = "Environment: Use warm ambient lighting to signal natural circadian wind-down.",
                        icon = "🔋"
                    )
                }
                liveValue >= 40f -> ActionableAdvice(
                    headline = "Balanced Energy Reserve",
                    primaryAction = "Pace your energy output evenly. Take short 10-min active breaks to maintain mental focus.",
                    secondaryAction = "Stay hydrated and get 15 mins of outdoor sunlight to sustain natural energy levels.",
                    icon = "🌱"
                )
                else -> ActionableAdvice(
                    headline = "Low Energy Reserves",
                    primaryAction = "Energy is low — avoid forcing high-intensity effort or excessive caffeine late in the day.",
                    secondaryAction = "Do a 10-min guided Box Breathing session, drink cold water, and prioritize an early bedtime.",
                    icon = "🌙"
                )
            }
        }
    }
}

@Composable
fun MetricSleekIcon(
    metric: PhysiologyMetric,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (metric) {
            PhysiologyMetric.STRAIN -> {
                // Sleek geometric lightning bolt
                val bolt = Path().apply {
                    moveTo(w * 0.58f, 0f)
                    lineTo(w * 0.16f, h * 0.54f)
                    lineTo(w * 0.48f, h * 0.54f)
                    lineTo(w * 0.40f, h * 1.0f)
                    lineTo(w * 0.84f, h * 0.44f)
                    lineTo(w * 0.52f, h * 0.44f)
                    close()
                }
                drawPath(bolt, color = tint)
            }
            PhysiologyMetric.RECOVERY -> {
                // Sleek shield with inner pulse check
                val shieldPath = Path().apply {
                    moveTo(w * 0.5f, 0f)
                    lineTo(w * 0.92f, h * 0.18f)
                    lineTo(w * 0.92f, h * 0.54f)
                    quadraticBezierTo(w * 0.92f, h * 0.86f, w * 0.5f, h * 1.0f)
                    quadraticBezierTo(w * 0.08f, h * 0.86f, w * 0.08f, h * 0.54f)
                    lineTo(w * 0.08f, h * 0.18f)
                    close()
                }
                drawPath(shieldPath, color = tint.copy(alpha = 0.20f))
                drawPath(shieldPath, color = tint, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round))
                drawLine(
                    color = tint,
                    start = Offset(w * 0.35f, h * 0.50f),
                    end = Offset(w * 0.48f, h * 0.63f),
                    strokeWidth = w * 0.10f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = tint,
                    start = Offset(w * 0.48f, h * 0.63f),
                    end = Offset(w * 0.68f, h * 0.38f),
                    strokeWidth = w * 0.10f,
                    cap = StrokeCap.Round
                )
            }
            PhysiologyMetric.ENERGY -> {
                // Sleek battery cell with pulse zap
                val bodyW = w * 0.74f
                val bodyH = h * 0.60f
                val topY = (h - bodyH) / 2f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(0f, topY),
                    size = Size(bodyW, bodyH),
                    cornerRadius = CornerRadius(w * 0.10f),
                    style = Stroke(width = w * 0.10f)
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(bodyW + w * 0.04f, topY + bodyH * 0.25f),
                    size = Size(w * 0.14f, bodyH * 0.50f),
                    cornerRadius = CornerRadius(w * 0.04f)
                )
                val boltPath = Path().apply {
                    moveTo(bodyW * 0.55f, topY + bodyH * 0.18f)
                    lineTo(bodyW * 0.32f, topY + bodyH * 0.52f)
                    lineTo(bodyW * 0.50f, topY + bodyH * 0.52f)
                    lineTo(bodyW * 0.45f, topY + bodyH * 0.82f)
                    lineTo(bodyW * 0.68f, topY + bodyH * 0.48f)
                    lineTo(bodyW * 0.50f, topY + bodyH * 0.48f)
                    close()
                }
                drawPath(boltPath, color = tint)
            }
        }
    }
}

@Composable
fun AiCoachSparkleIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val star = Path().apply {
            moveTo(w * 0.5f, 0f)
            quadraticBezierTo(w * 0.5f, h * 0.5f, w * 1.0f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, h * 1.0f)
            quadraticBezierTo(w * 0.5f, h * 0.5f, 0f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, 0f)
            close()
        }
        drawPath(star, color = tint)
    }
}

