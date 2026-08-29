package com.vanta.app.ui.components

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.ui.utils.rememberVantaHaptics

/**
 * Value-proportional ring brightness and track glow parameters.
 *
 * 1. Track Opacity/Glow Mapping (applied to background unfilled circle):
 *    - value < 30%: track opacity 12%, NO track glow (0.00f)
 *    - value 30-70%: track opacity 18%, NO track glow (0.00f)
 *    - value > 70%: track opacity 30%, subtle track glow (0.06f)
 *
 * 2. Active Arc (filled portion):
 *    - Active arc opacity stays 100% (1.0f) crisp and visible.
 *    - Active glow scales with value percentage (0.04f -> 0.09f -> 0.16f).
 */
data class RingBrightnessParams(
    val tierName: String,
    val valuePercent: Int,
    val trackAlpha: Float,
    val trackGlowAlpha: Float,
    val activeArcAlpha: Float,
    val activeGlowAlpha: Float,
    val activeGlowStrokeMultiplier: Float
)

/**
 * Computes value-proportional brightness parameters based on metric target value fraction (0.0 .. 1.0).
 */
fun calculateRingBrightness(fraction: Float): RingBrightnessParams {
    val clamped = fraction.coerceIn(0f, 1f)
    val pct = (clamped * 100).toInt()

    val (tierName, trackAlpha, trackGlowAlpha) = when {
        clamped < 0.30f -> Triple("Tier 1 (<30%)", 0.12f, 0.00f)
        clamped <= 0.70f -> Triple("Tier 2 (30-70%)", 0.18f, 0.00f)
        else -> Triple("Tier 3 (>70%)", 0.30f, 0.06f)
    }

    val activeArcAlpha = 1.0f // Filled arc stays crisp and fully bright
    val activeGlowAlpha = when {
        clamped < 0.30f -> 0.04f
        clamped <= 0.70f -> 0.09f
        else -> 0.16f
    }
    val activeGlowStrokeMultiplier = when {
        clamped < 0.30f -> 1.2f
        clamped <= 0.70f -> 1.4f
        else -> 1.7f
    }

    return RingBrightnessParams(
        tierName = tierName,
        valuePercent = pct,
        trackAlpha = trackAlpha,
        trackGlowAlpha = trackGlowAlpha,
        activeArcAlpha = activeArcAlpha,
        activeGlowAlpha = activeGlowAlpha,
        activeGlowStrokeMultiplier = activeGlowStrokeMultiplier
    )
}

/**
 * Handcrafted circular readiness ring — WHOOP / Oura / Apple Fitness grade.
 *
 * Base hues are fixed (Strain=orange, Recovery=green, Energy=blue).
 * Background track opacity and glow scale dynamically with value percentage.
 */
@Composable
fun CircularMetricRing(
    label: String,
    value: Float,
    maxValue: Float,
    displayValue: String,
    unit: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    ringSize: Dp = 108.dp,
    strokeWidth: Dp = 8.dp,
    status: String = ""
) {
    val target = (value / maxValue).coerceIn(0f, 1f)
    val progress = remember { Animatable(0f) }
    val haptics = rememberVantaHaptics()

    // Calculate brightness parameters directly from metric target value percentage
    val brightness = remember(target) {
        calculateRingBrightness(target).also { p ->
            Log.d("VantaRing", "[$label] value=$value/$maxValue (${p.valuePercent}%), tier=${p.tierName} -> trackAlpha=${p.trackAlpha}, trackGlow=${p.trackGlowAlpha}")
        }
    }

    var hasAnimated by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        if (!hasAnimated) {
            progress.snapTo(0f)
            hasAnimated = true
        }
        progress.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio  = Spring.DampingRatioMediumBouncy,
                stiffness     = Spring.StiffnessLow
            )
        )
        if (target > 0f) haptics.tick()
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(ringSize)
        ) {
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
                val strokePx   = strokeWidth.toPx()
                val diameter   = size.minDimension - strokePx
                val topLeft    = Offset(strokePx / 2, strokePx / 2)
                val arcSize    = Size(diameter, diameter)
                val sweepAngle = 360f * progress.value

                // 1. Unfilled Track Glow (only in Tier 3 >70%)
                if (brightness.trackGlowAlpha > 0f) {
                    drawArc(
                        color      = accentColor.copy(alpha = brightness.trackGlowAlpha),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter  = false,
                        topLeft    = topLeft,
                        size       = arcSize,
                        style      = Stroke(strokePx * 1.5f, cap = StrokeCap.Round)
                    )
                }

                // 2. Unfilled Track Arc (12% <30%, 18% 30-70%, 30% >70%)
                drawArc(
                    color      = accentColor.copy(alpha = brightness.trackAlpha),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSize,
                    style      = Stroke(strokePx, cap = StrokeCap.Round)
                )

                // 3. Active Ring Outer Glow (scales with value percentage)
                if (sweepAngle > 0f && brightness.activeGlowAlpha > 0f) {
                    drawArc(
                        color      = accentColor.copy(alpha = brightness.activeGlowAlpha),
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter  = false,
                        topLeft    = topLeft,
                        size       = arcSize,
                        style      = Stroke(strokePx * brightness.activeGlowStrokeMultiplier, cap = StrokeCap.Round)
                    )
                }

                // 4. Active Ring Arc — 100% solid crisp filled arc
                if (sweepAngle > 0f) {
                    drawArc(
                        color      = accentColor.copy(alpha = brightness.activeArcAlpha),
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter  = false,
                        topLeft    = topLeft,
                        size       = arcSize,
                        style      = Stroke(strokePx, cap = StrokeCap.Round)
                    )
                }
            }

            // Value + unit — dead center of the ring
            val valueScale = (ringSize.value / 108f).coerceIn(0.75f, 1.3f)
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text  = displayValue,
                    color = Color.White,
                    fontSize = (28 * valueScale).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text  = unit,
                        color = accentColor.copy(alpha = 0.9f),
                        fontSize = (11 * valueScale).sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp
                    )
                }
            }
        }

        // Caption below the ring — uppercase label
        Spacer(Modifier.height(8.dp))
        Text(
            text  = label.uppercase(),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        )
    }
}
