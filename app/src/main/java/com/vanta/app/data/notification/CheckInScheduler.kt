package com.vanta.app.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Schedules the two daily template check-in notifications via AlarmManager:
 *
 *  - Morning check-in at 08:10 — a warm greeting built from today's real data.
 *  - Night check-in at 21:30 — a calm recap of the day.
 *
 * Both are deterministic templates (never AI), so they cost zero API budget and
 * always fire even with no internet. One-shot exact alarms are re-armed after
 * each fire (see [CheckInReceiver]) and after every reboot ([BootReceiver]).
 */
object CheckInScheduler {

    const val ACTION_MORNING = "com.vanta.app.action.MORNING_CHECKIN"
    const val ACTION_NIGHT = "com.vanta.app.action.NIGHT_CHECKIN"

    private const val TAG = "VantaCheckIn"

    private const val RC_MORNING = 2001
    private const val RC_NIGHT = 2002

    /** 08:10 — the user's requested welcome hour. */
    val MORNING_HOUR: Int = 8
    val MORNING_MINUTE: Int = 10

    /** 21:30 — calm evening recap. */
    val NIGHT_HOUR: Int = 21
    val NIGHT_MINUTE: Int = 30

    /** Re-syncs every alarm with the user's current settings (enabled → arm, disabled → cancel). */
    fun scheduleAll(context: Context) {
        val settings = NotificationSettings(context)
        Log.d(TAG, "scheduleAll: enabled=${settings.enabled} morning=${settings.morningCheckIn} night=${settings.nightCheckIn} canExact=${canExact(context)}")
        if (settings.enabled && settings.morningCheckIn) {
            schedule(context, ACTION_MORNING, RC_MORNING, MORNING_HOUR, MORNING_MINUTE)
        } else {
            cancel(context, ACTION_MORNING, RC_MORNING)
        }
        if (settings.enabled && settings.nightCheckIn) {
            schedule(context, ACTION_NIGHT, RC_NIGHT, NIGHT_HOUR, NIGHT_MINUTE)
        } else {
            cancel(context, ACTION_NIGHT, RC_NIGHT)
        }
    }

    private fun canExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    private fun schedule(context: Context, action: String, requestCode: Int, hour: Int, minute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = nextTrigger(hour, minute)
        val pi = pendingIntent(context, action, requestCode)

        // Exact alarms are best-effort: on Android 12+ they need the
        // SCHEDULE_EXACT_ALARM special access. When unavailable (or revoked),
        // fall back to an inexact alarm that still lands within the hour —
        // a warm check-in drifting by a few minutes beats crashing or none.
        val exactArmed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                false
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                true
            }
        } catch (_: SecurityException) {
            false
        }
        if (!exactArmed) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) }
        }
        Log.d(TAG, "scheduled action=$action at $trigger exact=$exactArmed")
    }

    private fun cancel(context: Context, action: String, requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, action, requestCode))
    }

    /** The next occurrence of hour:minute, today if still ahead, else tomorrow. */
    private fun nextTrigger(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CheckInReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
