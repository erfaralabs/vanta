package com.vanta.app.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background WorkManager worker executed automatically at local midnight.
 * Archives completed day's telemetry to Room DB, resets daily counters, and updates baseline models.
 */
class MidnightRolloverWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val rolloverManager = DailyRolloverManager.getInstance(applicationContext)
            rolloverManager.performMidnightRollover()
            rolloverManager.scheduleMidnightWork()
            rolloverManager.schedulePeriodicTelemetrySync()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
