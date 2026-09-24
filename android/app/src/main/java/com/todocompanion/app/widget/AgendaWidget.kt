package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home-screen "Agenda" widget: a scrolling list of today's + overdue open tasks. Tapping an item
 * opens that task; the header + button quick-adds. Offline — reads the local Room DB only.
 */
class AgendaWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderOne(context, manager, it) }
    }

    // Re-render one widget when it's resized so the header can collapse on short placements.
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        renderOne(context, manager, id)

    private fun renderOne(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_agenda)
        val svc = Intent(context, AgendaWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_list, svc)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        // The ＋ opens the translucent quick-capture popup (the whole app never comes forward).
        views.setOnClickPendingIntent(R.id.widget_add, quickCaptureIntent(context, 1))
        views.setOnClickPendingIntent(R.id.widget_header, activityIntent(context, 0, null))
        // R104 — one broadcast template; each row's fill-in either ticks the task off or opens it.
        views.setPendingIntentTemplate(R.id.widget_list, TaskWidgetReceiver.template(context, 4201))

        // Per-widget title + theme (from the configuration screen).
        val scope = WidgetPrefs.scope(context, id)
        val title = WidgetPrefs.title(context, id).ifBlank { WidgetPrefs.defaultTitle(scope) }
        views.setTextViewText(R.id.widget_title, title)
        // R104 — theme + opacity on the card layer; text colours from the shared style.
        val s = WidgetStyle.resolve(context, id)
        WidgetStyle.applyListCard(views, R.id.widget_card, context, id)
        views.setTextColor(R.id.widget_title, s.textPrimary)
        views.setTextColor(R.id.widget_empty, s.textSecondary)
        // Calendar-style header: a tear-off date tile + a "weekday · D Mon" line, echoing the app's calendar.
        val today = LocalDate.now(ZoneId.systemDefault())
        views.setImageViewBitmap(R.id.widget_dateicon,
            WidgetBitmaps.calendarIcon(WidgetBitmaps.dp(context, 38f).toInt(), s.accent, s.surfaceVariant, s.textPrimary, today.dayOfMonth))
        views.setViewVisibility(R.id.widget_dateicon, View.VISIBLE)
        val dow = today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        val mon = today.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        views.setTextViewText(R.id.widget_daterow, "$dow · ${today.dayOfMonth} $mon")
        views.setViewVisibility(R.id.widget_daterow, View.VISIBLE)
        views.setTextColor(R.id.widget_daterow, s.textSecondary)
        // Size-responsive: on a short (≈1-row-tall) placement, drop the header so the list itself — the
        // actual content — gets every pixel and the widget reads as a clean task strip.
        val minH = runCatching { manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) }.getOrDefault(0)
        views.setViewVisibility(R.id.widget_header, if (minH in 1..99) View.GONE else View.VISIBLE)
        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetPrefs.clear(context, it) }
    }

    private fun activityIntent(context: Context, code: Int, action: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** ＋ → the translucent quick-capture popup, so a task is added without opening the whole app. */
    private fun quickCaptureIntent(context: Context, code: Int): PendingIntent {
        val intent = Intent(context, QuickCaptureActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, AgendaWidget::class.java))
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            context.sendBroadcast(Intent(context, AgendaWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }

        /** Re-render a single widget after its configuration changed. */
        fun updateOne(context: Context, id: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            AgendaWidget().onUpdate(context, manager, intArrayOf(id))
        }
    }
}

class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return AgendaFactory(applicationContext, id)
    }
}

private class AgendaFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val title: String, val sub: String, val overdue: Boolean,
                           val isEvent: Boolean = false, val sortKey: Long = Long.MAX_VALUE,
                           val priColor: Int = 0, val priTint: Float = 0f)
    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    private var style: WidgetStyle = WidgetStyle.resolve(context)

    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val startToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endWeek = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()

        val scope = WidgetPrefs.scope(context, widgetId)
        style = WidgetStyle.resolve(context, widgetId)
        val listId = if (scope.startsWith("list:")) scope.removePrefix("list:") else null

        val tasks = runBlocking { app.repository.wsTasksOnce() }
        // Phase 0 S1: honour the app's 12/24-hour clock on the widget too (falls back to device preference).
        val use24 = runBlocking {
            com.todocompanion.app.domain.AppClock.is24(app.repository.settingsSnapshot().timeFormat,
                android.text.format.DateFormat.is24HourFormat(context))
        }
        fun clock(h: Int, m: Int): String = if (use24) "%02d:%02d".format(h, m)
            else { val h12 = ((h + 11) % 12) + 1; val ap = if (h < 12) "AM" else "PM"; if (m == 0) "$h12 $ap" else "%d:%02d %s".format(h12, m, ap) }
        val taskRows = tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned }
            .filter { t ->
                when {
                    listId != null -> t.listId == listId
                    scope == "next7" -> t.dueDate != null && t.dueDate!! < endWeek
                    scope == "scheduled" -> t.dueDate != null
                    else -> t.dueDate != null && t.dueDate!! < endToday   // "today" = today + overdue
                }
            }
            .map { t ->
                val due = t.dueDate
                val overdue = due != null && due < startToday
                val sub = when {
                    due == null -> "No date"
                    overdue -> "Overdue"
                    else -> {
                        val dt = Instant.ofEpochMilli(due).atZone(zone)
                        val hasTime = !(dt.hour == 0 && dt.minute == 0)
                        val d = dt.toLocalDate()
                        when {
                            d == today && hasTime -> clock(dt.hour, dt.minute)
                            d == today -> "Today"
                            else -> "${d.dayOfMonth}/${d.monthValue}" + if (hasTime) " " + clock(dt.hour, dt.minute) else ""
                        }
                    }
                }
                val (pc, pt) = WidgetBitmaps.priorityColorAndTint(t.importance, t.urgency)
                Row(t.id, t.title.ifBlank { "Untitled" }, sub, overdue, isEvent = false, sortKey = due ?: Long.MAX_VALUE, priColor = pc, priTint = pt)
            }.toList()

        // R41 — the agenda reads the dedicated calendar too: today's / this-week's EVENT occurrences,
        // interleaved by start time. Only for the time-based scopes; a list-scoped widget stays tasks-only.
        val eventRows = if (listId != null || scope == "scheduled") emptyList() else runBlocking {
            val winEnd = if (scope == "next7") endWeek else endToday
            com.todocompanion.app.domain.calendar.CalendarEngine.expand(app.repository.wsEventsOnce(), startToday, winEnd, zone)
                .filter { it.endMillis >= System.currentTimeMillis() && it.startMillis < winEnd }
                .map { o ->
                    val st = Instant.ofEpochMilli(o.startMillis).atZone(zone)
                    val d = st.toLocalDate()
                    val sub = when {
                        o.event.allDay -> if (d == today) "All day" else "${d.dayOfMonth}/${d.monthValue} · all day"
                        d == today -> clock(st.hour, st.minute)
                        else -> "${d.dayOfMonth}/${d.monthValue} " + clock(st.hour, st.minute)
                    }
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
            // R104 — per-widget font scale + compact density (config).
            val vpad = (((if (style.compact) 4 else 8)) * context.resources.displayMetrics.density).toInt()
            setViewPadding(R.id.item_root, 0, vpad, 0, vpad)
            setTextViewTextSize(R.id.item_title, android.util.TypedValue.COMPLEX_UNIT_SP, style.sp(14f))
            setTextViewTextSize(R.id.item_sub, android.util.TypedValue.COMPLEX_UNIT_SP, style.sp(12f))
            setTextColor(R.id.item_title, style.textPrimary)
            setTextColor(R.id.item_sub, when {
                r.isEvent -> style.info
                r.overdue -> style.danger
                else -> style.textSecondary
            })
            // A drawn check-circle (modern, matches the in-app task checkbox): tasks tick off in place;
            // an event shows a coloured dot and just opens the calendar (events aren't completable here).
            val markPx = WidgetBitmaps.dp(context, 22f).toInt()
            if (r.isEvent) {
                setImageViewBitmap(R.id.item_check, WidgetBitmaps.dot(markPx, style.info))
                setContentDescription(R.id.item_check, "Open ${r.title}")
                setOnClickFillInIntent(R.id.item_check, TaskWidgetReceiver.openFill("open_calendar"))
            } else {
                // The app's rounded-square priority checkbox — tint + border in the task's priority colour.
                setImageViewBitmap(R.id.item_check, WidgetBitmaps.priorityCheckbox(markPx, r.priColor, false, r.priTint))
                setContentDescription(R.id.item_check, "Complete ${r.title}")
                setOnClickFillInIntent(R.id.item_check, TaskWidgetReceiver.completeFill(r.id))
            }
            // The rest of the row opens the task (or the calendar for an event).
            val action = if (r.isEvent) "open_calendar" else "open_task:${r.id}"
            setOnClickFillInIntent(R.id.item_root, TaskWidgetReceiver.openFill(action))
        }
    }
}
