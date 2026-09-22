package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * R107 — the Day widget: a calendar-style day agenda. A week date-selection strip (tap a day to
 * switch, echoing the in-app day view), compact iconized New-task / New-event buttons, priority-
 * coloured task rows with in-place check-off, and one day's events + tasks together. New-task opens
 * the translucent quick-capture popup (not the whole app); New-event opens the calendar's event
 * editor. Offline — reads the local Room DB + dedicated calendar only.
 */
class DayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { ids.forEach { render(context, manager, it) } } finally { pending.finish() }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPrefs.clear(context, it) } }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { render(context, manager, id) } finally { pending.finish() }
        }
    }

    /** Renders one widget. Safe to call from an IO coroutine — it does blocking DB reads. */
    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val app = context.applicationContext as App
        val style = WidgetStyle.resolve(context, id)
        val views = RemoteViews(context.packageName, R.layout.widget_day)
        val svc = Intent(context, DayWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.day_list, svc)
        views.setEmptyView(R.id.day_list, R.id.day_empty)
        views.setPendingIntentTemplate(R.id.day_list, TaskWidgetReceiver.template(context, 4203))

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val offset = WidgetPrefs.dayOffset(context, id)
        val selected = today.plusDays(offset.toLong())

        val s = runBlocking { app.repository.settingsSnapshot() }
        val weekStart = if (s.weekStart in 1..7) DayOfWeek.of(s.weekStart) else WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val fromStart = ((selected.dayOfWeek.value - weekStart.value) + 7) % 7
        val weekStartDate = selected.minusDays(fromStart.toLong())
        val weekDays = (0..6).map { weekStartDate.plusDays(it.toLong()) }

        // Per-day presence dots: any open task due, or any event, that day.
        val tasks = runBlocking { app.repository.wsTasksOnce() }
        val weekStartMs = weekStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEndMs = weekStartDate.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val eventDays = runCatching {
            runBlocking { com.todocompanion.app.domain.calendar.CalendarEngine.expand(app.repository.wsEventsOnce(), weekStartMs, weekEndMs, zone) }
                .map { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }.toSet()
        }.getOrDefault(emptySet())
        val taskDays = tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null }
            .map { Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() }.toSet()
        val hasItems = weekDays.map { it in eventDays || it in taskDays }

        // The week strip: one bitmap, seven equal invisible tap zones over it.
        val opts = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val wDp = (opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250).coerceIn(120, 640)
        val stripW = WidgetBitmaps.dp(context, (wDp - 24).toFloat()).toInt()
        val stripH = WidgetBitmaps.dp(context, 50f).toInt()
        val letters = weekDays.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
        val nums = weekDays.map { it.dayOfMonth }
        views.setImageViewBitmap(R.id.day_strip, WidgetBitmaps.weekStrip(
            stripW, stripH, letters, nums,
            selectedIdx = fromStart, todayIdx = weekDays.indexOf(today), hasItems = hasItems,
            accent = style.accent, onAccent = style.onAccent, textPrimary = style.textPrimary,
            textSecondary = style.textSecondary, dotColor = style.accent))
        val cellIds = intArrayOf(R.id.day_c0, R.id.day_c1, R.id.day_c2, R.id.day_c3, R.id.day_c4, R.id.day_c5, R.id.day_c6)
        for (i in 0 until 7) {
            val absOffset = ChronoUnit.DAYS.between(today, weekDays[i]).toInt()
            views.setOnClickPendingIntent(cellIds[i], setDayIntent(context, id, absOffset))
        }

        // Title (selected day's label) + week navigation (±7).
        views.setTextViewText(R.id.day_title, dayLabel(selected, today))
        views.setTextColor(R.id.day_title, style.textPrimary)
        views.setTextColor(R.id.day_prev, style.textSecondary)
        views.setTextColor(R.id.day_next, style.textSecondary)
        views.setOnClickPendingIntent(R.id.day_prev, navIntent(context, id, -7))
        views.setOnClickPendingIntent(R.id.day_next, navIntent(context, id, +7))
        views.setOnClickPendingIntent(R.id.day_title, activity(context, id * 10 + 1, "open_calendar"))

        // Compact iconized add buttons, one accent family for a polished pair: a filled-accent primary
        // "+" (new task) and a tonal-accent secondary (new event) — no clashing second hue.
        val iconPx = WidgetBitmaps.dp(context, 28f).toInt()
        val tonal = WidgetBitmaps.blend(style.accent, style.surface, 0.82f)
        views.setImageViewBitmap(R.id.day_add_task, WidgetBitmaps.roundIcon(context, iconPx, style.accent, style.onAccent, "plus"))
        views.setImageViewBitmap(R.id.day_add_event, WidgetBitmaps.roundIcon(context, iconPx, tonal, style.accent, "calendar"))
        views.setOnClickPendingIntent(R.id.day_add_task, quickCapture(context, id * 10 + 2))
        views.setOnClickPendingIntent(R.id.day_add_event, activity(context, id * 10 + 3, "new_event"))

        WidgetStyle.applyListCard(views, R.id.day_card, context, id)
        views.setTextColor(R.id.day_empty, style.textSecondary)
        // Size-responsive: shed the strip (then the header) on short placements so the list keeps its rows.
        val minH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        views.setViewVisibility(R.id.day_strip_wrap, if (minH in 1..149) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.day_header, if (minH in 1..79) View.GONE else View.VISIBLE)
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

    /** New task → the translucent quick-capture popup (the whole app never comes forward). */
    private fun quickCapture(context: Context, code: Int): PendingIntent {
        val i = Intent(context, QuickCaptureActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun navIntent(context: Context, id: Int, delta: Int): PendingIntent {
        val i = Intent(context, DayNavReceiver::class.java).setAction(DayNavReceiver.ACTION_NAV).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(DayNavReceiver.EXTRA_DELTA, delta)
        }
        return PendingIntent.getBroadcast(context, id * 100 + 50 + delta, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Tap a strip cell → jump the widget straight to that day (absolute offset from today). */
    private fun setDayIntent(context: Context, id: Int, absOffset: Int): PendingIntent {
        val i = Intent(context, DayNavReceiver::class.java).setAction(DayNavReceiver.ACTION_NAV).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(DayNavReceiver.EXTRA_ABS_OFFSET, absOffset)
        }
        return PendingIntent.getBroadcast(context, id * 100 + absOffset + 500, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
            context.sendBroadcast(Intent(context, DayWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
            })
        }
    }
}

/** ‹ / › week navigation + strip-cell day selection: shift or set the widget's day offset, re-render. */
class DayNavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NAV) return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        if (intent.hasExtra(EXTRA_ABS_OFFSET)) {
            WidgetPrefs.setDayOffset(context, id, intent.getIntExtra(EXTRA_ABS_OFFSET, 0))
        } else {
            val delta = intent.getIntExtra(EXTRA_DELTA, 0)
            WidgetPrefs.setDayOffset(context, id, WidgetPrefs.dayOffset(context, id) + delta)
        }
        DayWidget.updateOne(context, id)
    }

    companion object {
        const val ACTION_NAV = "com.todocompanion.app.action.DAY_NAV"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_ABS_OFFSET = "abs_offset"
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
                           val isEvent: Boolean, val sortKey: Long, val priColor: Int = 0, val priTint: Float = 0f)
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
        val use24 = runBlocking {
            com.todocompanion.app.domain.AppClock.is24(app.repository.settingsSnapshot().timeFormat,
                android.text.format.DateFormat.is24HourFormat(context))
        }
        fun clock(h: Int, m: Int): String = if (use24) "%02d:%02d".format(h, m)
            else { val h12 = ((h + 11) % 12) + 1; val ap = if (h < 12) "AM" else "PM"; if (m == 0) "$h12 $ap" else "%d:%02d %s".format(h12, m, ap) }
        val taskRows = tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned }
            .filter { t ->
                val due = t.dueDate ?: return@filter false
                (due in dayStart until dayEnd) || (isToday && due < dayStart)
            }
            .map { t ->
                val due = t.dueDate!!
                val overdue = isToday && due < dayStart
                val dt = Instant.ofEpochMilli(due).atZone(zone)
                val hasTime = !(dt.hour == 0 && dt.minute == 0)
                val sub = when {
                    overdue -> "Overdue"
                    hasTime -> clock(dt.hour, dt.minute)
                    else -> "Task"
                }
                val (pc, pt) = WidgetBitmaps.priorityColorAndTint(t.importance, t.urgency)
                Row(t.id, t.title.ifBlank { "Untitled" }, sub, overdue, isEvent = false, sortKey = if (overdue) 0 else due, priColor = pc, priTint = pt)
            }.toList()

        val eventRows = runBlocking {
            com.todocompanion.app.domain.calendar.CalendarEngine.expand(app.repository.wsEventsOnce(), dayStart, dayEnd, zone)
                .filter { it.startMillis < dayEnd && it.endMillis > dayStart }
                .map { o ->
                    val st = Instant.ofEpochMilli(o.startMillis).atZone(zone)
                    val sub = if (o.event.allDay) "All day" else clock(st.hour, st.minute)
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
            setTextColor(R.id.item_title, style.textPrimary)
            setTextColor(R.id.item_sub, when { r.isEvent -> style.info; r.overdue -> style.danger; else -> style.textSecondary })
            val markPx = WidgetBitmaps.dp(context, 22f).toInt()
            if (r.isEvent) {
                setImageViewBitmap(R.id.item_check, WidgetBitmaps.dot(markPx, style.info))
                setContentDescription(R.id.item_check, "Open ${r.title}")
                setOnClickFillInIntent(R.id.item_check, TaskWidgetReceiver.openFill("open_calendar"))
                setOnClickFillInIntent(R.id.item_root, TaskWidgetReceiver.openFill("open_calendar"))
            } else {
                // The app's rounded-square priority checkbox — tint + border in the task's priority colour.
                setImageViewBitmap(R.id.item_check, WidgetBitmaps.priorityCheckbox(markPx, r.priColor, false, r.priTint))
                setContentDescription(R.id.item_check, "Complete ${r.title}")
                setOnClickFillInIntent(R.id.item_check, TaskWidgetReceiver.completeFill(r.id))
                setOnClickFillInIntent(R.id.item_root, TaskWidgetReceiver.openFill("open_task:${r.id}"))
            }
        }
    }
}
