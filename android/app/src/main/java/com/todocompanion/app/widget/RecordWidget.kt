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
 * R66 — "The Record" widget: a scrolling list of what you've recently finished, wins (★) first among
 * same-day items. Tapping an entry opens that task; the header opens The Record. Reuses the Agenda
 * widget's layouts. Offline — reads the local DB only, scoped to the active workspace.
 */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_agenda)
            val svc = Intent(context, RecordWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, svc)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            views.setOnClickPendingIntent(R.id.widget_add, headerIntent(context))
            views.setOnClickPendingIntent(R.id.widget_header, headerIntent(context))
            views.setPendingIntentTemplate(R.id.widget_list, itemTemplate(context))

            views.setTextViewText(R.id.widget_title, WidgetPrefs.title(context, id).ifBlank { "The Record" })
            views.setTextViewText(R.id.widget_empty, "Finish something to see it here")
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

    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPrefs.clear(context, it) } }

    private fun headerIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_record")
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun itemTemplate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            context.sendBroadcast(Intent(context, RecordWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}

class RecordWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = RecordFactory(applicationContext)
}

private class RecordFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val title: String, val sub: String)
    private var rows: List<Row> = emptyList()
    private var light = false

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
        light = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES

        val done = runBlocking { app.repository.wsTasksOnce() }
            .filter { it.completed && !it.trashed && it.completedAt != null }
            .sortedByDescending { it.completedAt }
            .take(50)
        rows = done.map { t ->
            val d = Instant.ofEpochMilli(t.completedAt!!).atZone(zone).toLocalDate()
            val sub = when (d) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> "${d.dayOfMonth}/${d.monthValue}"
            }
            Row(t.id, (if (t.winFlag) "★ " else "") + t.title.ifBlank { "Untitled" }, sub)
        }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_agenda_item).apply {
            setTextViewText(R.id.item_title, r.title)
            setTextViewText(R.id.item_sub, r.sub)
            setTextColor(R.id.item_title, if (light) 0xFF1B1B2F.toInt() else 0xFFFFFFFF.toInt())
            setTextColor(R.id.item_sub, if (light) 0xFF6B6880.toInt() else 0xFFB9B4D0.toInt())
            val fill = Intent().putExtra(MainActivity.EXTRA_ACTION, "open_task:${r.id}")
            setOnClickFillInIntent(R.id.item_root, fill)
        }
    }
}
