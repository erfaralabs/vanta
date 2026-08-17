package com.vanta.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.app.R
import com.vanta.app.ui.theme.*
import java.util.Calendar

private data class TimeSlot(
    val greeting: String,
    val startColor: Color,
    val endColor: Color,
    val dateString: String,
)

internal fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * f,
        green = start.green + (end.green - start.green) * f,
        blue = start.blue + (end.blue - start.blue) * f,
        alpha = start.alpha + (end.alpha - start.alpha) * f
    )
}

/**
 * Maps the user's current state to the hero gradient — the background literally
 * reflects today's physiology (not the clock). Effort/warmth for strain, cool
 * calm greens/blues for recovery.
 */
private fun stateGradient(recovery: Int, strain: Double, energy: Int): Pair<Color, Color> {
    return when {
        // Heavy session banked — warm effort tones
        strain >= 10.0 -> Color(0xFFFF453A) to Color(0xFF660B00)
        // Low recovery — soft amber, restful framing
        recovery <= 54 -> Color(0xFFFF9F0A) to Color(0xFF593000)
        // High recovery + high energy — rich vibrant emerald green glow
        recovery >= 80 && energy >= 70 -> Color(0xFF007A3D) to Color(0xFF002914)
        // Good recovery — fresh vibrant teal
        recovery >= 70 -> Color(0xFF006B5E) to Color(0xFF002621)
        // Moderate — electric cyan / navy
        else -> Color(0xFF004D73) to Color(0xFF001A26)
    }
}

private fun getDynamicCoachSubtitle(
    strain: Double,
    recovery: Int,
    energy: Int,
    hour: Int
): String {
    val strainStr = "%.1f".format(strain)
    return when {
        // High Recovery (85%+) + Active Strain logged
        recovery >= 85 && strain >= 6.0 -> "${strainStr} Strain logged today. Energy at ${energy}% — strong adaptation. ⚡"
        
        // High Recovery + Low/Fresh Strain -> Prime window for exertion
        recovery >= 85 && strain < 6.0 -> when (hour) {
            in 5..11 -> "${recovery}% Recovery banked. Prime window for a high-strain session. ⚡"
            in 12..17 -> "Prime ${recovery}% Recovery. Your body has high capacity for exertion. 💪"
            else -> "Recovery at ${recovery}%. Excellent physiological baseline for overnight repair. 🧬"
        }

        // Heavy Strain logged (10.0+)
        strain >= 10.0 -> "${strainStr} Strain logged. Prioritize sleep & hydration for optimal adaptation. 🛌"
        
        // Moderate Strain (5.0 - 9.9)
        strain >= 5.0 -> "${strainStr} Strain accumulated. Energy at ${energy}%. Balanced activity today. ⚡"

        // Moderate Recovery (60% - 84%)
        recovery in 60..84 -> "Recovery at ${recovery}%. Moderate capacity — keep today's strain under 10.0. 🎯"

        // Low Recovery (<60%) -> Rest recommendation
        recovery < 60 -> "Recovery is low (${recovery}%). Focus on light movement & active restoration today. 🧘"

        else -> when (hour) {
            in 5..10 -> "Recovery at ${recovery}%. Ready to build today's strain baseline. 🔥"
            in 11..16 -> "${recovery}% Recovery & ${energy}% Energy. Steady exertion capacity. 💪"
            in 17..20 -> "${strainStr} Strain logged. Energy at ${energy}% for tonight. ⚡"
            else -> "${recovery}% Recovery. Prime window for HRV & sleep restoration. 🌙"
        }
    }
}

/**
 * Top edge-to-edge Hero Header Component for Vanta.
 * Renders dynamic AI coach subtitles driven by live Strain, Recovery, and Energy data.
 */
@Composable
fun HeroSection(
    userName: String = "there",
    strain: Double = 0.0,
    recovery: Int = 90,
    energy: Int = 90,
    savedDaysCount: Int = 0,
    onLogoClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    avatarKey: String? = null,
) {
    val (slot, currentHour) = remember {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val monthNames = listOf(
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
        )
        val dayOfWeek  = dayNames[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val month      = monthNames[calendar.get(Calendar.MONTH)]
        val year       = calendar.get(Calendar.YEAR)
        val dateString = "$dayOfWeek, $dayOfMonth $month $year"

        val timeSlot = when (hour) {
            in 5..10  -> TimeSlot(greeting = "Good Morning", startColor = MorningStart, endColor = MorningEnd, dateString = dateString)
            in 11..16 -> TimeSlot(greeting = "Good Afternoon", startColor = AfternoonStart, endColor = AfternoonEnd, dateString = dateString)
            in 17..20 -> TimeSlot(greeting = "Good Evening", startColor = EveningStart, endColor = EveningEnd, dateString = dateString)
            else       -> TimeSlot(greeting = "Good Night", startColor = NightStart, endColor = NightEnd, dateString = dateString)
        }
        Pair(timeSlot, hour)
    }

    // Premium subtitle: caller-provided (state-aware, repeat-avoiding) when given,
    // otherwise a lightweight internal fallback.
    val dynamicSubtitle = remember(strain, recovery, energy, currentHour, subtitle) {
        subtitle ?: getDynamicCoachSubtitle(strain, recovery, energy, currentHour)
    }

    // Data-driven hero gradient — the background reflects today's physiology
    // (recovery/strain/energy), not the time of day.
    val (gradStart, gradEnd) = remember(recovery, strain, energy) {
        stateGradient(recovery, strain, energy)
    }
    val heroBrush = remember(gradStart, gradEnd) {
        // Multi-stop anti-banding curve: smooth non-linear interpolation across 9 stops
        // eliminates OLED 8-bit color quantization step lines while preserving vibrant glow.
        val c0 = gradStart.copy(alpha = 0.95f)
        val c1 = lerpColor(gradStart, gradEnd, 0.15f).copy(alpha = 0.80f)
        val c2 = lerpColor(gradStart, gradEnd, 0.30f).copy(alpha = 0.62f)
        val c3 = lerpColor(gradStart, gradEnd, 0.45f).copy(alpha = 0.44f)
        val c4 = lerpColor(gradStart, gradEnd, 0.60f).copy(alpha = 0.28f)
        val c5 = lerpColor(gradEnd, VantaBlack, 0.30f).copy(alpha = 0.15f)
        val c6 = lerpColor(gradEnd, VantaBlack, 0.55f).copy(alpha = 0.07f)
        val c7 = lerpColor(gradEnd, VantaBlack, 0.80f).copy(alpha = 0.02f)
        val c8 = Color.Transparent

        Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to c0,
                0.12f to c1,
                0.25f to c2,
                0.38f to c3,
                0.50f to c4,
                0.65f to c5,
                0.78f to c6,
                0.90f to c7,
                1.00f to c8
            )
        )
    }

    val dateChipShape = remember { RoundedCornerShape(20.dp) }
    val logoShape     = CircleShape

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(dateChipShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text  = slot.dateString,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 0.3.sp
                    )
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onLogoClick() }
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(logoShape)
                        .background(VantaBlack)
                        .border(1.dp, NeonCyan.copy(alpha = 0.5f), logoShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarKey != null) {
                        AvatarImage(
                            avatarKey = avatarKey,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.ic_logo),
                            contentDescription = "Settings",
                            modifier = Modifier.fillMaxSize().padding(6.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text  = "${slot.greeting},",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Medium
                )
            )
        }

        Text(
            text  = userName,
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp
            )
        )

        Spacer(Modifier.height(2.dp))

        androidx.compose.animation.AnimatedContent(
            targetState = dynamicSubtitle,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) +
                 androidx.compose.animation.slideInVertically(animationSpec = androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) { height -> height / 3 })
                .togetherWith(
                    androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutLinearInEasing)) +
                    androidx.compose.animation.slideOutVertically(animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { height -> -height / 3 }
                )
            },
            label = "hero_subtitle_crossfade"
        ) { targetText ->
            Text(
                text  = targetText,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}
