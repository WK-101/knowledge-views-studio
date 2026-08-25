package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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

/**
 * Home-screen "Agenda" widget: a scrolling list of today's + overdue open tasks. Tapping an item
 * opens that task; the header + button quick-adds. Offline — reads the local Room DB only.
 */
class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_agenda)
            val svc = Intent(context, AgendaWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, svc)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            views.setOnClickPendingIntent(R.id.widget_add, activityIntent(context, 1, MainActivity.ACTION_QUICK_ADD))
            views.setOnClickPendingIntent(R.id.widget_header, activityIntent(context, 0, null))
            // Template for per-item taps; the factory supplies a fill-in intent with the task id.
            views.setPendingIntentTemplate(R.id.widget_list, itemTemplate(context))

            // Per-widget title + theme (from the configuration screen).
            val scope = WidgetPrefs.scope(context, id)
            val title = WidgetPrefs.title(context, id).ifBlank { WidgetPrefs.defaultTitle(scope) }
            views.setTextViewText(R.id.widget_title, title)
            val light = when (WidgetPrefs.theme(context, id)) {
                "light" -> true
                "dark" -> false
                else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            if (light) {
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg_light)
                views.setTextColor(R.id.widget_title, 0xFF1B1B2F.toInt())
                views.setTextColor(R.id.widget_empty, 0xFF6B6880.toInt())
            } else {
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg)
                views.setTextColor(R.id.widget_title, 0xFFFFFFFF.toInt())
                views.setTextColor(R.id.widget_empty, 0xFFB9B4D0.toInt())
            }
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
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

    private fun itemTemplate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
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
    private data class Row(val id: String, val title: String, val sub: String, val overdue: Boolean)
    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    private var light = false

    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val startToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endWeek = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()

        val scope = WidgetPrefs.scope(context, widgetId)
        light = when (WidgetPrefs.theme(context, widgetId)) {
            "light" -> true
            "dark" -> false
            else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val listId = if (scope.startsWith("list:")) scope.removePrefix("list:") else null

        val tasks = runBlocking { app.repository.allTasksOnce() }
        rows = tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned }
            .filter { t ->
                when {
                    listId != null -> t.listId == listId
                    scope == "next7" -> t.dueDate != null && t.dueDate!! < endWeek
                    scope == "scheduled" -> t.dueDate != null
                    else -> t.dueDate != null && t.dueDate!! < endToday   // "today" = today + overdue
                }
            }
            .sortedBy { it.dueDate ?: Long.MAX_VALUE }
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
                            d == today && hasTime -> "%02d:%02d".format(dt.hour, dt.minute)
                            d == today -> "Today"
                            else -> "${d.dayOfMonth}/${d.monthValue}" + if (hasTime) " %02d:%02d".format(dt.hour, dt.minute) else ""
                        }
                    }
                }
                Row(t.id, t.title.ifBlank { "Untitled" }, sub, overdue)
            }
            .take(50).toList()
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_agenda_item).apply {
            setTextViewText(R.id.item_title, r.title)
            setTextViewText(R.id.item_sub, r.sub)
            setTextColor(R.id.item_title, if (light) 0xFF1B1B2F.toInt() else 0xFFFFFFFF.toInt())
            setTextColor(R.id.item_sub, if (r.overdue) 0xFFE5484D.toInt() else if (light) 0xFF6B6880.toInt() else 0xFFB9B4D0.toInt())
            // Fill-in intent carries the task id back through the template.
            val fill = Intent().putExtra(MainActivity.EXTRA_ACTION, "open_task:${r.id}")
            setOnClickFillInIntent(R.id.item_root, fill)
        }
    }
}
