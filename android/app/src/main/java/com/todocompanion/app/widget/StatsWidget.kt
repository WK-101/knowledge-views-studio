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
import java.time.LocalDate
import java.time.ZoneId

/** At-a-glance productivity: tasks completed today and how many are still due. Offline; reads local DB. */
class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val tasks = app.repository.wsTasksOnce()
                val done = tasks.count { it.completed && !it.trashed && (it.completedAt ?: 0) in start until end }
                val due = tasks.count { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! < end }
                val views = RemoteViews(context.packageName, R.layout.widget_stats)
                views.setTextViewText(R.id.st_done, done.toString())
                views.setTextViewText(R.id.st_due, if (due == 0) "All clear" else "$due still due")
                views.setOnClickPendingIntent(R.id.st_root, openApp(context))
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, StatsWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, StatsWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
