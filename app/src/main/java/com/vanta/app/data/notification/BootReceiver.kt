package com.vanta.app.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the daily check-in alarms after the device reboots or the clock/timezone
 * changes (AlarmManager alarms don't survive a reboot). The periodic WorkManager
 * sync also covers this, but an explicit receiver is cheaper and immediate.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                runCatching { CheckInScheduler.scheduleAll(context) }
                runCatching {
                    val serviceIntent = Intent(context, com.vanta.app.data.service.VantaBackgroundService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }
    }
}
