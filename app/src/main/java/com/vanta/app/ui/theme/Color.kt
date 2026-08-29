package com.vanta.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Core AMOLED palette (Bevel-inspired premium neutral surfaces) ────────────
val VantaBlack        = Color(0xFF000000)
val VantaSurface      = Color(0xFF0A0A0B)   // near-black, faint warmth
val VantaSurface2     = Color(0xFF16161A)   // elevated surface
val VantaCard         = Color(0xFF121217)   // card surface
val VantaBorder       = Color(0x14FFFFFF)   // 8% white hairline

// Higher-elevation surfaces used inside cards / panels
val VantaElevated     = Color(0xFF1A1A1F)
val VantaHairline     = Color(0x0FFFFFFF)   // 6% white — subtle dividers

// ── Neon accents (softened to premium tones) ─────────────────────────────────
val NeonCyan          = Color(0xFF2FE0E8)
val NeonCyanDim       = Color(0xFF2BB8C2)
val NeonBlue          = Color(0xFF4C9AFF)
val NeonBlueDim       = Color(0xFF3A6FD8)

// ── Metric accent colors ──────────────────────────────────────────────────────
val StrainColor       = Color(0xFFFF9F0A)   // Warm orange (strain)
val RecoveryGreen     = Color(0xFF34D399)   // Mint green (recovery)
val EnergyAmber       = Color(0xFFF5A623)   // Refined amber (energy)

// ── Heart rate / health ───────────────────────────────────────────────────────
val HeartRateRed      = Color(0xFFFF5A76)
val CaloriesOrange    = Color(0xFFFF7A3D)
val StepsViolet       = Color(0xFF9B6BFF)
// Circular readiness-ring palette (WHOOP / Oura / Bevel grade)
val RingRecovery      = Color(0xFF34D399) // mint green
val RingStrain        = Color(0xFFFF8A3D) // warm orange
val RingEnergy        = Color(0xFF4DA3FF) // soft electric blue

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
