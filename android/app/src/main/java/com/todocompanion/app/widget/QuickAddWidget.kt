package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R

/** A tiny home-screen button that jumps straight into quick-add. Offline; launches the app only. */
class QuickAddWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_QUICK_ADD)
        }
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.widget_quickadd).apply {
            setOnClickPendingIntent(R.id.widget_root, pi)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
