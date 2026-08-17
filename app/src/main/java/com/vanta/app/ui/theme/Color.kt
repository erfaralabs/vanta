package com.vanta.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Core AMOLED palette ──────────────────────────────────────────────────────
val VantaBlack        = Color(0xFF000000)
val VantaSurface      = Color(0xFF0A0A0A)
val VantaSurface2     = Color(0xFF111111)
val VantaCard         = Color(0xFF0D0D0D)
val VantaBorder       = Color(0x1FFFFFFF)   // 12% white

// ── Neon accents ─────────────────────────────────────────────────────────────
val NeonCyan          = Color(0xFF00F5FF)
val NeonCyanDim       = Color(0xFF00B8C4)
val NeonBlue          = Color(0xFF0080FF)
val NeonBlueDim       = Color(0xFF0050CC)

// ── Metric accent colors ──────────────────────────────────────────────────────
val StrainColor       = Color(0xFF00F5FF)   // Cyan
val RecoveryGreen     = Color(0xFF39FF80)   // Neon green
val EnergyAmber       = Color(0xFFFFAA00)   // Amber

// ── Heart rate / health ───────────────────────────────────────────────────────
val HeartRateRed      = Color(0xFFFF3B6B)
val CaloriesOrange    = Color(0xFFFF6A00)
val StepsViolet       = Color(0xFFB44BFF)
// Circular readiness-ring palette (WHOOP / Oura grade, refined hues)
val RingRecovery      = Color(0xFF2FBF8F) // emerald green
val RingStrain        = Color(0xFFFF9F0A) // orange
val RingEnergy        = Color(0xFF2F9BFF) // electric blue

// ── Dynamic hero gradients (time-of-day) ─────────────────────────────────────
// Morning  (5–11)  → Orange–Gold
val MorningStart      = Color(0xFFFF6B35)
val MorningEnd        = Color(0xFFFFD700)

// Afternoon (11–17) → Sky Blue
val AfternoonStart    = Color(0xFF0080FF)
val AfternoonEnd      = Color(0xFF00D4FF)

// Evening  (17–21) → Purple–Pink
val EveningStart      = Color(0xFF8B31FF)
val EveningEnd        = Color(0xFFFF3CAC)

// Night    (21–5)  → Indigo–Navy
val NightStart        = Color(0xFF1A0066)
val NightEnd          = Color(0xFF0D1B4B)

// ── Text ─────────────────────────────────────────────────────────────────────
val TextPrimary       = Color(0xFFFFFFFF)
val TextSecondary     = Color(0xFF9CA3AF)
val TextTertiary      = Color(0xFF4B5563)

// ── Glow overlays ────────────────────────────────────────────────────────────
val CyanGlow          = Color(0x2600F5FF)
val BlueGlow          = Color(0x260080FF)
val GreenGlow         = Color(0x2639FF80)
val AmberGlow         = Color(0x26FFAA00)
