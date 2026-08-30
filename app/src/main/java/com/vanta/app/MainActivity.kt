package com.vanta.app

import android.content.Intent
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

        // Notification permission is requested at a calm, contextual moment (right
        // after onboarding completes) instead of a cold-launch interrupt — see
        // VantaNavGraph for the setup + welcome notification handoff.

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

        // Daily check-in alarms are armed once onboarding completes (see VantaNavGraph),
        // so a user who abandons onboarding never gets a phantom "Good morning".
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
