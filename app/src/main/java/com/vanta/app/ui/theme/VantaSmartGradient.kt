package com.vanta.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Interpolates smoothly between two Compose Colors.
 */
internal fun lerpGradientColor(start: Color, end: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * f,
        green = start.green + (end.green - start.green) * f,
        blue = start.blue + (end.blue - start.blue) * f,
        alpha = start.alpha + (end.alpha - start.alpha) * f
    )
}

/**
 * Smart, state-adaptive, animated, and OLED banding-safe hero section gradient.
 *
 * 1. STATE-ADAPTIVE:
 *    - >=75%: green tones (primed)      — #1C3A2C -> #0F1F16 -> #0A0D0B
 *    - 50-74%: amber tones (moderate)   — #3A331C -> #1F1A0F -> #0A0D0B
 *    - <50%: red tones (compromised)    — #3A1C1C -> #1F0F0F -> #0A0D0B
 *
 * 2. BANDING-SAFE:
 *    Uses 6 color stops to prevent 8-bit color quantization banding on OLED screens.
 *    The final stop exactly matches #0A0D0B (the screen's base background color) to eliminate seams.
 *
 * 3. ANIMATED:
 *    Uses animateColorAsState with an 800ms tween for smooth state transitions when recovery % updates.
 */
@Composable
fun rememberSmartHeroGradient(recoveryPercent: Int): Brush {
    // 1. Select target palette based on recovery percentage state with rich ambient depth
    val (targetTop, targetMid) = when {
        recoveryPercent >= 75 -> Color(0xFF0D5230) to Color(0xFF082817) // Rich vibrant emerald aura
        recoveryPercent >= 50 -> Color(0xFF573E0A) to Color(0xFF2B1E04) // Warm amber aura
        else                  -> Color(0xFF571616) to Color(0xFF2B0909) // Deep crimson aura
    }

    // 2. Animate color transitions smoothly when recoveryPercent updates
    val animatedTop by animateColorAsState(
        targetValue = targetTop,
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing),
        label = "hero_gradient_top"
    )

    val animatedMid by animateColorAsState(
        targetValue = targetMid,
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing),
        label = "hero_gradient_mid"
    )

    val baseBackground = Color(0xFF0A0D0B) // Screen base background color

    // 3. Construct 6 banding-safe color stops transitioning to baseBackground
    return remember(animatedTop, animatedMid) {
        val c0 = animatedTop
        val c1 = lerpGradientColor(animatedTop, animatedMid, 0.35f)
        val c2 = animatedMid
        val c3 = lerpGradientColor(animatedMid, baseBackground, 0.45f)
        val c4 = lerpGradientColor(animatedMid, baseBackground, 0.80f)
        val c5 = baseBackground

        Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to c0,
                0.18f to c1,
                0.40f to c2,
                0.62f to c3,
                0.82f to c4,
                1.00f to c5
            )
        )
    }
}
