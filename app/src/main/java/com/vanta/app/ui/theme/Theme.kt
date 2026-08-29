package com.vanta.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val VantaDarkColorScheme = darkColorScheme(
    primary          = NeonCyan,
    onPrimary        = VantaBlack,
    primaryContainer = Color(0xFF0A2E3A),
    onPrimaryContainer = NeonCyan,

    secondary        = NeonBlue,
    onSecondary      = VantaBlack,
    secondaryContainer = Color(0xFF0A1F3A),
    onSecondaryContainer = NeonBlue,

    tertiary         = RecoveryGreen,
    onTertiary       = VantaBlack,
    tertiaryContainer = Color(0xFF0A2F22),
    onTertiaryContainer = RecoveryGreen,

    background       = VantaBlack,
    onBackground     = TextPrimary,

    surface          = VantaSurface,
    onSurface        = TextPrimary,
    surfaceVariant   = VantaSurface2,
    onSurfaceVariant = TextSecondary,

    outline          = VantaBorder,
    outlineVariant   = Color(0x0DFFFFFF),

    error            = HeartRateRed,
    onError          = VantaBlack,
)

@Composable
fun VantaTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = VantaDarkColorScheme,
        typography  = VantaTypography,
        content     = content
    )
}
