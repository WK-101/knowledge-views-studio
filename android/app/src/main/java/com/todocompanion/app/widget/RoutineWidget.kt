package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.domain.Routine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * R106 — Routine Runner (launcher). Today's due routines, one tap to start the in-app guided runner.
 * The stepper itself (timers, linked-habit ticking, resumable progress) stays in the app where that
 * state lives; the widget is the glanceable "what's queued, start it" surface. Fully offline.
 *
 * "Due today" = a scheduled, runnable routine not yet finished today; if none are scheduled, we fall
 * back to every runnable routine so a tap is always available.
 */
class RoutineWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val routines = runCatching { app.repository.routinesOnce() }.getOrDefault(emptyList())
                val runs = runCatching { app.repository.routineRunsOnce() }.getOrDefault(emptyList())
                val ranToday = runs.filter { it.finished && it.epochDay == today }.map { it.routineId }.toSet()

                val runnable = routines.filter { it.isRunnable }
                val dueToday = runnable.filter {
                    it.scheduledOn(today) && it.id !in ranToday && (it.whenReminderMin != null || it.days.isNotEmpty())
                }
                // Show what's due; if nothing is scheduled, offer every routine so a tap is always there.
                val show = (if (dueToday.isNotEmpty()) dueToday else runnable).take(4)

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_routine)
                    WidgetStyle.applyListCard(views, R.id.rt_card, context, id)
                    views.setTextColor(R.id.rt_title, style.accentText)

                    val rowIds = intArrayOf(R.id.rt_r0, R.id.rt_r1, R.id.rt_r2, R.id.rt_r3)
                    for (i in rowIds.indices) {
                        val r = show.getOrNull(i)
                        if (r == null) {
                            views.setViewVisibility(rowIds[i], View.GONE)
                        } else {
                            views.setViewVisibility(rowIds[i], View.VISIBLE)
                            views.setTextViewText(rowIds[i], "${r.emoji.ifBlank { "🔗" }}  ${r.name}   ·   ${planLabel(r)}")
                            views.setTextColor(rowIds[i], style.textPrimary)
                            views.setOnClickPendingIntent(rowIds[i], runRoutine(context, r.name))
                        }
                    }
                    views.setViewVisibility(R.id.rt_empty, if (show.isEmpty()) View.VISIBLE else View.GONE)
                    if (show.isEmpty()) {
                        views.setTextViewText(R.id.rt_empty, if (routines.isEmpty()) "No routines yet — build one in the app." else "All routines done for today 🎉")
                        views.setTextColor(R.id.rt_empty, style.textSecondary)
                    }
                    views.setOnClickPendingIntent(R.id.rt_title, openRoutines(context))
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    private fun planLabel(r: Routine): String {
        val min = r.plannedSec / 60
        return if (min > 0) "$min min" else "${r.steps.size} steps"
    }

    private fun runRoutine(context: Context, name: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_RUN_ROUTINE + name)
        }
        return PendingIntent.getActivity(context, ("run:$name").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openRoutines(context: Context): PendingIntent {
        // No dedicated routines-list route; the Focus tab hosts routines, so land there.
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_focus")
        }
        return PendingIntent.getActivity(context, "open_focus_rt".hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, RoutineWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, RoutineWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
