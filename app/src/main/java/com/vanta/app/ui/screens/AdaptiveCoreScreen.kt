package com.vanta.app.ui.screens

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vanta.app.data.AiProvider
import com.vanta.app.data.intelligence.AdaptiveIntelligenceEngine
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.viewmodel.VantaAiViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AdaptiveCoreScreen(
    modifier: Modifier = Modifier,
    aiViewModel: VantaAiViewModel = viewModel()
) {
    val baseline by aiViewModel.userBaseline.collectAsState()
    val core = baseline.adaptiveCore
    val vantixInsight by aiViewModel.vantixInsight.collectAsState()
    val vantixInsightLoading by aiViewModel.vantixInsightLoading.collectAsState()
    val isVantixAvailable by aiViewModel.isVantixAvailable.collectAsState()

    // Trigger insight load when core activates (once per composition)
    LaunchedEffect(core != null) {
        if (core != null) aiViewModel.loadVantixInsight()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(VantaBlack),
        contentPadding = PaddingValues(bottom = 160.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
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
                            text = "VANTIX",
                            color = TextPrimary,
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-1).sp
                            )
                        )
                        Text(
                            text = "Training load, recovery & fitness age",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // Status badge
                    val badgeShape = RoundedCornerShape(8.dp)
                    if (core != null) {
                        Box(
                            modifier = Modifier
                                .clip(badgeShape)
                                .background(NeonCyan.copy(alpha = 0.12f))
                                .border(1.dp, NeonCyan.copy(alpha = 0.3f), badgeShape)
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "ACTIVE",
                                color = NeonCyan,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.2.sp
                                )
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(badgeShape)
                                .background(EnergyAmber.copy(alpha = 0.12f))
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "CALIBRATING",
                                color = EnergyAmber,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.2.sp
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // ── Calibration progress / status card ────────────────────────────────
        item(key = "status_card") {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                CoreCard {
                    val daysTracked = core?.totalDaysTracked ?: baseline.savedDaysCount
                    val progress = (daysTracked / 14f).coerceIn(0f, 1f)
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(900, easing = LinearOutSlowInEasing),
                        label = "core_progress"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (core != null) "VANTIX ONLINE"
                                   else "CALIBRATING · DAY ${daysTracked.coerceAtLeast(1)} OF 14",
                            color = if (core != null) NeonCyan else EnergyAmber,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                        )
                        Text(
                            text = if (core != null) "${core.totalDaysTracked} days tracked"
                                   else "$daysTracked / 14",
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // Progress bar only while calibrating; hidden once VANTIX is online
                    // (a full / redundant bar on an "ONLINE" card looks unfinished).
                    if (core == null) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = EnergyAmber,
                            trackColor = Color.White.copy(alpha = 0.08f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = baseline.subtleStatusMessage
                            .replace("Adaptive Core", "VANTIX")
                            .replace("adaptive core", "VANTIX"),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp)
                    )

                    if (core != null) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CoreStatChip(
                                label = "MODE",
                                value = if (core.isTrainingMode) "TRAINING" else "MOVER",
                                color = if (core.isTrainingMode) RecoveryGreen else NeonBlue
                            )
                            CoreStatChip(
                                label = "DAYS TRACKED",
                                value = "${core.totalDaysTracked}",
                                color = NeonCyan
                            )
                            CoreStatChip(
                                label = "CONSISTENCY",
                                value = "${(core.activityConsistency * 100).roundToInt()}%",
                                color = StepsViolet
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        if (core == null) {
            // ── No-data state ─────────────────────────────────────────────────
            item(key = "no_data") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CoreCard {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "⚡",
                                fontSize = 36.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Text(
                                text = "Fitness Age is ready at 14 days of data",
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "With 14+ days of data, Vanta estimates your fitness age from training load, recovery and activity trends.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.Center
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            // Tease: no tracker? Still useful
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Works with or without a fitness tracker — phone steps and activity data are enough to build your baseline.",
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    lineHeight = 16.sp,
                                    textAlign = TextAlign.Center
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            // ── Training Load Card (ATL / CTL / TSB) ─────────────────────────
            item(key = "load_card") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    CoreSectionHeader(if (core.isTrainingMode) "Training Load Intelligence" else "Activity Load")
                    Spacer(Modifier.height(12.dp))
                    CoreCard {
                        // Load status badge
                        val statusColor = when (core.loadStatus) {
                            AdaptiveIntelligenceEngine.LoadStatus.OVERREACHING -> HeartRateRed
                            AdaptiveIntelligenceEngine.LoadStatus.OPTIMAL -> RecoveryGreen
                            AdaptiveIntelligenceEngine.LoadStatus.UNDERLOADED -> EnergyAmber
                            AdaptiveIntelligenceEngine.LoadStatus.DAILY_MOVER -> NeonBlue
                            AdaptiveIntelligenceEngine.LoadStatus.INSUFFICIENT_DATA -> TextTertiary
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = core.loadStatus.label.uppercase(),
                                color = statusColor,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.8.sp
                                )
                            )
                            // ATL:CTL ratio pill
                            val ratioStr = "%.2f".format(core.atlCtlRatio)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(statusColor.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "ATL:CTL $ratioStr",
                                    color = statusColor,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = core.loadStatus.description,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp)
                        )

                        Spacer(Modifier.height(20.dp))

                        // ATL bar
                        val atlMax = maxOf(core.atl, core.ctl, 1.0)
                        LoadBar(
                            label = "ATL",
                            sublabel = "Acute — this week",
                            value = core.atl,
                            maxValue = atlMax * 1.2,
                            color = RingStrain,
                            trend = core.atlTrend
                        )
                        Spacer(Modifier.height(14.dp))
                        // CTL bar
                        LoadBar(
                            label = "CTL",
                            sublabel = "Long-term — 42-day base",
                            value = core.ctl,
                            maxValue = atlMax * 1.2,
                            color = RecoveryGreen,
                            trend = null
                        )

                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        Spacer(Modifier.height(14.dp))

                        // TSB freshness row
                        TsbRow(tsb = core.tsb)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            // ── Readiness trend card ──────────────────────────────────────────
            item(key = "readiness_card") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    CoreSectionHeader("Readiness Trajectory")
                    Spacer(Modifier.height(12.dp))
                    CoreCard {
                        val trend = core.readinessTrend
                        val trendLabel = when {
                            trend > 1.5 -> "↑ Improving fast"
                            trend > 0.3 -> "↑ Improving"
                            trend < -1.5 -> "↓ Declining fast"
                            trend < -0.3 -> "↓ Declining"
                            else -> "→ Stable"
                        }
                        val trendColor = when {
                            trend > 0.3 -> RecoveryGreen
                            trend < -0.3 -> HeartRateRed
                            else -> EnergyAmber
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "7-DAY RECOVERY TREND",
                                color = Color.White.copy(alpha = 0.45f),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.2.sp,
                                    fontSize = 9.sp
                                )
                            )
                            Text(
                                text = trendLabel,
                                color = trendColor,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CoreStatChip(
                                label = "TREND SLOPE",
                                value = "${if (trend >= 0) "+" else ""}${"%.1f".format(trend)}/day",
                                color = trendColor
                            )
                            CoreStatChip(
                                label = "EFFORT CEILING",
                                value = "${"%.1f".format(core.strainCeiling)} / 21",
                                color = StrainColor
                            )
                            CoreStatChip(
                                label = "MODE",
                                value = if (core.isTrainingMode) "Training" else "Daily Mover",
                                color = if (core.isTrainingMode) RecoveryGreen else NeonBlue
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            // ── Step trend card (Daily Mover users) ───────────────────────────
            if (!core.isTrainingMode) {
                item(key = "step_trend_card") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        CoreSectionHeader("Movement Trend")
                        Spacer(Modifier.height(12.dp))
                        CoreCard {
                            val sTrend = core.stepTrend
                            val trendLabel = when {
                                sTrend > 400  -> "↑ Building up"
                                sTrend > 100  -> "↑ Gradually increasing"
                                sTrend < -400 -> "↓ Dropping off"
                                sTrend < -100 -> "↓ Slightly declining"
                                else          -> "→ Holding steady"
                            }
                            val trendColor = when {
                                sTrend > 100  -> RecoveryGreen
                                sTrend < -100 -> EnergyAmber
                                else          -> NeonCyan
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "14-DAY STEP TREND",
                                    color = Color.White.copy(alpha = 0.45f),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.2.sp,
                                        fontSize = 9.sp
                                    )
                                )
                                Text(
                                    text = trendLabel,
                                    color = trendColor,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                CoreStatChip(
                                    label = "14-DAY AVG",
                                    value = "%,d steps".format(core.avgSteps14d.toLong()),
                                    color = StepsViolet
                                )
                                CoreStatChip(
                                    label = "CONSISTENCY",
                                    value = "${(core.activityConsistency * 100).roundToInt()}%",
                                    color = NeonCyan
                                )
                                CoreStatChip(
                                    label = "EFFORT CEILING",
                                    value = "%.1f strain".format(core.strainCeiling),
                                    color = StrainColor
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            // ── VANTIX AI Insight Card (only when core is active) ─────────────
            if (core != null && isVantixAvailable) {
                item(key = "vantix_ai_card") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        CoreSectionHeader("Coach insight")
                        Spacer(Modifier.height(12.dp))
                        CoreCard {
                            // Header row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(NeonCyan, CircleShape)
                                    )
                                    Text(
                                        text = "Vanta Coach",
                                        color = NeonCyan,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 1.sp,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                                if (!vantixInsightLoading) {
                                    TextButton(
                                        onClick = { aiViewModel.loadVantixInsight() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "Refresh",
                                            color = TextTertiary,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (vantixInsightLoading || vantixInsight == null) {
                                // Shimmer placeholder lines while generating
                                repeat(3) { i ->
                                    val lineWidth = if (i == 2) 0.6f else 1f
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(lineWidth)
                                            .height(14.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Color.White.copy(alpha = 0.07f),
                                                        Color.White.copy(alpha = 0.13f),
                                                        Color.White.copy(alpha = 0.07f)
                                                    )
                                                )
                                            )
                                    )
                                    if (i < 2) Spacer(Modifier.height(8.dp))
                                }
                            } else {
                                Text(
                                    text = vantixInsight!!,
                                    color = TextPrimary.copy(alpha = 0.92f),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            // ── VANTIX Biological Age Card (only when core & bioAge are active) ──────────
            val bioAge = baseline.biologicalAge
            if (core != null && bioAge != null) {
                item(key = "bio_age_card") {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        CoreSectionHeader("Vanta Fitness Age")
                        Spacer(Modifier.height(12.dp))
                        BioAgeCard(bioAge = bioAge)
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            // ── How it works card ─────────────────────────────────────────────
            item(key = "explainer") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    CoreSectionHeader("How VANTIX Works")
                    Spacer(Modifier.height(12.dp))
                    CoreCard {
                        ExplainerRow(
                            icon = "⚡",
                            title = "ATL — Acute Load",
                            body = "7-day exponentially weighted training strain. Spikes fast when you train hard, drops fast when you rest."
                        )
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                        Spacer(Modifier.height(14.dp))
                        ExplainerRow(
                            icon = "📈",
                            title = "CTL — Chronic Load",
                            body = "42-day fitness base. Builds slowly over months — this is your long-term conditioning level."
                        )
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                        Spacer(Modifier.height(14.dp))
                        ExplainerRow(
                            icon = "⚖️",
                            title = "TSB — Freshness",
                            body = "CTL minus ATL. Positive = rested and sharp. Negative = accumulated fatigue. The signal pro coaches read daily."
                        )
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                        Spacer(Modifier.height(14.dp))
                        ExplainerRow(
                            icon = "🧨",
                            title = "Auto Mode Detection",
                            body = "Adaptive Core reads your last 30 days and classifies you automatically. Push hard on 25%+ of days → Training mode. Mostly steady movement → Daily Mover mode. No watch or gym required."
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        // ── Health & wellness disclaimer (estimation, not medical advice) ──────
        item(key = "disclaimer") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "DISCLAIMER",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.4.sp
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Vanta's Strain, Recovery, Energy, Fitness Age and training-load metrics (ATL, CTL, TSB) are estimates for general wellness and training guidance only. They are not medical advice — not a diagnosis, treatment, or substitute for professional medical care. Always consult a qualified healthcare provider before making decisions about your health, exercise, or recovery. If you have a medical condition or experience unusual symptoms, stop and seek professional advice.",
                    color = TextTertiary,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 17.sp
                    )
                )
            }
        }
    }
}

// ── Shared sub-composables ─────────────────────────────────────────────────────

@Composable
private fun CoreCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val borderBrush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.16f),
        0.5f to Color.White.copy(alpha = 0.05f),
        1f to Color.Black.copy(alpha = 0.38f)
    )
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
private fun CoreSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = Color.White.copy(alpha = 0.38f),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp
        )
    )
}

@Composable
private fun CoreStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.38f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.0.sp
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        )
    }
}

@Composable
private fun LoadBar(
    label: String,
    sublabel: String,
    value: Double,
    maxValue: Double,
    color: Color,
    trend: Double?
) {
    val fraction = (value / maxValue).coerceIn(0.0, 1.0).toFloat()
    val animFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(900, easing = LinearOutSlowInEasing),
        label = "load_bar_$label"
    )

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = label,
                    color = color,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                )
                Text(
                    text = sublabel,
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trend != null) {
                    val trendText = if (trend >= 0) "+${"%.1f".format(trend)}" else "${"%.1f".format(trend)}"
                    val trendCol = if (trend >= 0) RecoveryGreen else HeartRateRed
                    Text(
                        text = trendText,
                        color = trendCol.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = "%.1f".format(value),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Light,
                        fontSize = 22.sp,
                        letterSpacing = (-0.5).sp
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.07f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.7f), color)
                        )
                    )
            )
        }
    }
}

@Composable
private fun TsbRow(tsb: Double) {
    val tsbFormatted = "${if (tsb >= 0) "+" else ""}${"%.1f".format(tsb)}"
    val tsbColor = when {
        tsb > 5.0 -> RecoveryGreen
        tsb < -10.0 -> HeartRateRed
        else -> EnergyAmber
    }
    val tsbLabel = when {
        tsb > 10.0 -> "Fresh & sharp — good window to push"
        tsb > 0.0 -> "Slight freshness — solid training conditions"
        tsb > -10.0 -> "Mild fatigue — manageable load"
        else -> "Significant fatigue — prioritize recovery"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "TSB  (FRESHNESS)",
                color = Color.White.copy(alpha = 0.38f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.0.sp
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tsbLabel,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = tsbFormatted,
            color = tsbColor,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp,
                fontSize = 32.sp
            )
        )
    }
}

@Composable
private fun ExplainerRow(icon: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(text = icon, fontSize = 18.sp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = TextPrimary,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp)
            )
        }
    }
}

@Composable
private fun BioAgeCard(
    bioAge: com.vanta.app.data.intelligence.BioAgeResult
) {
    val delta = bioAge.deltaYears
    val isYounger = delta < 0
    val absDelta = kotlin.math.abs(delta)

    val arcColor = when {
        delta <= -1.5 -> NeonCyan
        delta < 1.5 -> EnergyAmber
        else -> Color(0xFFFF5252)
    }

    val animatedSweep by animateFloatAsState(
        targetValue = ((bioAge.biologicalAge / (bioAge.chronologicalAge + 15.0)).toFloat() * 280f).coerceIn(40f, 280f),
        animationSpec = tween(1200, easing = LinearOutSlowInEasing),
        label = "bio_age_ring"
    )

    CoreCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VANTA FITNESS AGE",
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                    fontSize = 10.sp
                )
            )
            Text(
                text = bioAge.confidenceLabel,
                color = NeonCyan.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            )
        }

        Spacer(Modifier.height(16.dp))

        // WHOOP-style animated ring
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(140.dp)) {
                val strokeWidth = 12.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2)
                val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

                // Background track arc
                drawArc(
                    color = Color(0xFF1E1E1E),
                    startAngle = 130f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Foreground active ring gradient arc
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(arcColor, arcColor.copy(alpha = 0.6f), arcColor)
                    ),
                    startAngle = 130f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%.1f".format(bioAge.biologicalAge),
                    color = TextPrimary,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp
                    )
                )
                Text(
                    text = "FITNESS AGE",
                    color = TextTertiary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Subtitle badge: chronological age vs biological age
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chronological age: ${bioAge.chronologicalAge}  ·  ",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Text(
                text = if (isYounger) "${"%.1f".format(absDelta)} yrs younger" else "${"%.1f".format(absDelta)} yrs older",
                color = if (isYounger) NeonCyan else Color(0xFFFF5252),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        if (bioAge.thirtyDayDelta != null) {
            Spacer(Modifier.height(4.dp))
            val isTrendYounger = bioAge.thirtyDayDelta <= 0
            val absTrend = kotlin.math.abs(bioAge.thirtyDayDelta)
            Text(
            text = if (isTrendYounger) "${"%.1f".format(absTrend)} years younger than 30 days ago" else "${"%.1f".format(absTrend)} years older than 30 days ago",
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        HorizontalDivider(color = Color(0xFF1E1E1E), thickness = 1.dp)

        Spacer(Modifier.height(12.dp))

        Text(
            text = "WHAT DRIVES THIS",
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontSize = 9.sp
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        bioAge.factors.forEach { factor ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = factor.title,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = factor.description,
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = if (factor.deltaYears <= 0) "%.1f yrs".format(factor.deltaYears) else "+%.1f yrs".format(factor.deltaYears),
                    color = if (factor.isPositive) NeonCyan else Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}
