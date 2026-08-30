package com.vanta.app.data.intelligence

import com.vanta.app.data.db.DailyMetricRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * VANTA Adaptive Core — continuous intelligence layer that fine-tunes the
 * VANTA Adaptive Core Engine (VANTIX)
 *
 * Computes Training Load Intelligence (ATL, CTL, TSB), Step Trend Analysis,
 * and Training Mode classification for VANTIX.
 */
object AdaptiveIntelligenceEngine {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Minimum days before Adaptive Core activates (needs enough history). */
    const val MIN_DAYS_FOR_CORE = 14

    /** Rolling window for user mode classification. */
    private const val MODE_WINDOW_DAYS = 30

    /** Strain threshold for counting a day as an "active session". */
    private const val ACTIVE_SESSION_STRAIN = 5.5

    /** Training mode threshold: if >= this fraction of days are active sessions. */
    private const val TRAINING_MODE_THRESHOLD = 0.25

    // ── EWMA decay factors ────────────────────────────────────────────────────
    private const val ATL_DECAY = 1.0 / 7.0
    private const val CTL_DECAY = 1.0 / 42.0

    // ── Result ────────────────────────────────────────────────────────────────

    data class AdaptiveCoreResult(
        /** Acute Training Load — 7-day EWMA of training strain. */
        val atl: Double,
        /** Chronic Training Load — 42-day EWMA of training strain. */
        val ctl: Double,
        /** Training Stress Balance (freshness): CTL − ATL. Positive = fresh, negative = fatigued. */
        val tsb: Double,
        /** ATL ÷ CTL ratio. >1.3 overreaching, 0.8–1.3 optimal, <0.8 underloaded. */
        val atlCtlRatio: Double,
        /** 7-day linear slope of recovery scores: positive = improving, negative = declining. */
        val readinessTrend: Double,
        /** User's 90th-percentile strain across all history (personalized effort ceiling reference). */
        val strainCeiling: Double,
        /** User's activity consistency score — fraction of days with meaningful movement (0–1). */
        val activityConsistency: Double,
        /** Whether the user is classified as Training mode (>=25% active sessions). */
        val isTrainingMode: Boolean,
        /** Total number of days in the history used for computation. */
        val totalDaysTracked: Int,
        /** Current load status label. */
        val loadStatus: LoadStatus,
        /** ATL trend vs the previous 7 days (positive = load increasing). */
        val atlTrend: Double,
        /** 14-day slope of daily steps — positive = moving more, negative = moving less. */
        val stepTrend: Double = 0.0,
        /** User's rolling average steps over the last 14 days. */
        val avgSteps14d: Double = 0.0,
    ) {
        val isAthleteMode: Boolean get() = isTrainingMode
    }

    enum class LoadStatus(val label: String, val description: String) {
        OVERREACHING("Overreaching", "Acute load exceeds chronic base — ease off to let fitness compound"),
        OPTIMAL("Optimal Zone", "Load balanced — you're in the ideal build window"),
        UNDERLOADED("Underloaded", "Training load has dropped — your fitness base is fading"),
        DAILY_MOVER("Daily Mover", "Consistent daily movement — no structured training load detected"),
        INSUFFICIENT_DATA("Building", "Keep training — Adaptive Core needs more data")
    }

    // ── Main compute ──────────────────────────────────────────────────────────

    /**
     * Computes all Adaptive Core signals from the full historical record list.
     * Records should be in date-descending order (newest first) as returned by Room.
     * Returns null if insufficient data (< MIN_DAYS_FOR_CORE real days).
     */
    fun compute(records: List<DailyMetricRecord>): AdaptiveCoreResult? {
        // Only COMPLETED archived days count toward Adaptive Core. The still-in-progress
        // "today" is not a tracked day yet, and a date is never counted twice — otherwise
        // day-progress / core-activation would run ahead of real history ("extra days").
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        val real = records.filter { it.hasRealData() }
            .filter { it.date < today }
            .distinctBy { it.date }
        if (real.size < MIN_DAYS_FOR_CORE) return null

        // Sort oldest-first for EWMA calculation
        val sorted = real.sortedBy { it.date }

        // ── User mode classification (rolling 30 days) ────────────────────────
        val modeWindow = sorted.takeLast(MODE_WINDOW_DAYS)
        val activeDaysInWindow = modeWindow.count { it.strain >= ACTIVE_SESSION_STRAIN || it.workoutDurationMin >= 15 }
        val isTrainingMode = (activeDaysInWindow.toDouble() / modeWindow.size.coerceAtLeast(1)) >= TRAINING_MODE_THRESHOLD

        // ── EWMA ATL / CTL ────────────────────────────────────────────────────
        var atl = sorted.first().strain
        var ctl = sorted.first().strain
        var atlPrev7Ago = atl

        val n = sorted.size
        for (i in 0 until n) {
            val s = sorted[i].strain
            atl = atl + ATL_DECAY * (s - atl)
            ctl = ctl + CTL_DECAY * (s - ctl)
            if (i == (n - 8).coerceAtLeast(0)) {
                atlPrev7Ago = atl
            }
        }

        val tsb = ctl - atl
        val atlCtlRatio = if (ctl > 0.1) atl / ctl else 1.0
        val atlTrend = atl - atlPrev7Ago

        // ── Readiness trend (last 7 days linear slope) ─────────────────────────
        val last7Recoveries = sorted.takeLast(7).map { it.recovery.toDouble() }
        val readinessTrend = linearSlope(last7Recoveries)

        // ── Strain ceiling (90th percentile) ──────────────────────────────────
        val strains = sorted.map { it.strain }.sorted()
        val p90Index = ((strains.size - 1) * 0.90).roundToInt().coerceIn(0, strains.size - 1)
        val strainCeiling = strains[p90Index]

        // ── Activity consistency (fraction of all days with meaningful steps) ──
        val activeDays = sorted.count { it.steps >= 2000 || it.workoutDurationMin >= 10 }
        val activityConsistency = activeDays.toDouble() / sorted.size.coerceAtLeast(1)

        // ── Step trend (14-day slope) ─────────────────────────────────────────
        val stepWindow = sorted.takeLast(14)
        val stepTrend = linearSlope(stepWindow.map { it.steps.toDouble() })
        val avgSteps14d = if (stepWindow.isNotEmpty()) stepWindow.map { it.steps }.average() else 0.0

        // ── Load status ───────────────────────────────────────────────────────
        val loadStatus = when {
            !isTrainingMode -> LoadStatus.DAILY_MOVER
            atl < 1.0 -> LoadStatus.INSUFFICIENT_DATA
            atlCtlRatio > 1.3 -> LoadStatus.OVERREACHING
            atlCtlRatio < 0.8 -> LoadStatus.UNDERLOADED
            else -> LoadStatus.OPTIMAL
        }

        return AdaptiveCoreResult(
            atl = atl,
            ctl = ctl,
            tsb = tsb,
            atlCtlRatio = atlCtlRatio,
            readinessTrend = readinessTrend,
            strainCeiling = strainCeiling,
            activityConsistency = activityConsistency,
            isTrainingMode = isTrainingMode,
            totalDaysTracked = sorted.size,
            loadStatus = loadStatus,
            atlTrend = atlTrend,
            stepTrend = stepTrend,
            avgSteps14d = avgSteps14d
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Least-squares slope of a value sequence (positive = increasing trend). */
    private fun linearSlope(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val meanX = (n - 1) / 2.0
        val meanY = values.average()
        val varX = (0 until n).sumOf { i -> (i - meanX) * (i - meanX) } / n
        if (varX < 1e-9) return 0.0
        val cov = (0 until n).sumOf { i -> (i - meanX) * (values[i] - meanY) } / n
        return cov / varX
    }

    /** Maps TSB to a recovery modifier for the physiology engine. */
    fun tsbRecoveryModifier(core: AdaptiveCoreResult): Double {
        if (!core.isAthleteMode || core.atl < 1.0) return 0.0
        return when {
            core.tsb > 10.0  -> ((core.tsb - 10.0) / 10.0).coerceAtMost(1.0) * 3.0  // +3% max bonus
            core.tsb < -15.0 -> ((core.tsb + 15.0) / 10.0).coerceAtLeast(-1.0) * 5.0 // -5% max penalty
            else             -> 0.0
        }
    }
}
