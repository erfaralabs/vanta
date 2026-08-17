package com.vanta.app.data

import androidx.compose.ui.graphics.Color
import com.vanta.app.ui.theme.*

// ── Data models ───────────────────────────────────────────────────────────────

data class HourlyStepData(val hourLabel: String, val steps: Int)

data class DayStepData(
    val dayLabel: String,
    val dateLabel: String,
    val totalSteps: Int,
    val goalSteps: Int = 10000,
    val distanceKm: Float,
    val caloriesKcal: Int,
    val activeTimeMin: Int,
    val flightsClimbed: Int,
    val avgPaceMinPerKm: String,
    val peakHourLabel: String,
    val hourlySteps: List<HourlyStepData>,
    val isoDate: String = "",
    val strain: Double = 0.0,
    val recovery: Int = 0,
    val energy: Int = 0
)

data class ChartDataPoint(val x: Float, val y: Float)

data class HrZone(val name: String, val minutes: Int, val color: Color, val bpm: String)

data class PersonalRecord(
    val metric: String,
    val value: String,
    val unit: String,
    val date: String,
    val icon: String,
    val color: Color
)

// ── Fallback Structures (Zeroed out to prevent layout pops) ─────────────────

object MockData {

    val daysStepData = listOf(
        DayStepData("Today", "Today", 0, 10000, 0f, 0, 0, 0, "--", "--", emptyList()),
        DayStepData("Yesterday", "Yesterday", 0, 10000, 0f, 0, 0, 0, "--", "--", emptyList()),
        DayStepData("Day 3", "--", 0, 10000, 0f, 0, 0, 0, "--", "--", emptyList()),
        DayStepData("Day 4", "--", 0, 10000, 0f, 0, 0, 0, "--", "--", emptyList()),
        DayStepData("Day 5", "--", 0, 10000, 0f, 0, 0, 0, "--", "--", emptyList()),
        DayStepData("Day 6", "--", 0, 10000, 0f, 0, 0, 0, "--", "--", emptyList()),
        DayStepData("Day 7", "--", 0, 10000, 0f, 0, 0, 0, "--", "--", emptyList())
    )

    val heartRateData = emptyList<ChartDataPoint>()

    val weeklyStrainData = listOf(
        ChartDataPoint(0f, 0f),
        ChartDataPoint(1f, 0f),
        ChartDataPoint(2f, 0f),
        ChartDataPoint(3f, 0f),
        ChartDataPoint(4f, 0f),
        ChartDataPoint(5f, 0f),
        ChartDataPoint(6f, 0f),
    )

    val recoveryTrendData = listOf(
        ChartDataPoint(0f, 75f),
        ChartDataPoint(1f, 75f),
        ChartDataPoint(2f, 75f),
        ChartDataPoint(3f, 75f),
        ChartDataPoint(4f, 75f),
        ChartDataPoint(5f, 75f),
        ChartDataPoint(6f, 75f),
    )

    val trainingLoadData = emptyList<ChartDataPoint>()
    val caloriesData = emptyList<ChartDataPoint>()

    val hrZones = listOf(
        HrZone("Zone 1 — Recovery", 0, Color(0xFF64B5F6), "< 115 bpm"),
        HrZone("Zone 2 — Aerobic",  0, Color(0xFF4CAF50), "115–140 bpm"),
        HrZone("Zone 3 — Tempo",    0, NeonCyan,           "141–162 bpm"),
        HrZone("Zone 4 — Threshold",0, EnergyAmber,        "163–178 bpm"),
        HrZone("Zone 5 — Max",      0, HeartRateRed,       "> 178 bpm"),
    )

    val personalRecords = emptyList<PersonalRecord>()

    val weekLabels    = listOf("Today", "Day 2", "Day 3", "Day 4", "Day 5", "Day 6", "Day 7")
    val monthLabels   = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul")
}
