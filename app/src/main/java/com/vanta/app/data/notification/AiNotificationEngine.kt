package com.vanta.app.data.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.vanta.app.data.AiProvider
import com.vanta.app.data.ai.CoachPromptSystem
import com.vanta.app.data.DeterministicPhysiologyResult
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.VantaGemmaEngine
import com.vanta.app.data.baseline.UserBaseline
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.VantaDatabase
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * Premium AI Notification Engine.
 *
 * Only meaningful events produce notifications: morning recovery, workout
 * completed, strain spike >= 1.5, recovery change >= 10%, achievements /
 * milestones, weekly summary, goal reached. Never fires on small step changes,
 * minor HR drift, calorie/distance updates, app launches, or routine syncs.
 *
 * API budget: 3 AI-generated notifications/day (soft) then intelligent templates;
 * 5 total notifications/day (hard) then silence.
 */
class AiNotificationEngine(private val context: Context) {

    private val settings = NotificationSettings(context)
    private val prefs = context.getSharedPreferences("vanta_notification_state", Context.MODE_PRIVATE)
    private val gemma = VantaGemmaEngine(context)
    private val dao = VantaDatabase.getInstance(context).dailyMetricsDao()

    companion object {
        const val SOFT_AI_LIMIT = 3       // default AI calls per day (user-adjustable 3–10)
        const val HARD_TOTAL_LIMIT = 15   // total notifications per day (AI + templates)
        const val STRAIN_DELTA = 1.5      // significant strain increase
        const val RECOVERY_DELTA = 10     // recovery change threshold
        const val MIN_WORKOUT_MINUTES = 15
        val STREAK_MILESTONES = intArrayOf(3, 7, 14, 21, 30)
    }

    private val today: String get() = LocalDate.now(ZoneId.systemDefault()).toString()
    private val daySeed: Int get() = today.hashCode()
    private val nowMs: Long get() = System.currentTimeMillis()

    private data class NotificationEvent(
        val reason: String,
        val priority: String,
        val recovery: Int,
        val energy: Int,
        val strain: Double,
        val steps: Long,
        val workoutMinutes: Int,
        val weeklyAvgStrain: Double,
        val streak: Int = 0,
        val weekWorkouts: Int = 0,
        val weekSteps: Long = 0
    )

    private val notificationBuffer = AiNotificationBuffer(context)

    /**
     * Evaluates the current state and returns a notification decision, or null
     * when nothing meaningful happened / budget is exhausted / category is off.
     */
    suspend fun evaluate(
        telemetry: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline
    ): NotificationDecision? {
        if (!settings.enabled) return null

        // Never consume the daily budget for notifications that can't be shown.
        // Until POST_NOTIFICATIONS is granted (Android 13+), events are evaluated
        // but not counted, so the full 5/day budget is available the moment the
        // user grants permission instead of being wasted on invisible posts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, NotificationPoster.NOTIFICATION_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        resetBudgetIfNewDay()
        if (totalCount() >= HARD_TOTAL_LIMIT) return null

        val event = detectEvent(telemetry, det, baseline) ?: return null

        // Dedupe: same reason firing again within 60s (worker + foreground race).
        if (prefs.getLong("last_${event.reason}_time", 0L) > nowMs - 60_000L) return null

        // 1. Check if we have a pre-generated AI notification draft from daily analysis (0MB background load)
        val bufferedDraft = notificationBuffer.popDraft(event.reason, today)
        val aiDecision = if (bufferedDraft != null) {
            NotificationDecision(
                notify = true,
                title = bufferedDraft.title,
                message = bufferedDraft.message,
                priority = bufferedDraft.priority,
                reason = event.reason
            )
        } else if (aiCount() < settings.aiLimitPerDay) {
            generateWithAi(event)
        } else null

        val decision = aiDecision ?: generateTemplate(event)

        // Personalize the message body with the name collected during onboarding
        // (title stays clean). Name comes first and follows title-casing rules.
        val name = runCatching {
            VantaDatabase.getInstance(context).userProfileDao().getUserProfile()?.name?.trim().orEmpty()
        }.getOrDefault("")
        val displayName = name.replaceFirstChar { it.uppercase() }
        val finalDecision = if (displayName.isNotBlank() && !decision.message.contains(name, ignoreCase = true)) {
            decision.copy(message = "$displayName, ${decision.message}")
        } else decision

        prefs.edit()
            .putLong("last_${event.reason}_time", nowMs)
            .putString("budget_date", today)
            .putInt("total_count_today", totalCount() + 1)
            .putInt("ai_count_today", aiCount() + (if (aiDecision != null) 1 else 0))
            .apply()

        return finalDecision
    }

    // ── Event detection ────────────────────────────────────────────────────────

    private suspend fun detectEvent(
        t: HealthConnectTelemetry,
        det: DeterministicPhysiologyResult,
        baseline: UserBaseline
    ): NotificationEvent? {
        val base = NotificationEvent(
            reason = "", priority = "",
            recovery = det.recovery, energy = det.energy, strain = det.strain,
            steps = t.steps, workoutMinutes = t.exerciseMinutes,
            weeklyAvgStrain = baseline.avgStrain
        )
        val records = dao.getAllRecords()

        // 1. Workout completed (highest value, not HR-dependent).
        if (settings.workout && workoutEventFired(t.exerciseMinutes)) {
            return base.copy(reason = "workout", priority = "high")
        }

        // 2. Significant strain increase (works from strain alone — no HR needed,
        //    since strain is derived from steps/workouts even without a watch).
        if (settings.strain && strainEventFired(det.strain)) {
            return base.copy(reason = "strain", priority = "high")
        }

        // 3. Recovery changed by >= 10% mid-day (RHR-driven — gated by heartRate).
        if (settings.morningRecovery && settings.heartRate && recoveryChangeFired(det.recovery)) {
            return base.copy(reason = "recovery", priority = "normal")
        }

        // 4. Achievement / milestone.
        if (settings.achievement) {
            val streak = trainingStreak(records)
            if (streak in STREAK_MILESTONES && prefs.getInt("last_streak_milestone", 0) != streak) {
                prefs.edit().putInt("last_streak_milestone", streak).apply()
                return base.copy(reason = "achievement", priority = "normal", streak = streak)
            }
            if (t.steps >= 10_000 && !prefs.getBoolean("achievement_10k_$today", false)) {
                prefs.edit().putBoolean("achievement_10k_$today", true).apply()
                return base.copy(reason = "achievement", priority = "normal", streak = 0)
            }
        }

        // 5. Morning recovery (once/day, after 05:00).
        if (settings.morningRecovery && morningRecoveryFired(det.recovery)) {
            return base.copy(reason = "recovery", priority = "normal")
        }

        // 6. Dynamic Intraday WHOOP-style spontaneous check-ins (Midday, Afternoon, Evening)
        if (settings.intradayNudges) {
            if (middayPacingFired(det.recovery, det.strain)) {
                return base.copy(reason = "midday", priority = "normal")
            }
            if (afternoonEnergyFired(det.energy, t.steps)) {
                return base.copy(reason = "afternoon", priority = "normal")
            }
            if (eveningWindDownFired(det.strain, det.recovery)) {
                return base.copy(reason = "evening", priority = "normal")
            }
        }

        // 7. Weekly summary (every 7 days).
        if (settings.weekly && weeklyDue(baseline)) {
            prefs.edit().putString("last_weekly_date", today).apply()
            val (weekWorkouts, weekSteps) = weeklyAggregates(records)
            return base.copy(reason = "weekly", priority = "normal", weekWorkouts = weekWorkouts, weekSteps = weekSteps)
        }

        // 8. Goal reached (5 workouts in the current week).
        if (settings.goals) {
            val (weekWorkouts, _) = weeklyAggregates(records)
            if (goalReached(weekWorkouts)) {
                return base.copy(reason = "goal", priority = "high", weekWorkouts = weekWorkouts)
            }
        }

        return null
    }



    private fun workoutEventFired(exerciseMinutes: Int): Boolean {
        prefs.edit().putInt("last_seen_workout_minutes", exerciseMinutes).apply()
        if (exerciseMinutes < MIN_WORKOUT_MINUTES) return false

        val lastDate = prefs.getString("last_workout_notif_date", "")
        val lastMinutes = prefs.getInt("last_workout_notif_minutes", 0)
        val lastTime = prefs.getLong("last_workout_notif_time", 0L)

        val firstWorkoutToday = lastDate != today
        val freshSession = (exerciseMinutes - lastMinutes) >= MIN_WORKOUT_MINUTES &&
            (nowMs - lastTime) > 4L * 60L * 60L * 1000L

        if (firstWorkoutToday || freshSession) {
            prefs.edit()
                .putString("last_workout_notif_date", today)
                .putInt("last_workout_notif_minutes", exerciseMinutes)
                .putLong("last_workout_notif_time", nowMs)
                .apply()
            return true
        }
        return false
    }

    private fun strainEventFired(strain: Double): Boolean {
        val lastDate = prefs.getString("last_strain_date", "")
        val last = prefs.getFloat("last_notified_strain", -1f)
        if (lastDate != today) {
            // New day: reset baseline so we only fire on intra-day jumps.
            prefs.edit().putString("last_strain_date", today).putFloat("last_notified_strain", -1f).apply()
            return false
        }
        if (last < 0f) {
            prefs.edit().putFloat("last_notified_strain", strain.toFloat()).apply()
            return false
        }
        if (strain >= 4.0 && (strain - last) >= STRAIN_DELTA) {
            prefs.edit().putFloat("last_notified_strain", strain.toFloat()).apply()
            return true
        }
        return false
    }

    private fun recoveryChangeFired(recovery: Int): Boolean {
        val last = prefs.getInt("last_notified_recovery", -1)
        if (last < 0) return false
        if (abs(recovery - last) >= RECOVERY_DELTA) {
            prefs.edit().putInt("last_notified_recovery", recovery).apply()
            return true
        }
        return false
    }

    private fun morningRecoveryFired(recovery: Int): Boolean {
        if (prefs.getString("last_morning_date", "") == today) return false
        val hour = LocalTime.now(ZoneId.systemDefault()).hour
        if (hour < 5) return false // wait for the morning, not a 00:15 lock
        prefs.edit()
            .putString("last_morning_date", today)
            .putInt("last_notified_recovery", recovery)
            .apply()
        return true
    }

    private fun middayPacingFired(recovery: Int, strain: Double): Boolean {
        if (prefs.getString("last_midday_date", "") == today) return false
        val now = LocalTime.now(ZoneId.systemDefault())
        val jitterMinute = abs(daySeed).mod(40) // 0 to 39 minutes offset
        val triggerTime = LocalTime.of(11, 30).plusMinutes(jitterMinute.toLong())
        if (now.isBefore(triggerTime) || now.isAfter(LocalTime.of(14, 0))) return false
        prefs.edit().putString("last_midday_date", today).apply()
        return true
    }

    private fun afternoonEnergyFired(energy: Int, steps: Long): Boolean {
        if (prefs.getString("last_afternoon_date", "") == today) return false
        val now = LocalTime.now(ZoneId.systemDefault())
        val jitterMinute = abs(daySeed * 7).mod(45) // 0 to 44 minutes offset
        val triggerTime = LocalTime.of(14, 45).plusMinutes(jitterMinute.toLong())
        if (now.isBefore(triggerTime) || now.isAfter(LocalTime.of(17, 30))) return false
        prefs.edit().putString("last_afternoon_date", today).apply()
        return true
    }

    private fun eveningWindDownFired(strain: Double, recovery: Int): Boolean {
        if (prefs.getString("last_evening_date", "") == today) return false
        val now = LocalTime.now(ZoneId.systemDefault())
        val jitterMinute = abs(daySeed * 13).mod(40) // 0 to 39 minutes offset
        val triggerTime = LocalTime.of(18, 15).plusMinutes(jitterMinute.toLong())
        if (now.isBefore(triggerTime) || now.isAfter(LocalTime.of(20, 30))) return false
        prefs.edit().putString("last_evening_date", today).apply()
        return true
    }

    private fun weeklyDue(baseline: UserBaseline): Boolean {
        val last = prefs.getString("last_weekly_date", "")
        if (last.isNullOrEmpty()) return baseline.savedDaysCount >= 7 // first summary after a real week
        val lastDate = runCatching { LocalDate.parse(last) }.getOrNull() ?: return false
        return ChronoUnit.DAYS.between(lastDate, LocalDate.now(ZoneId.systemDefault())) >= 7
    }

    private fun goalReached(weekWorkouts: Int): Boolean {
        val weekKey = LocalDate.now(ZoneId.systemDefault())
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        if (prefs.getString("goal_week_$weekKey", "") == weekKey) return false
        if (weekWorkouts >= 5) {
            prefs.edit().putString("goal_week_$weekKey", weekKey).apply()
            return true
        }
        return false
    }

    private fun trainingStreak(records: List<DailyMetricRecord>): Int {
        var streak = 0
        for ((index, r) in records.withIndex()) {
            val isTodayRecord = r.date == today
            val trained = r.workoutDurationMin >= MIN_WORKOUT_MINUTES || r.strain >= 4.0
            if (index == 0 && isTodayRecord && !trained) continue // today not trained yet — count from yesterday
            if (trained) streak++ else if (streak > 0) break
            if (index >= 29) break
        }
        return streak
    }

    private fun weeklyAggregates(records: List<DailyMetricRecord>): Pair<Int, Long> {
        val weekStart = LocalDate.now(ZoneId.systemDefault())
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var workouts = 0
        var steps = 0L
        records.forEach { r ->
            val date = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return@forEach
            if (!date.isBefore(weekStart)) {
                if (r.workoutDurationMin >= MIN_WORKOUT_MINUTES || r.strain >= 4.0) workouts++
                steps += r.steps
            }
        }
        return workouts to steps
    }

    // ── Budget helpers ─────────────────────────────────────────────────────────

    private fun resetBudgetIfNewDay() {
        if (prefs.getString("budget_date", "") != today) {
            prefs.edit()
                .putString("budget_date", today)
                .putInt("ai_count_today", 0)
                .putInt("total_count_today", 0)
                .apply()
        }
    }

    private fun aiCount(): Int = prefs.getInt("ai_count_today", 0)
    private fun totalCount(): Int = prefs.getInt("total_count_today", 0)

    // ── Cloud AI (optional, budget-capped) ─────────────────────────────────────

    private fun savedKeyAndProvider(): Pair<String, AiProvider> {
        val settings = context.getSharedPreferences("vanta_settings", Context.MODE_PRIVATE)
        val providerName = settings.getString("vanta_ai_analysis_provider", null)
            ?: settings.getString("vanta_ai_provider", null)
            ?: settings.getString("ai_provider", null)
            ?: if (settings.getString("api_key_gemini", "")?.isNotBlank() == true) AiProvider.GEMINI.name
            else if (settings.getString("api_key_deepseek", "")?.isNotBlank() == true) AiProvider.DEEPSEEK.name
            else if (settings.getString("api_key_openrouter", "")?.isNotBlank() == true) AiProvider.OPENROUTER.name
            else AiProvider.GEMINI.name
        val provider = runCatching { AiProvider.valueOf(providerName) }.getOrDefault(AiProvider.GEMINI)
        val key = settings.getString("api_key_${provider.name.lowercase()}", "") ?: ""
        return key to provider
    }

    private suspend fun generateWithAi(event: NotificationEvent): NotificationDecision? {
        val (key, provider) = savedKeyAndProvider()
        // Never load heavy on-device LLM in background worker tasks — background uses pre-generated buffer or smart template
        if (provider == AiProvider.ON_DEVICE_LITERT) return null
        if (key.isBlank()) return null

        // No internet → skip AI instantly and fall back to templates. Prevents a
        // 30-60s HTTP timeout from delaying a real notification.
        if (!hasNetwork()) return null

        // Personalization: the model sees the user's actual history so the message
        // can reference this athlete's trends (streaks, patterns, recovery arc).
        val history = runCatching { dao.getAllRecords() }.getOrDefault(emptyList())
        val prompt = CoachPromptSystem.notificationPrompt(
            CoachPromptSystem.NotificationPromptData(
                triggerLabel = reasonLabel(event.reason),
                recovery = event.recovery,
                energy = event.energy,
                strain = event.strain,
                steps = event.steps,
                workoutMinutes = event.workoutMinutes,
                weeklyAvgStrain = event.weeklyAvgStrain,
                streak = event.streak,
                weekWorkouts = event.weekWorkouts,
                profileName = runCatching {
                    com.vanta.app.data.db.VantaDatabase.getInstance(context).userProfileDao()
                        .getUserProfile()?.name ?: ""
                }.getOrDefault(""),
                heartRateAllowed = settings.heartRate
            ),
            history = history
        )
        val raw = gemma.generateWithProvider(
            prompt.system, prompt.user, key, provider,
            connectTimeoutMs = 5_000, readTimeoutMs = 10_000
        ) ?: return null
        return parseNotificationJson(raw, event)
    }

    private fun hasNetwork(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Throwable) {
            false
        }
    }

    private fun parseNotificationJson(raw: String, e: NotificationEvent): NotificationDecision? {
        return try {
            val start = raw.indexOf("{")
            val end = raw.lastIndexOf("}")
            if (start == -1 || end <= start) return null
            val obj = JSONObject(raw.substring(start, end + 1))
            val title = obj.optString("title").trim().take(60)
            val message = obj.optString("message").trim().replace(Regex("\\s+"), " ").take(140)
            val priority = obj.optString("priority").lowercase().let {
                if (it in listOf("low", "normal", "high")) it else e.priority
            }
            if (title.length < 3 || message.length < 8) return null
            NotificationDecision(notify = true, title = title, message = message, priority = priority, reason = e.reason)
        } catch (e: Exception) {
            null
        }
    }


    // ── Intelligent template fallback (premium, metric-grounded, rotated) ──────

    private fun generateTemplate(e: NotificationEvent): NotificationDecision {
        val stepsGoal = context.getSharedPreferences("vanta_settings", android.content.Context.MODE_PRIVATE)
            .getInt("steps_goal", 10000).coerceAtLeast(1000)
        val goalFmt = if (stepsGoal >= 1000) "%,d".format(stepsGoal) else "$stepsGoal"
        val title = when (e.reason) {
            "recovery" -> "Morning Recovery"
            "workout" -> "Workout Logged"
            "strain" -> "Strain Spike"
            "midday" -> "Midday Pacing"
            "afternoon" -> "Afternoon Check"
            "evening" -> "Evening Wind-down"
            "achievement" -> if (e.streak > 0) "Milestone" else "${stepsGoal / 1000}k Steps"
            "weekly" -> "Weekly Summary"
            else -> "Goal Reached"
        }
        val salt = when (e.reason) {
            "workout" -> 1; "strain" -> 2; "midday" -> 3; "afternoon" -> 4; "evening" -> 5; "achievement" -> 6; "weekly" -> 7; "goal" -> 8; else -> 0
        }
        val variants = templateVariants(e)
        val message = variants[(daySeed + salt).mod(variants.size)]
        return NotificationDecision(
            notify = true, title = title, message = message, priority = e.priority, reason = e.reason
        )
    }

    private fun templateVariants(e: NotificationEvent): List<String> {
        val stepsGoal = context.getSharedPreferences("vanta_settings", android.content.Context.MODE_PRIVATE)
            .getInt("steps_goal", 10000).coerceAtLeast(1000)
        val goalFmt = if (stepsGoal >= 1000) "%,d".format(stepsGoal) else "$stepsGoal"
        return when (e.reason) {
        "recovery" -> listOf(
            "🌅 ${e.recovery}% recovery to work with today — yesterday's load is absorbed and you're ready for more.",
            "💪 Recovery's at ${e.recovery}% — your green light to train with real intent.",
            "⚡ ${e.recovery}% recovered, ${e.energy}% in the tank. Keep today's session sharp and honest."
        )
        "midday" -> listOf(
            if (e.recovery >= 65) "⚡ Midday window: ${e.recovery}% recovery is in your favor. High-readiness day to push strain."
            else "⚖️ Midday check: strain is at ${"%.1f".format(e.strain)}. Keep your pacing steady and hydrated.",
            "👟 ${e.steps} steps banked by midday. Clean progress through the first half of the day.",
            "🔥 Optimal daylight window to get your primary session in before the afternoon."
        )
        "afternoon" -> listOf(
            "🔋 ${e.energy}% energy reserve holding with ${e.steps} steps so far. Steady afternoon momentum.",
            "🚶 Afternoon movement check — ${e.steps} steps on the board. Room to build if you're training later.",
            "⚡ Daily strain at ${"%.1f".format(e.strain)}/21. Solid physiological balance through the afternoon."
        )
        "evening" -> listOf(
            "🌙 Evening check: ${"%.1f".format(e.strain)} strain vs ${e.recovery}% morning recovery. Work is done — start winding down.",
            "🍵 Daily strain locked at ${"%.1f".format(e.strain)}. Shift focus toward nutrition, hydration, and sleep.",
            "🛋️ ${e.steps} total steps today. Rest and let the recovery cycle begin for tomorrow."
        )
        "workout" -> listOf(
            "🏋️ ${e.workoutMinutes} min of real work banked, strain at ${"%.1f".format(e.strain)}. Solid session.",
            "💪 Session logged — ${e.workoutMinutes} minutes of quality effort. That's the day's anchor.",
            "🔥 ${e.workoutMinutes} minutes done. Strong work, now protect the rest of the day."
        )
        "strain" -> listOf(
            "⚡ Strain jumped to ${"%.1f".format(e.strain)} — a meaningful push. Keep the rest of today light.",
            "🌡️ ${"%.1f".format(e.strain)} strain now, up from this morning. The hard work is done — recover.",
            "⚠️ That's a real strain spike at ${"%.1f".format(e.strain)}. Everything after this is maintenance."
        )
        "achievement" -> if (e.streak > 0) listOf(
            "🔥 ${e.streak} days of training in a row. The habit is real — keep it rolling.",
            "🏆 ${e.streak}-day training streak. That's consistency most people never find.",
            "⚡ ${e.streak} straight days with a session. Your body is adapting to the routine."
        ) else listOf(
            "👟 ${e.steps} steps — you cleared $goalFmt. A milestone day.",
            "🚶 $goalFmt steps before the day's over. That's a win on its own.",
            "🏅 Hit the $goalFmt mark (${e.steps} steps). Strong daily movement."
        )
        "weekly" -> listOf(
            "📊 This week: ${"%.1f".format(e.weeklyAvgStrain)} avg strain, ${e.weekWorkouts} workouts, ${e.weekSteps} steps. Consistent.",
            "📅 Weekly check-in — ${"%.1f".format(e.weeklyAvgStrain)} average strain across ${e.weekWorkouts} sessions. Solid week.",
            "✅ ${e.weekWorkouts} workouts logged this week. The week added up exactly how it should."
        )
        "goal" -> listOf(
            "🎯 Goal reached — ${e.weekWorkouts} workouts this week. That's the target, done.",
            "🏆 You hit your weekly goal: ${e.weekWorkouts} sessions. Delivered.",
            "💪 ${e.weekWorkouts} workouts this week — goal completed. Reward the consistency."
        )
        else -> emptyList()
    }
    }

    private fun reasonLabel(reason: String): String = when (reason) {
        "recovery" -> "Morning recovery update"
        "workout" -> "Workout completed"
        "strain" -> "Significant strain increase"
        "midday" -> "Midday training & pacing nudge"
        "afternoon" -> "Afternoon energy & movement check"
        "evening" -> "Evening recovery & wind-down prep"
        "achievement" -> "New achievement or milestone"
        "weekly" -> "Weekly summary"
        else -> "Goal reached"
    }
}

