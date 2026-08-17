package com.vanta.app.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vanta.app.MainActivity
import com.vanta.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps Vanta alive in the background so the telemetry sync and AI notifications
 * fire reliably. Runs a quiet, ongoing foreground notification — that's what makes
 * Vanta appear under "Active apps" in the notification shade.
 *
 * While alive, it also runs the same [TelemetrySync] as the WorkManager worker on
 * a fast cadence. WorkManager's periodic job is deferred by battery optimizations
 * and never runs more than once per 15 min; this loop guarantees fresh data +
 * notifications even when the app is closed.
 */
class VantaBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSyncLoop() {
        scope.launch {
            // First sync shortly after start — fast feedback without racing the
            // app-open sync (the 45s Health Connect cache dedupes anyway).
            delay(SYNC_INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    // Heavy work on IO; loop ticks on Default.
                    withContext(Dispatchers.IO) { TelemetrySync.runSync(this@VantaBackgroundService) }
                } catch (t: Throwable) {
                    // Never kill the loop on a transient failure.
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vanta)
            .setContentTitle("Vanta")
            .setContentText("Training sync active — recovery, strain and energy stay up to date.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            // Never re-alert on update, hide the timestamp, keep it device-local —
            // the entry is a quiet "active app" chip, not something that looks new
            // every time the app opens.
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Background training sync",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Keeps Vanta's telemetry sync running in the background"
                        setSound(null, null)
                    }
                )
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "vanta_background"
        const val NOTIFICATION_ID = 9001

        /** Fast cadence: first sync ~45s after start, then every 10 minutes. */
        const val SYNC_INITIAL_DELAY_MS = 45_000L
        const val SYNC_INTERVAL_MS = 10 * 60 * 1000L
    }
}
