package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * R104 — the Day widget: one day's events and tasks together (the unified agenda Any.do/Business
 * Calendar surface), with ‹ › day navigation, in-place task check-off, and a split New Task /
 * New Event footer. Offline — reads the local Room DB + dedicated calendar only.
 */
class DayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPrefs.clear(context, it) } }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_day)
        val svc = Intent(context, DayWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.day_list, svc)
        views.setEmptyView(R.id.day_list, R.id.day_empty)
        views.setPendingIntentTemplate(R.id.day_list, TaskWidgetReceiver.template(context, 4203))

        // Date label for the selected day.
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone).plusDays(WidgetPrefs.dayOffset(context, id).toLong())
        views.setTextViewText(R.id.day_title, dayLabel(date, LocalDate.now(zone)))

        // Day navigation + the day label taps to the calendar.
        views.setOnClickPendingIntent(R.id.day_prev, navIntent(context, id, -1))
        views.setOnClickPendingIntent(R.id.day_next, navIntent(context, id, +1))
        views.setOnClickPendingIntent(R.id.day_title, activity(context, id * 10 + 1, "open_calendar"))
        views.setOnClickPendingIntent(R.id.day_add_task, activity(context, id * 10 + 2, MainActivity.ACTION_QUICK_ADD))
        views.setOnClickPendingIntent(R.id.day_add_event, activity(context, id * 10 + 3, "open_calendar"))

        WidgetStyle.applyListCard(views, R.id.day_card, context, id)
        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.day_list)
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }

    private fun activity(context: Context, code: Int, action: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun navIntent(context: Context, id: Int, delta: Int): PendingIntent {
        val i = Intent(context, DayNavReceiver::class.java).setAction(DayNavReceiver.ACTION_NAV).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(DayNavReceiver.EXTRA_DELTA, delta)
        }
        return PendingIntent.getBroadcast(context, id * 10 + 5 + delta, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, DayWidget::class.java))
            if (ids.isEmpty()) return
            m.notifyAppWidgetViewDataChanged(ids, R.id.day_list)
            context.sendBroadcast(Intent(context, DayWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }

        fun updateOne(context: Context, id: Int) {
            val m = AppWidgetManager.getInstance(context) ?: return
            DayWidget().render(context, m, id)
        }
    }
}

/** ‹ / › day navigation: shift the widget's day offset and re-render. */
class DayNavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NAV) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val delta = intent.getIntExtra(EXTRA_DELTA, 0)
        WidgetPrefs.setDayOffset(context, id, WidgetPrefs.dayOffset(context, id) + delta)
        DayWidget.updateOne(context, id)
    }

    companion object {
        const val ACTION_NAV = "com.todocompanion.app.action.DAY_NAV"
        const val EXTRA_DELTA = "delta"
    }
}

class DayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return DayFactory(applicationContext, id)
    }
}

private class DayFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val title: String, val sub: String, val overdue: Boolean,
                           val isEvent: Boolean, val sortKey: Long)
    private var rows: List<Row> = emptyList()
    private var style: WidgetStyle = WidgetStyle.resolve(context)

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        val zone = ZoneId.systemDefault()
        style = WidgetStyle.resolve(context, widgetId)
        val today = LocalDate.now(zone)
        val date = today.plusDays(WidgetPrefs.dayOffset(context, widgetId).toLong())
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val isToday = date == today

        val tasks = runBlocking { app.repository.wsTasksOnce() }
        val taskRows = tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned }
            .filter { t ->
                val due = t.dueDate ?: return@filter false
                // The selected day, plus overdue rolled onto today.
                (due in dayStart until dayEnd) || (isToday && due < dayStart)
            }
            .map { t ->
                val due = t.dueDate!!
                val overdue = isToday && due < dayStart
                val dt = Instant.ofEpochMilli(due).atZone(zone)
                val hasTime = !(dt.hour == 0 && dt.minute == 0)
                val sub = when {
                    overdue -> "Overdue"
                    hasTime -> "%02d:%02d".format(dt.hour, dt.minute)
                    else -> "Task"
                }
                Row(t.id, t.title.ifBlank { "Untitled" }, sub, overdue, isEvent = false, sortKey = if (overdue) 0 else due)
            }.toList()

        val eventRows = runBlocking {
            com.todocompanion.app.domain.calendar.CalendarEngine.expand(app.repository.wsEventsOnce(), dayStart, dayEnd, zone)
                .filter { it.startMillis < dayEnd && it.endMillis > dayStart }
                .map { o ->
                    val st = Instant.ofEpochMilli(o.startMillis).atZone(zone)
                    val sub = if (o.event.allDay) "All day" else "%02d:%02d".format(st.hour, st.minute)
                    Row("evt:${o.event.id}", o.event.title.ifBlank { "Event" }, sub, overdue = false, isEvent = true, sortKey = o.startMillis)
                }
        }
        rows = (taskRows + eventRows).sortedBy { it.sortKey }.take(50)
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_agenda_item).apply {
            setTextViewText(R.id.item_title, r.title)
            setTextViewText(R.id.item_sub, r.sub)
            val vpad = (((if (style.compact) 4 else 8)) * context.resources.displayMetrics.density).toInt()
            setViewPadding(R.id.item_root, 0, vpad, 0, vpad)
            setTextViewTextSize(R.id.item_title, android.util.TypedValue.COMPLEX_UNIT_SP, style.sp(14f))
            setTextViewTextSize(R.id.item_sub, android.util.TypedValue.COMPLEX_UNIT_SP, style.sp(12f))
            setTextViewTextSize(R.id.item_check, android.util.TypedValue.COMPLEX_UNIT_SP, style.sp(17f))
            setTextColor(R.id.item_title, style.textPrimary)
            setTextColor(R.id.item_sub, when { r.isEvent -> style.info; r.overdue -> style.danger; else -> style.textSecondary })
            if (r.isEvent) {
                setTextViewText(R.id.item_check, "•")
                setTextColor(R.id.item_check, style.info)
                setOnClickFillInIntent(R.id.item_check, TaskWidgetReceiver.openFill("open_calendar"))
                setOnClickFillInIntent(R.id.item_root, TaskWidgetReceiver.openFill("open_calendar"))
            } else {
                setTextViewText(R.id.item_check, "○")
                setTextColor(R.id.item_check, if (r.overdue) style.danger else style.accentText)
                setOnClickFillInIntent(R.id.item_check, TaskWidgetReceiver.completeFill(r.id))
                setOnClickFillInIntent(R.id.item_root, TaskWidgetReceiver.openFill("open_task:${r.id}"))
            }
        }
    }
}
