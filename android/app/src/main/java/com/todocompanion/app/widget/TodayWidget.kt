package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget showing today's task load with a one-tap quick-add.
 * Fully offline: reads the local Room database, never touches the network.
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val tasks = app.repository.allTasksOnce()
                val active = tasks.filter { !it.completed && !it.abandoned }
                val dueToday = active.count { it.dueDate != null && it.dueDate in startOfDay until startOfTomorrow }
                val overdue = active.count { it.dueDate != null && it.dueDate < startOfDay }

                val views = RemoteViews(context.packageName, R.layout.widget_today).apply {
                    setTextViewText(R.id.widget_date, today.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())))
                    setTextViewText(R.id.widget_count, dueToday.toString())
                    setTextViewText(R.id.widget_subtitle, if (dueToday == 1) "task due today" else "tasks due today")
                    if (overdue > 0) {
                        setViewVisibility(R.id.widget_overdue, android.view.View.VISIBLE)
                        setTextViewText(R.id.widget_overdue, "$overdue overdue")
                    } else {
                        setViewVisibility(R.id.widget_overdue, android.view.View.GONE)
                    }
                    setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, null))
                    setOnClickPendingIntent(R.id.widget_add, openAppIntent(context, MainActivity.ACTION_QUICK_ADD))
                }
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun openAppIntent(context: Context, action: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
        }
        // A distinct request code per action keeps the two PendingIntents from colliding.
        val requestCode = if (action == null) 0 else 1
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        /** Re-render every placed instance. Safe to call from any thread. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, TodayWidget::class.java).apply {
                this.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
