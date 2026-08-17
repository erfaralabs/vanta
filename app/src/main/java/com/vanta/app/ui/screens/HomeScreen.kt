package com.vanta.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.MetricValue
import com.vanta.app.data.VantaGemmaEngine
import com.vanta.app.ui.components.*
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.viewmodel.VantaAiUiState
import com.vanta.app.ui.viewmodel.VantaAiViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data holder for main dashboard metrics tiles.
 */
data class MetricInfo(
    val title: String,
    val value: String,
    val unit: String,
    val color: Color,
    val isMeasured: Boolean,
    val showBadge: Boolean = true,
    val valueColor: Color? = null,
    val supporting: String = "",
    val progress: Float? = null,
    val progressLabel: String = ""
)

/** WHOOP-style status label for the Recovery ring. */
private fun recoveryStatus(recovery: Int): String = when {
    recovery >= 85 -> "Excellent"
    recovery >= 70 -> "Great"
    recovery >= 60 -> "Moderate"
    recovery >= 40 -> "Fatigued"
    else -> "Depleted"
}

/** Intensity label for the Strain ring (0–21 scale). */
private fun strainStatus(strain: Double): String = when {
    strain >= 16 -> "Extreme"
    strain >= 12 -> "Very High"
    strain >= 8 -> "High"
    strain >= 4 -> "Moderate"
    else -> "Light"
}

/** Readiness label for the Energy ring. */
private fun energyStatus(energy: Int): String = when {
    energy >= 85 -> "High"
    energy >= 70 -> "Ready"
    energy >= 50 -> "Moderate"
    energy >= 30 -> "Low"
    else -> "Drained"
}

/**
 * Primary Vanta Dashboard Screen.
 */
@Composable
fun HomeScreen(
    onHealthConnectClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onMetricClick: (PhysiologyMetric) -> Unit = {},
    onStepsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember { HealthConnectManager(context) }
    val scope = rememberCoroutineScope()
    val haptics = com.vanta.app.ui.utils.rememberVantaHaptics()

    val aiViewModel: VantaAiViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val userBaseline by aiViewModel.userBaseline.collectAsState()
    val userProfile by aiViewModel.userProfile.collectAsState()
    val liveTelemetry by aiViewModel.liveTelemetry.collectAsState()
    val historicalRecords by aiViewModel.historicalRecords.collectAsState()
    val todayRecord0 = remember(historicalRecords) { historicalRecords.getOrNull(0) }

    val isStepsMeasured by aiViewModel.isStepsMeasured.collectAsState()
    val isCaloriesMeasured by aiViewModel.isCaloriesMeasured.collectAsState()
    val isDistanceMeasured by aiViewModel.isDistanceMeasured.collectAsState()

    // Instant-paint values: always reflects the latest/highest step count between live & Room DB with 0 lag
    val effectiveSteps = maxOf(liveTelemetry?.steps ?: 0L, todayRecord0?.steps ?: 0L)
    val effectiveCalories = maxOf(liveTelemetry?.calories ?: 0L, todayRecord0?.calories ?: 0L)
    val effectiveDistance = maxOf(liveTelemetry?.distanceKm ?: 0.0, todayRecord0?.distanceKm ?: 0.0)

    // Health Connect Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        if (grantedPermissions.isNotEmpty()) {
            aiViewModel.runAnalysis()
        }
    }

    // Check permissions and run single analysis pass on launch
    LaunchedEffect(Unit) {
        if (!manager.hasPermissions() && manager.isAvailable) {
            permissionLauncher.launch(manager.permissions)
        } else if (manager.hasPermissions()) {
            aiViewModel.runAnalysis(skipCloudCall = true)
        }
    }

    // Realtime: keep the live tiles fresh while the dashboard is on screen (every 30s)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            if (manager.hasPermissions()) {
                aiViewModel.runAnalysis(skipCloudCall = true)
            }
        }
    }

    // Day-over-day deltas from the two most recent archived records (date DESC).
    val (strainDelta, recoveryDelta) = remember(historicalRecords) {
        val cur = historicalRecords.getOrNull(0)
        val prev = historicalRecords.getOrNull(1)
        if (cur != null && prev != null) (cur.strain - prev.strain) to (cur.recovery - prev.recovery)
        else null to null
    }
    val strainDeltaText = when {
        strainDelta == null -> "--"
        strainDelta >= 0 -> "+%.1f".format(strainDelta)
        else -> "%.1f".format(strainDelta)
    }
    val recoveryDeltaText = when {
        recoveryDelta == null -> "--"
        recoveryDelta >= 0 -> "+$recoveryDelta"
        else -> "$recoveryDelta"
    }

    // Personalized strain target: your own 7-day average strain. Today's card shows
    // how much strain is LEFT under that target — so as steps/strain build up, the
    // remaining budget shrinks live. "reached ✓" when the target is met.
    val targetBase = if (userBaseline.avgStrain >= 1.0) userBaseline.avgStrain else 6.5
    val strainTarget = targetBase.coerceIn(2.0, 21.0)
    val currentStrain = todayRecord0?.strain ?: 0.0
    val strainLeft = (strainTarget - currentStrain).coerceAtLeast(0.0)
    val strainTargetText = when {
        todayRecord0 == null -> "--"
        strainLeft <= 0.05 -> "reached"
        else -> "%.1f".format(strainLeft)
    }

    // Supporting details + hairline progress for each card (7-day baselines from
    // the user's own history so every card reads personal, not generic).
    val recentAvgRecovery = historicalRecords.take(7).let { r ->
        if (r.isEmpty()) 0.0 else r.map { it.recovery }.average()
    }
    val stepsGoal = userProfile?.stepsGoal?.takeIf { it > 0 } ?: 10000
    val stepsPct = (effectiveSteps / stepsGoal.toFloat()).coerceIn(0f, 1f)
    val calsPct = if (userBaseline.avgCalories > 0) {
        (effectiveCalories / userBaseline.avgCalories).toFloat().coerceIn(0f, 1f)
    } else null
    val strainUsedPct = if (strainTarget > 0) {
        (currentStrain / strainTarget).toFloat().coerceIn(0f, 1f)
    } else null

    val metrics = listOf(
        MetricInfo(
            title = "Strain Change", value = strainDeltaText, unit = "vs yesterday",
            color = StrainColor, isMeasured = true, showBadge = false,
            valueColor = when {
                strainDelta == null -> TextSecondary
                strainDelta >= 0 -> EnergyAmber
                else -> RecoveryGreen
            },
            supporting = "7d avg ${"%.1f".format(userBaseline.avgStrain)} · ${if (strainDelta == null) "building baseline" else "Δ $strainDeltaText"}"
        ),
        MetricInfo(
            title = "Recovery Change", value = recoveryDeltaText, unit = "vs yesterday",
            color = RecoveryGreen, isMeasured = true, showBadge = false,
            valueColor = when {
                recoveryDelta == null -> TextSecondary
                recoveryDelta >= 0 -> RecoveryGreen
                else -> HeartRateRed
            },
            supporting = "7d avg ${recentAvgRecovery.toInt()}%"
        ),
        MetricInfo(
            title = "Strain Target", value = strainTargetText, unit = if (strainLeft <= 0.05) "" else "left today",
            color = NeonBlue, isMeasured = true, showBadge = false, valueColor = NeonBlue,
            supporting = "today target · ${"%.1f".format(strainTarget)}",
            progress = strainUsedPct,
            progressLabel = if (strainLeft <= 0.05) "target reached" else {
                "${((strainLeft / strainTarget) * 100).toInt().coerceIn(0, 100)}% remaining"
            }
        ),
        MetricInfo(
            title = "Steps", value = "%,d".format(effectiveSteps), unit = "steps",
            color = NeonCyan, isMeasured = isStepsMeasured,
            supporting = "7d avg ${"%,d".format(userBaseline.avgSteps.toLong())}",
            progress = stepsPct,
            progressLabel = "${(stepsPct * 100).toInt()}% of ${if (stepsGoal >= 1000) "%,d".format(stepsGoal) else "$stepsGoal"}"
        ),
        MetricInfo(
            title = "Calories", value = "%,d".format(effectiveCalories), unit = "kcal",
            color = EnergyAmber, isMeasured = isCaloriesMeasured,
            supporting = if (userBaseline.avgCalories > 0) {
                "7d avg ${"%,d".format(userBaseline.avgCalories.toLong())}"
            } else {
                "active kcal today"
            },
            progress = calsPct,
            progressLabel = if (calsPct != null) "${(calsPct * 100).toInt()}% of avg" else ""
        ),
        MetricInfo(
            title = "Distance", value = "%.1f".format(effectiveDistance), unit = "km",
            color = StepsViolet, isMeasured = isDistanceMeasured,
            supporting = if (userBaseline.avgDistanceKm > 0) {
                "7d avg ${"%.1f".format(userBaseline.avgDistanceKm)} km"
            } else {
                "movement today"
            }
        )
    )

    val isDailyAnalysisAvailable by aiViewModel.isDailyAnalysisAvailable.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D0B))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            // ── Dynamic Radial Physiology Gauges & Hero Header Data ──────────────
            item(key = "hero_and_rings", contentType = "hero") {
                val aiUiState by aiViewModel.uiState.collectAsState()
                var lastAnalysis by remember { mutableStateOf<Triple<Double, Int, Int>?>(null) }
                val (strainVal, recoveryVal, energyVal) = when (val s = aiUiState) {
                    is VantaAiUiState.Success -> {
                        Triple(s.analysis.strain, s.analysis.recovery, s.analysis.energy)
                            .also { lastAnalysis = it }
                    }
                    else -> lastAnalysis ?: todayRecord0?.let { Triple(it.strain, it.recovery, it.energy) } ?: Triple(0.0, 85, 80)
                }

                var lastKnownName by remember { mutableStateOf(userProfile?.name?.takeIf { it.isNotBlank() }) }
                SideEffect {
                    userProfile?.name?.takeIf { it.isNotBlank() }?.let { lastKnownName = it }
                }
                val displayName = userProfile?.name?.takeIf { it.isNotBlank() } ?: lastKnownName ?: ""

                val effectiveRecoveryForGradient = if (recoveryVal > 0) recoveryVal else 85
                val topHeroGradient = rememberSmartHeroGradient(recoveryPercent = effectiveRecoveryForGradient)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topHeroGradient)
                        .padding(bottom = 16.dp)
                ) {
                    var lastValidSubtitle by remember { mutableStateOf<String?>(null) }
                    val heroSubtitle = remember(strainVal, recoveryVal, energyVal, effectiveSteps, liveTelemetry?.exerciseMinutes, aiUiState) {
                        val picked = com.vanta.app.ui.utils.VantaSubtitle.pick(
                            context = context,
                            recovery = recoveryVal,
                            strain = strainVal,
                            energy = energyVal,
                            steps = effectiveSteps,
                            exerciseMinutes = liveTelemetry?.exerciseMinutes ?: 0,
                        )
                        if (picked.isNotBlank() && !picked.startsWith("Loading")) {
                            picked.also { lastValidSubtitle = it }
                        } else {
                            lastValidSubtitle ?: picked
                        }
                    }
                    HeroSection(
                        userName = displayName,
                        strain = strainVal,
                        recovery = recoveryVal,
                        energy = energyVal,
                        savedDaysCount = userBaseline.savedDaysCount,
                        onLogoClick = onSettingsClick,
                        subtitle = heroSubtitle,
                        avatarKey = userProfile?.avatarKey?.takeIf { it.isNotBlank() },
                        modifier = Modifier.wrapContentHeight()
                    )

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        val availableWidth = maxWidth
                        val sideRingSize = ((availableWidth.value - 32) / 3.25f).coerceIn(76f, 114f).dp
                        val heroRingSize = sideRingSize * 1.14f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                    .clickable {
                                        haptics.click()
                                        onMetricClick(PhysiologyMetric.STRAIN)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                CircularMetricRing(
                                    label        = "Strain",
                                    value        = strainVal.toFloat(),
                                    maxValue     = 21.0f,
                                    displayValue = "%.1f".format(strainVal),
                                    unit         = "/21",
                                    accentColor  = RingStrain,
                                    ringSize     = sideRingSize,
                                    strokeWidth  = 8.dp,
                                    status       = strainStatus(strainVal)
                                )
                                Text(
                                    text = "details ↗",
                                    color = RingStrain.copy(alpha = 0.65f),
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                    .clickable {
                                        haptics.click()
                                        onMetricClick(PhysiologyMetric.RECOVERY)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                CircularMetricRing(
                                    label        = "Recovery",
                                    value        = recoveryVal.toFloat(),
                                    maxValue     = 100f,
                                    displayValue = "$recoveryVal",
                                    unit         = "%",
                                    accentColor  = RingRecovery,
                                    ringSize     = heroRingSize, // hero — ~14% larger than the side rings
                                    strokeWidth  = 8.5.dp,
                                    status       = recoveryStatus(recoveryVal)
                                )
                                Text(
                                    text = "details ↗",
                                    color = RingRecovery.copy(alpha = 0.65f),
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                    .clickable {
                                        haptics.click()
                                        onMetricClick(PhysiologyMetric.ENERGY)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                CircularMetricRing(
                                    label        = "Energy",
                                    value        = energyVal.toFloat(),
                                    maxValue     = 100f,
                                    displayValue = "$energyVal",
                                    unit         = "%",
                                    accentColor  = RingEnergy,
                                    ringSize     = sideRingSize,
                                    strokeWidth  = 8.dp,
                                    status       = energyStatus(energyVal)
                                )
                                Text(
                                    text = "details ↗",
                                    color = RingEnergy.copy(alpha = 0.65f),
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }

            // ── Vanta Neural Intelligence Section (Cardless Editorial Layout) ─────────────
            if (isDailyAnalysisAvailable) {
                item(key = "gemma_ai_assistant", contentType = "ai_card") {
                    val aiUiState by aiViewModel.uiState.collectAsState()

                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        VantaAiCard(
                            uiState = aiUiState,
                            onRefresh = { aiViewModel.reAnalyze() }
                        )
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }

            // ── Health Connect Data Header ───────────────────────────────────────
            item(key = "hc_header", contentType = "section_header") {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Health Telemetry",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ── Health Telemetry Grid (Measured vs Estimated Badges) ───────────
            item(key = "metrics_grid") {
                val chunkedMetrics = metrics.chunked(2)
                chunkedMetrics.forEach { rowMetrics ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowMetrics.forEach { m ->
                            val tileClick: (() -> Unit)? = when (m.title) {
                                "Steps", "Distance" -> {
                                    {
                                        haptics.click()
                                        onStepsClick()
                                    }
                                }
                                "Recovery" -> {
                                    {
                                        haptics.click()
                                        onMetricClick(PhysiologyMetric.RECOVERY)
                                    }
                                }
                                "Strain Target" -> {
                                    {
                                        haptics.click()
                                        onMetricClick(PhysiologyMetric.STRAIN)
                                    }
                                }
                                else -> null
                            }
                            MetricTile(
                                label       = m.title,
                                value       = m.value,
                                unit        = m.unit,
                                accentColor = m.color,
                                isMeasured  = m.isMeasured,
                                showBadge   = m.showBadge,
                                valueColor  = m.valueColor,
                                supporting  = m.supporting,
                                progress    = m.progress,
                                progressLabel = m.progressLabel,
                                onClick     = tileClick,
                                modifier    = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                        if (rowMetrics.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
