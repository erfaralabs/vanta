package com.vanta.app.data.service

import android.content.Context
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.HealthConnectTelemetry
import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import com.vanta.app.data.baseline.AdaptiveBaselineManager
import com.vanta.app.data.db.DailyMetricRecord
import com.vanta.app.data.db.VantaDatabase
import com.vanta.app.data.notification.AiNotificationEngine
import com.vanta.app.data.notification.NotificationPoster
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Single source of truth for a background telemetry sync — used by BOTH the
 * WorkManager worker and the foreground service loop, so notifications and the
 * dashboard stay live even when the app is closed and WorkManager is deferred
 * by battery optimizations.
 *
 * Fast: parallel Health Connect reads, deterministic physiology, DB persist, then
 * a notification pass (deduped by prefs). No cloud AI calls here.
 */
object TelemetrySync {

    suspend fun runSync(context: Context) {
        // Guarantee midnight rollover and day archiving are performed before fetching today's telemetry
        runCatching {
            com.vanta.app.data.worker.DailyRolloverManager.getInstance(context).checkAndPerformRolloverIfNeeded()
        }

        val manager = HealthConnectManager(context)
        val engine = VantaDeterministicPhysiologyEngine(context)
        val baselineManager = AdaptiveBaselineManager(context)

        // Only skip when Health Connect itself is unavailable. Missing individual
        // permissions (e.g. sleep, which users may never grant) must NOT kill the
        // whole sync — every fetch below already handles a denied permission by
        // returning its neutral empty value (0 steps / 0 HR, never fabricated).
        if (!manager.isAvailable) return

        // Parallel wave of all telemetry reads in a single coroutineScope
        val db = VantaDatabase.getInstance(context)
        val userProfile = db.userProfileDao().getUserProfile()
        val snapshot = manager.fetchTodayTelemetrySnapshot(userProfile)
        val telemetry = snapshot.telemetry

        val baseline = baselineManager.getCurrentBaseline()
        val detResult = engine.calculatePhysiology(telemetry, baseline)

        val todayDate = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()

        // Guard: only write to Room when Health Connect returned real data.
        // After midnight rollover the physiology prefs are cleared, so if HC
        // hasn't synced yet this sync would return steps=0/HR=0 → strain=0.5
        // and REPLACE would overwrite the real accumulated strain from yesterday/
        // earlier today with 0.5. Skip the write entirely; the next sync cycle
        // (or the foreground runAnalysis) will land with real data and write then.
        val hasRealData = telemetry.steps > 100 ||
            telemetry.avgBpm > 0 ||
            telemetry.peakBpm > 0 ||
            telemetry.exerciseMinutes > 0 ||
            telemetry.calories > 50 ||
            telemetry.distanceKm > 0.05
        if (hasRealData) {
            db.dailyMetricsDao().insertOrUpdate(
                DailyMetricRecord.fromPhysiology(
                    date = todayDate,
                    timestamp = System.currentTimeMillis(),
                    restingBpm = detResult.rhrToday,
                    avgBpm = telemetry.avgBpm,
                    maxBpm = telemetry.peakBpm,
                    steps = telemetry.steps,
                    calories = telemetry.calories,
                    distanceKm = telemetry.distanceKm,
                    workoutDurationMin = telemetry.exerciseMinutes,
                    phys = detResult
                )
            )
        }

        // The UI shares the same in-process static cache; drop it so the next
        // foreground read re-queries Health Connect for the freshest numbers.
        HealthConnectManager.clearCache()

        // AI notification engine: fires only on meaningful events, respects the
        // daily budget and settings, and falls back to premium templates.
        val decision = AiNotificationEngine(context)
            .evaluate(telemetry = telemetry, det = detResult, baseline = baseline)
        if (decision != null) {
            NotificationPoster.post(context, decision)
        }

        // One-time Adaptive Core activation notification — fires exactly once
        // the first time the user accumulates >= 14 days of data. Never repeats.
        val corePrefs = context.getSharedPreferences("vanta_core", android.content.Context.MODE_PRIVATE)
        val coreNotified = corePrefs.getBoolean("core_activation_notified", false)
        if (!coreNotified && baseline.adaptiveCore != null) {
            val days = baseline.adaptiveCore.totalDaysTracked
            val modeLabel = if (baseline.adaptiveCore.isTrainingMode) "Training Mode" else "Daily Mover Mode"
            NotificationPoster.post(
                context,
                com.vanta.app.data.notification.NotificationDecision(
                    notify = true,
                    title = "⚡ Adaptive Core is now active",
                    message = "You've built $days days of data. Core is now calibrated to your patterns in $modeLabel — every score in Vanta is now personalised to you.",
                    reason = "core_activation",
                    priority = "high"
                )
            )
            corePrefs.edit().putBoolean("core_activation_notified", true).apply()
        }

        // Real-Time zero-lag widget updates
        com.vanta.app.widget.VantaWidgetUpdater.updateAllWidgets(context)

        // Automatic Supabase Cloud Backup (if configured and interval reached)
        com.vanta.app.data.backup.SupabaseBackupManager.checkAndPerformAutoBackup(context)
    }
}
