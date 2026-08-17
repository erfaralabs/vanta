package com.vanta.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.vanta.app.MainActivity
import com.vanta.app.R
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import com.vanta.app.data.baseline.AdaptiveBaselineManager
import com.vanta.app.data.db.VantaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Real-Time Zero-Lag Widget Update Manager for Vanta.
 * Updates all home screen widget instances instantly whenever telemetry or daily scores change.
 */
object VantaWidgetUpdater {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun updateAllWidgets(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                // 1. Fetch current physiological data
                val todayStr = LocalDate.now().toString()
                val db = VantaDatabase.getInstance(appContext)
                val todayRecord = db.dailyMetricsDao().getRecordForDate(todayStr)

                val engine = VantaDeterministicPhysiologyEngine(appContext)
                val hcManager = HealthConnectManager(appContext)
                val baselineManager = AdaptiveBaselineManager(appContext)
                val baseline = baselineManager.getCurrentBaseline()

                val telemetry = hcManager.readTodayTelemetry()
                val phys = engine.calculatePhysiology(telemetry, baseline)

                val strain = todayRecord?.strain ?: phys.strain
                val recovery = todayRecord?.recovery ?: phys.recovery
                val energy = todayRecord?.energy ?: phys.energy
                val steps = todayRecord?.steps ?: telemetry.steps

                val strainText = String.format(Locale.US, "%.1f", strain)
                val recoveryText = "$recovery%"
                val energyText = "$energy"
                val stepsText = "${NumberFormat.getNumberInstance(Locale.US).format(steps)} STEPS"

                val statusLine = when {
                    recovery >= 90 -> "EXCELLENT RECOVERY"
                    recovery >= 80 -> "HIGH RECOVERY"
                    recovery >= 70 -> "GREAT RECOVERY"
                    recovery >= 60 -> "GOOD RECOVERY"
                    recovery >= 50 -> "MODERATE RECOVERY"
                    recovery >= 40 -> "POOR RECOVERY"
                    else -> "VERY POOR RECOVERY"
                }

                val appWidgetManager = AppWidgetManager.getInstance(appContext)

                // Shared Launch Intent for multi-column widgets
                val mainIntent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val mainPendingIntent = PendingIntent.getActivity(
                    appContext, 0, mainIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // ── 1. Update 1x1 Quick Score Widgets (Tap-to-Switch Metric) ─────
                val quickScoreIds = appWidgetManager.getAppWidgetIds(
                    ComponentName(appContext, VantaQuickScoreWidget::class.java)
                )
                val widgetPrefs = appContext.getSharedPreferences("vanta_widget_prefs", Context.MODE_PRIVATE)

                if (quickScoreIds.isNotEmpty()) {
                    for (id in quickScoreIds) {
                        val views = RemoteViews(appContext.packageName, R.layout.widget_quick_score)
                        val mode = widgetPrefs.getString("mode_$id", "STRAIN") ?: "STRAIN"

                        val (valText, labelText, frac, color) = when (mode) {
                            "RECOVERY" -> Quadruple(recoveryText, "RECOVERY", (recovery.toFloat() / 100f).coerceIn(0f, 1f), VantaWidgetRenderer.COLOR_GREEN)
                            "ENERGY" -> Quadruple(energyText, "ENERGY", (energy.toFloat() / 100f).coerceIn(0f, 1f), VantaWidgetRenderer.COLOR_BLUE)
                            else -> Quadruple(strainText, "STRAIN", (strain.toFloat() / 21f).coerceIn(0f, 1f), VantaWidgetRenderer.COLOR_CYAN)
                        }

                        val bitmap = VantaWidgetRenderer.renderQuickScoreBitmap(
                            valueText = valText,
                            labelText = labelText,
                            progressFraction = frac,
                            accentColor = color
                        )
                        views.setImageViewBitmap(R.id.widget_quick_score_image, bitmap)

                        // Interactive Tap-to-Switch Intent
                        val cycleIntent = Intent(appContext, VantaQuickScoreWidget::class.java).apply {
                            action = VantaQuickScoreWidget.ACTION_CYCLE_1X1_METRIC
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        }
                        val cyclePendingIntent = PendingIntent.getBroadcast(
                            appContext, id, cycleIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.widget_quick_score_container, cyclePendingIntent)
                        views.setOnClickPendingIntent(R.id.widget_quick_score_image, cyclePendingIntent)

                        appWidgetManager.updateAppWidget(id, views)
                    }
                }

                // ── 2. Update 2x1 Daily Summary Widgets ───────────────────────
                val summaryIds = appWidgetManager.getAppWidgetIds(
                    ComponentName(appContext, VantaDailySummaryWidget::class.java)
                )
                if (summaryIds.isNotEmpty()) {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_daily_summary)
                    views.setTextViewText(R.id.widget_strain_value, strainText)
                    views.setTextViewText(R.id.widget_recovery_value, recoveryText)
                    views.setTextViewText(R.id.widget_energy_value, energyText)
                    views.setOnClickPendingIntent(R.id.widget_daily_summary_container, mainPendingIntent)

                    for (id in summaryIds) {
                        appWidgetManager.updateAppWidget(id, views)
                    }
                }

                // ── 3. Update 2x2 Flagship Core Widgets ───────────────────────
                val coreIds = appWidgetManager.getAppWidgetIds(
                    ComponentName(appContext, VantaCoreWidget::class.java)
                )
                if (coreIds.isNotEmpty()) {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_core)
                    val ringsBitmap = VantaWidgetRenderer.renderTripleRingsBitmap(
                        strain = strain,
                        recovery = recovery,
                        energy = energy
                    )
                    views.setImageViewBitmap(R.id.widget_core_rings_image, ringsBitmap)
                    views.setTextViewText(R.id.widget_core_steps_value, stepsText)
                    views.setTextViewText(R.id.widget_core_status_line, statusLine)
                    views.setOnClickPendingIntent(R.id.widget_core_container, mainPendingIntent)

                    for (id in coreIds) {
                        appWidgetManager.updateAppWidget(id, views)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
