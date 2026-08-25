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
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
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
    }
}

class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = AgendaFactory(applicationContext)
}

private class AgendaFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val title: String, val sub: String, val overdue: Boolean)
    private var rows: List<Row> = emptyList()

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
        val today = LocalDate.now(zone)
        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val startToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tasks = runBlocking { app.repository.allTasksOnce() }
        rows = tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned && it.dueDate != null && it.dueDate!! < endToday }
            .sortedBy { it.dueDate }
            .map { t ->
                val due = t.dueDate!!
                val overdue = due < startToday
                val dt = Instant.ofEpochMilli(due).atZone(zone)
                val hasTime = !(dt.hour == 0 && dt.minute == 0)
                val sub = when {
                    overdue -> "Overdue"
                    hasTime -> "%02d:%02d".format(dt.hour, dt.minute)
                    else -> "Today"
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
            setTextColor(R.id.item_sub, if (r.overdue) 0xFFFF9A8B.toInt() else 0xFFB9B4D0.toInt())
            // Fill-in intent carries the task id back through the template.
            val fill = Intent().putExtra(MainActivity.EXTRA_ACTION, "open_task:${r.id}")
            setOnClickFillInIntent(R.id.item_root, fill)
        }
    }
}
