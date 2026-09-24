package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.domain.calendar.Availability
import com.todocompanion.app.domain.calendar.CalendarEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * R106 — Next Up + Free Time. The next thing on your plate (event or timed task) and how much of the
 * day is still open. Reads the app's OWN local events and tasks (no system-calendar access, no network)
 * and computes free openings on-device with the Availability engine. Fully offline. Tap opens the calendar.
 */
class FreeTimeWidget : BaseWidgetProvider() {

    private data class NextItem(val title: String, val startMillis: Long, val isEvent: Boolean)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val today = LocalDate.now(zone)
                val events = runCatching { app.repository.wsEventsOnce() }.getOrDefault(emptyList())
                val tasks = runCatching { app.repository.wsTasksOnce() }.getOrDefault(emptyList())

                // Next timed event occurrence still ahead (recurrence-aware).
                val nextEvent = runCatching {
                    CalendarEngine.expand(events, now, now + 30L * 24 * 3600 * 1000, zone)
                        .filter { !it.event.allDay && it.endMillis >= now }
                        .minByOrNull { it.startMillis }
                }.getOrNull()
                // Next timed, active task (a midnight due-time is an all-day sentinel → skip).
                val nextTask = tasks.asSequence()
                    .filter { !it.completed && !it.trashed && !it.abandoned }
                    .filter { it.dueDate != null && it.dueDate!! >= now }
                    .filter { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalTime() != LocalTime.MIDNIGHT }
                    .minByOrNull { it.dueDate!! }

                val evItem = nextEvent?.let { NextItem(it.event.title, it.startMillis, true) }
                val tkItem = nextTask?.let { NextItem(it.title, it.dueDate!!, false) }
                val next = listOfNotNull(evItem, tkItem).minByOrNull { it.startMillis }

                // Free minutes left in the REST of today's working window: start the window at the
                // current hour (clamped into an 8–22 day) so the number reflects time still ahead, not
                // hours already gone. (A future refinement is honouring the user's stored availability
                // config; this keeps a sensible default while staying directionally correct.)
                val freeMin = runCatching {
                    val startH = LocalTime.now(zone).hour.coerceIn(8, 22)
                    val cfg = Availability.Config(setOf(1, 2, 3, 4, 5, 6, 7), startH, 22, 15, 0)
                    val taskBusy = Availability.taskBusyIntervals(tasks, zone)
                    Availability.forDays(events, listOf(today), cfg, zone, extraBusy = taskBusy).firstOrNull()?.freeMin ?: 0
                }.getOrDefault(0)

                val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone)

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_freetime)
                    WidgetStyle.applyListCard(views, R.id.ft_card, context, id)
                    views.setTextColor(R.id.ft_label, style.accentText)
                    views.setTextColor(R.id.ft_title, style.textPrimary)
                    views.setTextColor(R.id.ft_when, style.textSecondary)
                    views.setTextColor(R.id.ft_free, style.success)

                    if (next == null) {
                        views.setTextViewText(R.id.ft_title, "Nothing scheduled")
                        views.setTextViewText(R.id.ft_when, "The rest of the day is yours.")
                    } else {
                        val icon = if (next.isEvent) "📅 " else "✓ "
                        views.setTextViewText(R.id.ft_title, icon + next.title)
                        val mins = ((next.startMillis - now) / 60000L).toInt()
                        val rel = when {
                            mins <= 0 -> "now"
                            mins < 60 -> "in $mins min"
                            mins < 24 * 60 -> "in ${mins / 60}h ${mins % 60}m"
                            else -> "in ${mins / (24 * 60)}d"
                        }
                        views.setTextViewText(R.id.ft_when, fmt.format(Instant.ofEpochMilli(next.startMillis)) + " · " + rel)
                    }

                    views.setTextViewText(R.id.ft_free, if (freeMin >= 15) "${Availability.fmtMinutes(freeMin)} left today" else "")
                    views.setOnClickPendingIntent(R.id.ft_root, openCalendar(context))
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    private fun openCalendar(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_calendar")
        }
        return PendingIntent.getActivity(context, "open_calendar".hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, FreeTimeWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, FreeTimeWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
