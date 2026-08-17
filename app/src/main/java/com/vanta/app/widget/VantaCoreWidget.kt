package com.vanta.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.vanta.app.R

/**
 * 2x2 Flagship VANTA Core Widget Provider.
 */
class VantaCoreWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_core)
            appWidgetManager.updateAppWidget(id, views)
        }
        VantaWidgetUpdater.updateAllWidgets(context)
    }

    override fun onEnabled(context: Context) {
        VantaWidgetUpdater.updateAllWidgets(context)
    }
}
