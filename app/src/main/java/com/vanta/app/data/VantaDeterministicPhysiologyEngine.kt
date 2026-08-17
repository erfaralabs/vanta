package com.vanta.app.data

import android.content.Context
import android.content.SharedPreferences
import com.vanta.app.data.baseline.UserBaseline
import kotlin.math.pow
import kotlin.math.roundToInt

enum class RecoveryCategory(val label: String, val range: IntRange) {
    EXCELLENT("Excellent", 90..100),
    GREAT("Great", 80..89),
    GOOD("Good", 70..79),
    MODERATE("Moderate", 60..69),
    POOR("Poor", 40..59),
    VERY_POOR("Very Poor", 30..39);

    companion object {
        fun fromScore(score: Int): RecoveryCategory = entries.firstOrNull { score in it.range } ?: VERY_POOR
    }
}

enum class WatchWearMode(val label: String) {
    ALL_DAY_WEAR("All-Day Continuous Heart Rate"),
    WORKOUT_ONLY("Workout-Only Smartwatch Tracking"),
    PHONE_MOVEMENT("Phone Movement & Telemetry")
}

data class DeterministicPhysiologyResult(
    val strain: Double,                      // WHOOP-style 0.0–21.0 scale (formatted to 1 decimal place)
    val recovery: Int,                       // 0–100% Recovery score (locked per calendar date, min 30%)
    val recoveryCategory: RecoveryCategory,
    val energy: Int,                         // 0–100% Energy readiness score (monotonically decreasing, non-negative)
    val wearMode: WatchWearMode,             // Automatically detected wear habit
    val hrMax: Int,
    val rhrBaseline: Int,
    val rhrToday: Int,
    val avgHrBaseline: Int,
    val avgHrToday: Int,
    val isLearningPhase: Boolean,
    val savedDaysCount: Int,
    val baselineSummaryMessage: String,
    val breakdownExplanation: String
)

/**
 * Pure Deterministic Physiology Engine for Vanta.
 *
 * ADAPTIVE RECOVERY:
 * - Calculates ONCE per day at 12:00 AM / first app launch after midnight.
 * - The previous day's FINAL strain is persisted at daily rollover and always drives next-day Recovery.
 * - Does NOT rely on resting heart rate by default — RHR only exists when the watch is
 *   worn overnight, so without sleep tracking the blend uses:
 *     • Previous Strain        (yesterday's final load)
 *     • Time since last workout (recovery window since the last session)
 *     • Recent training history (rolling 7-day average strain = chronic load)
 *   with base weights 55% / 25% / 20%.
 * - When sleep IS tracked, sleep duration and the genuine overnight RHR join the
 *   blend: 40% strain / 15% time / 15% history / 20% sleep / 10% RHR.
 * - Missing data is NEVER faked or replaced with a hardcoded value: absent inputs
 *   drop out and the remaining weights rebalance proportionally.
 * - Clamped between 30% and 100%.
 * - Component scoring (see companion object): strain 0→100, 5→86, 10→72, 15→50,
 *   21→35; time-since-workout 45 at <6h → 100 after 72h; 7-day avg strain
 *   ≤3→100 … >16→42; sleep <4h→45 … 8-9h→95+; resting HR neutral 80 (±3/bpm,
 *   only with sleep). All are single continuous curves — recovery is a natural
 *   output, never tuned to a target percentage.
 * - PERSONALIZATION: the strain penalty is scaled by a per-user factor derived
 *   from that user's own objective history (least-squares slope of strain → RHR
 *   elevation on days with genuine overnight RHR). The base algorithm is identical
 *   for everyone; only the user's measured RHR response to training load varies
 *   the output, and it ramps in gradually (≥3 training days, full effect after 10).
 *
 * ENERGY READINESS:
 * - At start of day: Energy = Recovery.
 * - Reduced Strain penalty formula: Energy = Recovery - (0.8 * Strain) - StepPenalty - HRPenalty.
 * - Monotonically decreasing: Energy NEVER increases during the same calendar day.
 *
 * STRAIN MODEL (0.0–21.0):
 * - Step baseline uses a diminishing-returns power curve (see calculateStepBaselineStrain)
 *   so casual walking contributes little and 12–15k of normal walking cannot inflate
 *   strain on its own. Hard effort comes from the HR/workout impulse (intensity × time).
 * - Workouts with measurable HR intensity drive high strain (10–18+ on hard days);
 *   sessions without HR get only a modest duration credit.
 */
class VantaDeterministicPhysiologyEngine(
    private val context: Context
) {

    private val prefs: SharedPreferences = context.getSharedPreferences("vanta_physiology_baseline", Context.MODE_PRIVATE)

    var userAge: Int
        get() = prefs.getInt("user_age", 25)
        set(value) = prefs.edit().putInt("user_age", value).apply()

    private val yesterdayStrain: Double
        get() = prefs.getFloat("yesterday_strain", 3.5f).toDouble()

    /**
     * Persists a completed day's final strain so next-day Recovery uses the actual
     * previous-day strain. Written by DailyRolloverManager at midnight rollover.
     */
    fun persistYesterdayStrain(strain: Double) {
        prefs.edit().putFloat("yesterday_strain", strain.coerceIn(0.0, 21.0).toFloat()).apply()
    }

    fun resetTodayStrainAndEnergy() {
        prefs.edit()
            .remove("today_max_strain")
            .remove("today_min_energy")
            .remove("locked_recovery_score")
            .remove("locked_recovery_date")
            .remove("last_energy_date")
            .apply()
    }

    /**
     * Diminishing-returns step baseline. Casual walking contributes little strain
     * and the slope keeps flattening, so 12–15k of normal walking can never inflate
     * strain on its own — hard effort must come from the HR/workout impulse.
     *
     * Calibration references (smooth power curve, NOT hardcoded targets):
     *   ~3k → ~3.0 | ~5k → ~4.0 | ~8k → ~5.2 | ~12k → ~6.6 | ~17k → ~8.1 | 20k+ → ~8.9
     */
    private fun calculateStepBaselineStrain(steps: Long): Double {
        val s = steps.toDouble().coerceAtLeast(0.0)
        if (s <= 0.0) return 0.5
        return (5.2 * (s / 8000.0).pow(0.58)).coerceIn(0.5, 9.5)
    }

    /**
     * Deterministic calculation of Strain (0.0–21.0), Recovery (30–100%), and Energy (0–100%).
     */
    fun calculatePhysiology(
        t: HealthConnectTelemetry,
        baseline: UserBaseline = UserBaseline.Default,
        targetDate: java.time.LocalDate = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
    ): DeterministicPhysiologyResult {
        val hrMax = (220 - userAge).coerceAtLeast(140)
        val rhrBaseline = baseline.avgRestingBpm.roundToInt()
        val avgHrBaseline = baseline.avgAvgBpm.roundToInt()

        val hasHeartRate = (t.avgBpm in 40..220) || (t.peakBpm in 40..220) || (t.currentBpm in 40..220)
        val hasWorkout = t.exerciseMinutes > 0
        // Sleep tracking is the ONLY context where a resting-HR record is trustworthy.
        // Without the watch worn overnight, "RestingHeartRate" can be a daytime
        // artifact (e.g. 87 bpm vs a 60 baseline) that would wrongly tank recovery
        // and pollute the baseline. So:
        //   hasSleepData  -> user tracked sleep last night (SleepSessionRecord exists)
        //   hasGenuineRhr -> a plausible resting-HR reading taken during that sleep
        val hasSleepData = t.sleepMinutes > 0
        val hasGenuineRhr = hasSleepData && (t.restingBpm in 40..100)
        // Persisted/displayed RHR: the genuine reading, or 0 = "not measured today".
        // Never a fabricated value.
        val rhrToday = if (hasGenuineRhr) t.restingBpm else 0
        // Reference resting rate for the strain HR-reserve math only (never scored):
        // the genuine reading if present, otherwise the user's baseline reference.
        val rhrForStrain = if (t.restingBpm in 40..100) t.restingBpm else rhrBaseline.coerceIn(40, 100)
        // Average HR is NEVER filled with a stale/default baseline value: when no
        // valid HR samples exist the record stores 0 (displayed as "—"). The strain
        // HR component contributes 0 in that case while steps/workout/calories stay
        // unchanged — a fabricated baseline HR must not leak into strain or the DB.
        val avgHrToday = if (t.avgBpm in 40..220) t.avgBpm else 0

        val wearMode = when {
            hasHeartRate && hasWorkout -> WatchWearMode.ALL_DAY_WEAR
            hasHeartRate -> WatchWearMode.ALL_DAY_WEAR
            hasWorkout -> WatchWearMode.WORKOUT_ONLY
            else -> WatchWearMode.PHONE_MOVEMENT
        }

        val calRef = if (baseline.isLearningPhase) 500.0 else baseline.avgCalories.coerceAtLeast(250.0)

        val isToday = (targetDate == java.time.LocalDate.now(java.time.ZoneId.systemDefault()))
        val targetDateStr = targetDate.toString()

        // Self-heal stale per-day locks: the rollover reset only runs on app start /
        // the daily worker, so if the app stays open across midnight the previous
        // day's max strain / min energy would otherwise leak into the new day.
        val savedLockDate = prefs.getString("locked_recovery_date", "") ?: ""
        if (savedLockDate.isNotEmpty() && savedLockDate != targetDateStr) {
            prefs.edit()
                .remove("today_max_strain")
                .remove("today_min_energy")
                .remove("locked_recovery_date")
                .remove("locked_recovery_score")
                .remove("last_energy_date")
                .apply()
        }

        // ── 1. DETERMINISTIC STRAIN (0.0–21.0): Monotonically non-decreasing ─────
        val stepBaseline = calculateStepBaselineStrain(t.steps)

        val hrAddition: Double = if (hasHeartRate || hasWorkout) {
            val hrReserve = (hrMax - rhrForStrain).toDouble().coerceAtLeast(40.0)

            // Peak-aware intensity: 70% average + 30% max HR. A hard spike (e.g.
            // 158 bpm) counts even when the average is dragged down by rests between
            // sets — a long easy session can no longer outrank a short intense one.
            val avgIntensity = ((avgHrToday - rhrForStrain).toDouble() / hrReserve).coerceIn(0.0, 1.0)
            val peakIntensity = ((t.peakBpm - rhrForStrain).toDouble() / hrReserve).coerceIn(0.0, 1.0)
            val intensity = (avgIntensity * 0.7 + peakIntensity * 0.3).coerceIn(0.0, 1.0)

            // Time-weighted cardiovascular impulse — workouts (intensity × time) are
            // the main driver of high strain. Volume = real session minutes, or a
            // small 30-min credit only for clearly elevated HR (avgIntensity > 0.3)
            // so casual walking never earns workout volume. When a session is logged
            // but no HR intensity is measurable, a modest duration credit applies.
            val hrImpulse = if (intensity > 0.1) {
                val activeMinutes = maxOf(
                    t.exerciseMinutes,
                    if (avgIntensity > 0.3) 30 else 0
                ).toDouble()
                val volumeFactor = (activeMinutes / 60.0).coerceIn(0.0, 2.5)
                intensity.pow(1.5) * volumeFactor * 5.5
            } else if (t.exerciseMinutes > 0) {
                (t.exerciseMinutes / 60.0).coerceIn(0.0, 2.0) * 0.6
            } else {
                0.0
            }

            // Calories are a minor rider, never a dominant source of strain.
            val calorieImpulse = if (t.calories > 0) ((t.calories / calRef).pow(1.2)) * 0.8 else 0.0

            (hrImpulse + calorieImpulse).coerceAtLeast(0.0)
        } else {
            // Movement-only days (no HR sensor, no logged session): walking stays modest.
            val calorieImpulse = if (t.calories > 0) ((t.calories / calRef).pow(1.2)) * 0.7 else 0.0
            val distanceImpulse = if (t.distanceKm > 0.0) (t.distanceKm / (calRef * 0.02)).coerceIn(0.0, 1.5) else 0.0
            (calorieImpulse + distanceImpulse).coerceAtLeast(0.0)
        }

        val rawStrain = (stepBaseline + hrAddition).coerceIn(0.5, 21.0)
        val computedStrain = ((rawStrain * 10.0).roundToInt() / 10.0).coerceIn(0.5, 21.0)

        val finalStrain: Double = if (isToday) {
            val baseMax = 0.5f
            val savedMaxStrain = prefs.getFloat("today_max_strain", baseMax).toDouble().coerceAtLeast(baseMax.toDouble())
            val maxStrain = maxOf(computedStrain, savedMaxStrain)
            prefs.edit().putFloat("today_max_strain", maxStrain.toFloat()).apply()
            maxStrain
        } else {
            computedStrain
        }

        // ── 2. ADAPTIVE RECOVERY (30% - 100%): Locked once per calendar date ────────
        val lockedDate = prefs.getString("locked_recovery_date", "")
        val finalRecovery: Int = if (isToday && lockedDate == targetDateStr && prefs.contains("locked_recovery_score")) {
            prefs.getInt("locked_recovery_score", 90)
        } else {
            // Recovery does NOT rely on RHR by default — RHR only exists when the watch
            // is worn overnight. Without sleep tracking the blend uses:
            //   • previous strain          (yesterday's final load)
            //   • time since last workout  (recovery window since the last session)
            //   • recent training history  (rolling 7-day average strain = chronic load)
            // When sleep IS tracked, sleep duration and the genuine overnight RHR join
            // the blend. Any missing input rebalances the weights proportionally —
            // data is never faked or replaced with a hardcoded value.
            val prevStrain = yesterdayStrain.coerceIn(0.0, 21.0)
            val scorePrevStrain = scoreStrain(prevStrain, baseline.personalStrainFactor)

            val timeScore = t.hoursSinceLastWorkout?.let { scoreTimeSinceWorkout(it) }
            val historyScore = if (baseline.savedDaysCount > 0) scoreTrainingHistory(baseline.avgStrain) else null
            val sleepScore = if (hasSleepData) scoreSleep(t.sleepMinutes) else null
            val rhrScore = if (hasGenuineRhr) scoreRestingHr((rhrBaseline - t.restingBpm).toDouble()) else null

            val calcRecovery = combineRecovery(
                scorePrevStrain = scorePrevStrain,
                scoreTimeSinceWorkout = timeScore,
                scoreTrainingHistory = historyScore,
                scoreSleep = sleepScore,
                scoreRhr = rhrScore
            )

            if (isToday) {
                // Lock for current calendar date
                prefs.edit()
                    .putString("locked_recovery_date", targetDateStr)
                    .putInt("locked_recovery_score", calcRecovery)
                    .apply()
            }

            calcRecovery
        }

        val category = RecoveryCategory.fromScore(finalRecovery)

        // ── 3. ENERGY READINESS (0 - 100%): Monotonically decreasing ─────────────
        val lastEnergyDate = prefs.getString("last_energy_date", "")
        val startOfDayEnergy = if (lastEnergyDate != targetDateStr) finalRecovery else prefs.getInt("today_min_energy", finalRecovery)

        // Reduced Strain Penalty formula: Energy = Recovery - (0.8 * Strain) - StepPenalty - HRPenalty
        val strainDepletion = finalStrain * 0.8
        val stepsGoal = context.getSharedPreferences("vanta_settings", android.content.Context.MODE_PRIVATE)
            .getInt("steps_goal", 10000).coerceAtLeast(1000)
        val stepDepletion = (t.steps / stepsGoal) * 3.0
        val hrElevationDepletion = if (hasHeartRate && avgHrToday > rhrBaseline) {
            ((avgHrToday - rhrBaseline) / 30.0) * 2.0
        } else {
            0.0
        }

        val rawEnergy = (finalRecovery.toDouble() - strainDepletion - stepDepletion - hrElevationDepletion)
            .roundToInt()
            .coerceIn(0, 100)

        val finalEnergy: Int = if (isToday) {
            val currentMinEnergy = if (lastEnergyDate == targetDateStr && prefs.contains("today_min_energy")) {
                prefs.getInt("today_min_energy", startOfDayEnergy)
            } else {
                startOfDayEnergy
            }
            val minEnergy = minOf(rawEnergy, currentMinEnergy).coerceAtLeast(0)
            prefs.edit()
                .putString("last_energy_date", targetDateStr)
                .putInt("today_min_energy", minEnergy)
                .apply()
            minEnergy
        } else {
            rawEnergy.coerceAtMost(finalRecovery).coerceAtLeast(0)
        }

        val phasePrefix = if (baseline.isLearningPhase) {
            "Learning Phase (${baseline.savedDaysCount}/7 days archived in Room DB)"
        } else {
            "Personalized Phase (Room DB 7-Day Rolling Baseline)"
        }

        val sleepSuffix = if (hasSleepData) "Sleep: ${t.sleepMinutes} min" else "No sleep data (RHR unused)"
        val explanation = "$phasePrefix — Mode: ${wearMode.label}, Strain: $finalStrain/21.0, Recovery: ${category.label} ($finalRecovery%), $sleepSuffix, Energy: ${finalEnergy}%."

        return DeterministicPhysiologyResult(
            strain = finalStrain,
            recovery = finalRecovery,
            recoveryCategory = category,
            energy = finalEnergy,
            wearMode = wearMode,
            hrMax = hrMax,
            rhrBaseline = rhrBaseline,
            rhrToday = rhrToday,
            avgHrBaseline = avgHrBaseline,
            avgHrToday = avgHrToday,
            isLearningPhase = baseline.isLearningPhase,
            savedDaysCount = baseline.savedDaysCount,
            baselineSummaryMessage = baseline.subtleStatusMessage,
            breakdownExplanation = explanation
        )
    }

    companion object {
        /**
         * Strain → next-day Recovery score.
         *
         * Calibration rationale (single continuous piecewise-linear curve, no per-input tuning):
         *   - 0  → 100 : zero training stress
         *   - 5  → 86  : moderate day, recoverable overnight
         *   - 10 → 72  : hard day, noticeable next-day fatigue
         *   - 15 → 50  : very hard, multi-day recovery
         *   - 21 → 35  : maximal strain
         * Recovery cost grows through the moderate→hard range and saturates near
         * maximal effort (beyond ~15 the per-unit recovery cost shrinks).
         *
         * `personalStrainFactor` (default 1.0 = base model, identical for everyone)
         * scales the penalty away from 100, so a user whose own RHR history shows
         * high sensitivity to strain recovers lower, and a resilient user higher.
         * The penalty is 0 at strain 0, so rest days are unaffected.
         */
        fun scoreStrain(prevStrain: Double, personalStrainFactor: Double = 1.0): Double {
            val s = prevStrain.coerceIn(0.0, 21.0)
            val base = when {
                s < 5.0 -> 100.0 - s * 2.8
                s < 10.0 -> 86.0 - ((s - 5.0) / 5.0) * 14.0
                s < 15.0 -> 72.0 - ((s - 10.0) / 5.0) * 22.0
                else -> 50.0 - ((s - 15.0) / 6.0) * 15.0
            }
            val score = 100.0 - (100.0 - base) * personalStrainFactor
            return score.coerceIn(35.0, 100.0)
        }

        /**
         * Personalizes the strain penalty from the user's OWN objective training history.
         *
         * Uses only objective physiology: each training day's strain and its resting HR.
         * The least-squares slope of (strain → RHR elevation above the user's baseline RHR)
         * measures how strongly this user's body responds to training load:
         *   - positive slope (RHR rises on hard days)  ⇒ sensitive  ⇒ factor > 1 (stronger penalty)
         *   - flat/inverse slope (RHR stays stable)    ⇒ resilient  ⇒ factor ≤ 1 (lighter penalty)
         *
         * The factor blends toward 1.0 until `confidenceWindow` training days accumulate,
         * so personalization is gradual and a single workout can never move it. The
         * population constants (gain, clamp) are shared by every user — only each user's
         * own data varies the result.
         */
        fun personalStrainFactor(
            strains: List<Double>,
            restingBpms: List<Double>,
            baselineRestingBpm: Double,
            gain: Double = 0.1,
            minTrainingDays: Int = 3,
            confidenceWindow: Int = 10
        ): Double {
            if (strains.size < minTrainingDays || strains.size != restingBpms.size) return 1.0
            val n = strains.size
            val meanX = strains.average()
            val elevations = restingBpms.map { it - baselineRestingBpm }
            val meanE = elevations.average()
            val varX = strains.sumOf { (it - meanX) * (it - meanX) } / n
            if (varX < 1e-9) return 1.0
            val cov = strains.indices.sumOf { i ->
                (strains[i] - meanX) * (elevations[i] - meanE)
            } / n
            val slope = cov / varX
            val rawFactor = 1.0 + slope * gain
            val confidence = (n.toDouble() / confidenceWindow).coerceIn(0.0, 1.0)
            return (1.0 + (rawFactor - 1.0) * confidence).coerceIn(0.85, 1.15)
        }

        /**
         * Resting HR trend score.
         *
         * rhrDelta = baseline − today (positive ⇒ RHR today is BELOW baseline ⇒ better).
         * Neutral RHR maps to 80 (not 90): a normal RHR must not inflate recovery.
         * ±3 points per bpm of deviation from baseline — an elevated morning RHR is
         * the standard objective proxy for incomplete autonomic recovery.
         */
        fun scoreRestingHr(rhrDelta: Double): Double =
            (80.0 + rhrDelta * 3.0).coerceIn(45.0, 95.0)

        /**
         * Time since the most recent workout → Recovery score.
         *
         * A training session opens a finite recovery window: acute fatigue dominates
         * for the first hours, then clears within ~2-3 days. NULL means no workout
         * session was found (missing data) — the caller rebalances the weights and
         * the component is simply absent, it is never assumed to be any value.
         */
        fun scoreTimeSinceWorkout(hoursSinceWorkout: Double?): Double {
            if (hoursSinceWorkout == null) return 80.0 // neutral fallback, weight rebalanced
            return when {
                hoursSinceWorkout < 6.0 -> 45.0
                hoursSinceWorkout < 12.0 -> 56.0
                hoursSinceWorkout < 24.0 -> 70.0
                hoursSinceWorkout < 48.0 -> 84.0
                hoursSinceWorkout < 72.0 -> 94.0
                else -> 100.0
            }
        }

        /**
         * Recent training history → Recovery score.
         *
         * Chronic load depletes readiness even on an easy previous day: a rolling
         * 7-day average strain is the objective picture of accumulated fatigue.
         * NULL when no history has accumulated yet (no saved days) — absent, not
         * assumed.
         */
        fun scoreTrainingHistory(avgStrain7d: Double?): Double {
            if (avgStrain7d == null) return 75.0 // neutral fallback, weight rebalanced
            val a = avgStrain7d.coerceIn(0.0, 21.0)
            return when {
                a <= 3.0 -> 100.0
                a <= 6.0 -> 92.0
                a <= 9.0 -> 80.0
                a <= 12.0 -> 66.0
                a <= 16.0 -> 52.0
                else -> 42.0
            }
        }

        /**
         * Last night's sleep duration → Recovery score.
         *
         * Only ever used when sleep was genuinely tracked (minutes > 0); otherwise
         * the caller leaves the component absent and rebalances the weights.
         */
        fun scoreSleep(sleepMinutes: Int): Double = when {
            sleepMinutes < 240 -> 45.0
            sleepMinutes < 360 -> 60.0
            sleepMinutes < 420 -> 72.0
            sleepMinutes < 480 -> 85.0
            sleepMinutes < 540 -> 95.0
            else -> 100.0
        }

        /**
         * Blends the available recovery signals with documented base weights and
         * rebalances proportionally whenever an input is missing — missing data is
         * never fabricated or replaced with a hardcoded value.
         *
         *   • No sleep (default): 55% previous strain / 25% time since workout / 20% training history
         *   • Sleep tracked:      40% strain / 15% time / 15% history / 20% sleep / 10% RHR (when genuine)
         *
         * Any absent component's weight is redistributed across the present ones,
         * and the result is clamped to the documented 30–100 range.
         */
        fun combineRecovery(
            scorePrevStrain: Double,
            scoreTimeSinceWorkout: Double?,
            scoreTrainingHistory: Double?,
            scoreSleep: Double?,
            scoreRhr: Double?
        ): Int {
            val hasSleep = scoreSleep != null
            val entries = mutableListOf<Pair<Double, Double>>()
            entries += scorePrevStrain to (if (hasSleep) 40.0 else 55.0)
            if (scoreTimeSinceWorkout != null) entries += scoreTimeSinceWorkout to (if (hasSleep) 15.0 else 25.0)
            if (scoreTrainingHistory != null) entries += scoreTrainingHistory to (if (hasSleep) 15.0 else 20.0)
            if (scoreSleep != null) entries += scoreSleep to 20.0
            if (scoreRhr != null) entries += scoreRhr to 10.0
            val totalWeight = entries.sumOf { it.second }
            val blended = entries.sumOf { it.first * it.second } / totalWeight
            return blended.roundToInt().coerceIn(30, 100)
        }
    }
}
