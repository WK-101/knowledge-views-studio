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

/** Eisenhower matrix at a glance: open-task counts per quadrant. Tap opens the Matrix. Offline. */
class MatrixWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = app.repository.settingsSnapshot()
                val impT = s.matrixImportanceThreshold
                val urgT = s.matrixUrgencyThreshold
                val openTasks = app.repository.allTasksOnce().filter { !it.completed && !it.trashed && !it.abandoned }
                // q0 = urgent+important, q1 = important, q2 = urgent, q3 = neither.
                val counts = IntArray(4)
                openTasks.forEach { t ->
                    val imp = t.importance >= impT; val urg = t.urgency >= urgT
                    counts[if (imp && urg) 0 else if (imp) 1 else if (urg) 2 else 3]++
                }
                val views = RemoteViews(context.packageName, R.layout.widget_matrix)
                views.setTextViewText(R.id.mx_q1, counts[0].toString())
                views.setTextViewText(R.id.mx_q2, counts[1].toString())
                views.setTextViewText(R.id.mx_q3, counts[2].toString())
                views.setTextViewText(R.id.mx_q4, counts[3].toString())
                views.setOnClickPendingIntent(R.id.mx_root, openApp(context))
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_matrix")
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, MatrixWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, MatrixWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
