package com.vanta.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.vanta.app.R

/**
 * 1x1 Quick Score Widget Provider.
 * Supports interactive tap-to-switch metric toggling between STRAIN, RECOVERY, and ENERGY.
 */
class VantaQuickScoreWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_CYCLE_1X1_METRIC = "com.vanta.app.ACTION_CYCLE_1X1_METRIC"
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CYCLE_1X1_METRIC) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val targetIds = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                intArrayOf(appWidgetId)
            } else {
                appWidgetManager.getAppWidgetIds(ComponentName(context, VantaQuickScoreWidget::class.java))
            }

            val prefs = context.getSharedPreferences("vanta_widget_prefs", Context.MODE_PRIVATE)
            for (id in targetIds) {
                val currentMode = prefs.getString("mode_$id", "STRAIN") ?: "STRAIN"
                val nextMode = when (currentMode) {
                    "STRAIN" -> "RECOVERY"
                    "RECOVERY" -> "ENERGY"
                    else -> "STRAIN"
                }
                prefs.edit().putString("mode_$id", nextMode).apply()
            }
            VantaWidgetUpdater.updateAllWidgets(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_score)
            appWidgetManager.updateAppWidget(id, views)
        }
        VantaWidgetUpdater.updateAllWidgets(context)
    }

    override fun onEnabled(context: Context) {
        VantaWidgetUpdater.updateAllWidgets(context)
    }
}
