package com.vanta.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.theme.*
import com.vanta.app.ui.utils.rememberVantaHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immersive guided box-breathing session.
 *
 * A gradient breathing ring expands on the inhale, holds, then contracts on the
 * exhale. The ambient glow breathes with it and haptics tick at each phase
 * change, so the whole screen — not just the circle — feels alive.
 */

enum class BreathingMode(val label: String, val subtitle: String, val moduleName: String) {
    BOX("Box Breathing", "4-4-4-4 · Focus & Recovery", "box"),
    FOUR_SEVEN_EIGHT("4-7-8", "4-7-8 · Calm & Sleep", "fourseveneight"),
}

private fun breathingPhases(mode: BreathingMode): List<Triple<String, Int, Float>> = when (mode) {
    BreathingMode.BOX -> listOf(
        Triple("Breathe In", 4, 1.26f),
        Triple("Hold", 4, 1.26f),
        Triple("Breathe Out", 4, 0.92f),
        Triple("Hold", 4, 0.92f),
    )
    BreathingMode.FOUR_SEVEN_EIGHT -> listOf(
        Triple("Inhale (nose)", 4, 1.22f),
        Triple("Hold", 7, 1.22f),
        Triple("Exhale (mouth)", 8, 0.88f),
    )
}

private fun defaultCycles(mode: BreathingMode): Int = when (mode) {
    BreathingMode.BOX -> 4
    BreathingMode.FOUR_SEVEN_EIGHT -> 4
}

private fun breathingBenefit(mode: BreathingMode): String = when (mode) {
    BreathingMode.BOX -> "Sharpens focus · dials heart-rate variability · tactical cool‑down"
    BreathingMode.FOUR_SEVEN_EIGHT -> "Natural tranquilizer · activates parasympathetic · eases sleep onset"
}

@Composable
fun BreathingScreen(onBackClick: () -> Unit) {
    val haptics = rememberVantaHaptics()
    val accent = NeonCyan
    val accent2 = StepsViolet

    var mode by remember { mutableStateOf(BreathingMode.BOX) }
    var isRunning by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("Ready") }
    var secondsLeft by remember { mutableIntStateOf(4) }
    var cyclesDone by remember { mutableIntStateOf(0) }
    val targetCycles by remember { derivedStateOf { defaultCycles(mode) } }

    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0.30f) }
    val pulse = remember { Animatable(0f) }
    val phases by remember { derivedStateOf { breathingPhases(mode) } }

    // Second counter per phase — auto-resets to the current phase's duration.
    LaunchedEffect(phase, isRunning, mode) {
        if (!isRunning || phase == "Ready" || phase == "Complete") return@LaunchedEffect
        val dur = phases.find { it.first == phase }?.second ?: 4
        secondsLeft = dur
        while (secondsLeft > 0) {
            delay(1000)
            if (isRunning) secondsLeft--
        }
    }

    // Mode-generic breathing loop.
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (true) {
            if (cyclesDone >= targetCycles) {
                phase = "Complete"
                isRunning = false
                break
            }
            for ((name, dur, targetScale) in phases) {
                phase = name
                haptics.tick()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    scale.animateTo(targetScale, tween(dur * 1000, easing = LinearOutSlowInEasing))
                    glow.animateTo(if (targetScale > 1f) 0.70f else 0.22f, tween(dur * 1000, easing = LinearOutSlowInEasing))
                }
            }
            cyclesDone++
        }
    }

    // Slow idle shimmer when not running.
    LaunchedEffect(isRunning) {
        if (isRunning) return@LaunchedEffect
        while (true) {
            pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
            pulse.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
        }
    }

    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(VantaBlack)) {
        // ── Ambient breathing glow ─────────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.40f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.20f * glow.value), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.minDimension * 0.55f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent2.copy(alpha = 0.12f * glow.value), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.minDimension * 0.42f
                )
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
        // ── Top bar ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
                    text = "BREATHING EXERCISE",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Text(
                    text = mode.subtitle,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                )
            }
        }

        // ── Paced Breathing technique selector ─────────────────────────────────
        if (!isRunning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Paced Breathing",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BreathingMode.entries.forEach { m ->
                        val selected = mode == m
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) accent.copy(alpha = 0.20f) else VantaSurface2)
                                .border(1.dp, if (selected) accent.copy(alpha = 0.5f) else Color(0x14FFFFFF), RoundedCornerShape(20.dp))
                                .clickable { if (!isRunning) mode = m }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = m.label,
                                color = if (selected) accent else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // ── Breathing circle ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 14.dp.toPx()
                    val inset = strokePx / 2f
                    val d = size.minDimension - strokePx
                    val arcSize = androidx.compose.ui.geometry.Size(d, d)
                    val topLeft = Offset(inset, inset)

                    val ring = Brush.sweepGradient(
                        colors = listOf(accent, accent2, accent)
                    )

                    // Glow halo that swells with the breath
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.30f * glow.value), Color.Transparent),
                            center = center,
                            radius = size.minDimension * 0.62f
                        )
                    )
                    // Inner fill
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.16f * glow.value),
                                accent2.copy(alpha = 0.05f * glow.value),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.minDimension * 0.48f
                        )
                    )
                    // Track + gradient ring
                    drawArc(
                        color = Color.White.copy(alpha = 0.06f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(strokePx, cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = ring,
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(strokePx, cap = StrokeCap.Round)
                    )
                }

                // Phase + seconds center
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = phase,
                        color = TextPrimary,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        letterSpacing = (-0.5).sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (phase == "Ready" || phase == "Complete") "" else "$secondsLeft",
                        color = accent,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 52.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(targetCycles) { i ->
                            val done = i < cyclesDone
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (done) accent else Color.White.copy(alpha = 0.15f))
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (phase == "Complete") "Session complete — great work" else "Cycle ${(cyclesDone + 1).coerceAtMost(targetCycles)} of $targetCycles",
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── Controls ───────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (phase == "Complete") "Tap start again to reset" else breathingBenefit(mode),
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Button(
                    onClick = {
                        haptics.click()
                        if (phase == "Complete") {
                            cyclesDone = 0
                            scope.launch {
                                scale.snapTo(1f)
                                glow.snapTo(0.30f)
                            }
                            phase = "Ready"
                            isRunning = true
                        } else {
                            isRunning = !isRunning
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent.copy(alpha = 0.2f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = when {
                            phase == "Complete" -> "Start Again"
                            isRunning -> "Pause"
                            else -> "Start Breathing"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
                if (isRunning || cyclesDone > 0) {
                    OutlinedButton(
                        onClick = {
                            haptics.tick()
                            isRunning = false
                            cyclesDone = 0
                            phase = "Ready"
                            scope.launch {
                                scale.snapTo(1f)
                                glow.snapTo(0.30f)
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
                    ) {
                        Text("Reset", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        }
    }
}

