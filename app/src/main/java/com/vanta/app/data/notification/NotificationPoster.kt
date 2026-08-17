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
    const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Coach",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Personalized coach insights from Vanta"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vanta)
            .setContentTitle(decision.title)
            .setContentText(decision.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(decision.message))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_PROMO)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId(decision.reason), notification)
        return true
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
