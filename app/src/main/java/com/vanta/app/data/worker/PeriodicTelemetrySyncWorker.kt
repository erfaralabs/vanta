package com.vanta.app.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic background sync (every 15 min, WorkManager minimum period).
 *
 * Pulls the latest Health Connect telemetry even when the app is closed,
 * re-computes Strain / Recovery / Energy with the deterministic engine, and keeps
 * today's Room DB record + physiology prefs (max strain, min energy, locked
 * recovery) current — so the dashboard is already live when the app reopens.
 *
 * Deliberately does NOT run the full cloud AI overview (no network/cost impact
 * in the background); the AI overview regenerates on next open via the existing
 * cache-invalidation. The AI Notification Engine runs here instead — it only
 * calls the cloud for capped, meaningful events and falls back to templates.
 */
class PeriodicTelemetrySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            com.vanta.app.data.service.TelemetrySync.runSync(applicationContext)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
