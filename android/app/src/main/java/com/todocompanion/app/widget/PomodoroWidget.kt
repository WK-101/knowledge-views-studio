package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R

/** One-tap launcher into the Focus timer. Offline. */
class PomodoroWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_focus")
        }
        val pi = PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_pomodoro)
            views.setOnClickPendingIntent(R.id.pomo_root, pi)
            manager.updateAppWidget(id, views)
        }
    }
}
