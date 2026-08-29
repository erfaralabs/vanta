package com.vanta.app.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vanta.app.R

/**
 * Posts notifications on a dedicated "AI Coach" channel. A stable id per
 * [NotificationDecision.reason] means repeated events replace the previous
 * notification instead of stacking.
 */
object NotificationPoster {

    const val CHANNEL_ID = "vanta_ai_coach"
    const val CHANNEL_ID_CHECKIN = "vanta_checkins"
    const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // High-importance "Alerts" channel — recovery, strain, workouts, milestones.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Coach Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Meaningful coach events: recovery, strain, workouts and milestones"
                    enableVibration(true)
                }
            )

            // Silent "Check-ins" channel — the scheduled morning/evening rituals and
            // intraday nudges stay visible but calm, like WHOOP / Bevel.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_CHECKIN,
                    "Daily Check-ins",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Quiet morning and evening rituals and gentle intraday nudges"
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
    }

    /** Returns true when the notification was actually posted. */
    fun post(context: Context, decision: NotificationDecision): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)

        val priority = when (decision.priority) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val intent = android.content.Intent(context, com.vanta.app.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            notificationId(decision.reason),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelIdFor(decision.reason))
            .setSmallIcon(R.drawable.ic_stat_vanta)
            .setContentTitle(decision.title)
            .setContentText(decision.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(decision.message))
            .setPriority(priority)
            .setCategory(categoryFor(decision.reason))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId(decision.reason), notification)
        return true
    }

    /** Quiet scheduled rituals (check-in channel) vs. loud coach alerts (alerts channel). */
    private fun channelIdFor(reason: String): String = when (reason) {
        "morning", "night", "midday", "afternoon", "evening" -> CHANNEL_ID_CHECKIN
        else -> CHANNEL_ID
    }

    private fun categoryFor(reason: String): String = when (reason) {
        "morning", "night", "midday", "afternoon", "evening" -> NotificationCompat.CATEGORY_STATUS
        else -> NotificationCompat.CATEGORY_RECOMMENDATION
    }

    /**
     * Friendly first-run welcome posted right after onboarding completes, so the
     * user immediately knows the coach is alive and notifications are wired up.
     */
    fun postWelcome(context: Context, name: String): Boolean {
        val greeting = if (name.isNotBlank()) {
            "Welcome to Vanta, ${name.replaceFirstChar { it.uppercase() }}."
        } else {
            "Welcome to Vanta."
        }
        return post(
            context,
            NotificationDecision(
                notify = true,
                title = "Your coach is ready",
                message = "$greeting I'll check in each morning and evening, and only nudge you when it matters — recovery, strain, and milestones.",
                priority = "high",
                reason = "welcome"
            )
        )
    }

    private fun notificationId(reason: String): Int = when (reason) {
        "workout" -> 1001
        "strain" -> 1002
        "achievement" -> 1003
        "weekly" -> 1004
        "goal" -> 1005
        "test" -> 1006
        "morning" -> 1007
        "night" -> 1008
        "midday" -> 1009
        "afternoon" -> 1010
        "evening" -> 1011
        "welcome" -> 1012
        else -> 1000 // recovery
    }

    /**
     * Sends an immediate sample notification so the user can verify delivery.
     * Explicitly bypasses the engine/budget — a test is a user-triggered action,
     * not a real coach event. Returns true when posted.
     */
    suspend fun postTest(context: Context): Boolean {
        val name = runCatching {
            com.vanta.app.data.db.VantaDatabase.getInstance(context)
                .userProfileDao().getUserProfile()?.name?.trim().orEmpty()
        }.getOrDefault("")
        val message = if (name.isNotBlank()) {
            "${name.replaceFirstChar { it.uppercase() }}, this is a test from the AI Coach. If you can see this, notifications are working. ✅"
        } else {
            "This is a test from the AI Coach. If you can see this, notifications are working. ✅"
        }
        return post(
            context,
            NotificationDecision(
                notify = true,
                title = "Vanta test notification",
                message = message,
                priority = "high",
                reason = "test"
            )
        )
    }
}
