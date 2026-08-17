package com.vanta.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vanta.app.data.notification.NotificationPoster
import com.vanta.app.data.service.VantaBackgroundService
import com.vanta.app.navigation.VantaNavGraph
import com.vanta.app.ui.theme.VantaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationPoster.ensureChannel(this)

        // Ask for notification permission once on Android 13+ so EVERY user gets the
        // active-app entry + AI coach notifications without digging into Settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(NotificationPoster.NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(NotificationPoster.NOTIFICATION_PERMISSION), 1001)
        }

        // Foreground service: keeps the telemetry sync + AI notifications alive in
        // the background and makes Vanta appear under "Active apps" in the shade.
        runCatching {
            val intent = Intent(this, VantaBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // Arm the daily 08:10 morning / 21:30 night template check-in alarms.
        com.vanta.app.data.notification.CheckInScheduler.scheduleAll(this)
        com.vanta.app.widget.VantaWidgetUpdater.updateAllWidgets(this)
        com.vanta.app.ui.dev.DevResolutionManager.init(this)
        setContent {
            VantaTheme {
                com.vanta.app.ui.dev.DevResolutionContainer {
                    VantaNavGraph()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.vanta.app.widget.VantaWidgetUpdater.updateAllWidgets(this)
    }

    override fun onStop() {
        super.onStop()
        // Completely release and unload on-device LLM model & GPU memory when app is minimized or backgrounded
        com.vanta.app.data.ai.OnDeviceLlmManager.getInstance(applicationContext).unloadEngine()
    }
}
