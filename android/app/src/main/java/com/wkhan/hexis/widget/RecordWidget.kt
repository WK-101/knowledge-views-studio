package com.wkhan.hexis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.wkhan.hexis.App
import com.wkhan.hexis.MainActivity
import com.wkhan.hexis.R
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * R66 — "The Record" widget: a scrolling list of what you've recently finished, wins (★) first among
 * same-day items. Tapping an entry opens that task; the header opens The Record. Reuses the Agenda
 * widget's layouts. Offline — reads the local DB only, scoped to the active workspace.
 */
class RecordWidget : BaseWidgetProvider() {
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
            // The Record reuses the Agenda layout but isn't a dated view — hide the calendar date tile/row.
            views.setViewVisibility(R.id.widget_dateicon, android.view.View.GONE)
            views.setViewVisibility(R.id.widget_daterow, android.view.View.GONE)
            views.setTextViewText(R.id.widget_empty, "Finish something to see it here")
            val s = WidgetStyle.resolve(context, id)
            WidgetStyle.applyListCard(views, R.id.widget_card, context, id)
            views.setTextColor(R.id.widget_title, s.textPrimary)
            views.setTextColor(R.id.widget_empty, s.textSecondary)
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }

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
        fun refresh(context: Context) = Widgets.broadcastUpdate(context, RecordWidget::class.java, R.id.widget_list)
    }
}

class RecordWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return RecordFactory(applicationContext, id)
    }
}

private class RecordFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val title: String, val sub: String)
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
        val today = LocalDate.now(zone)
        style = WidgetStyle.resolve(context, widgetId)

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
            val vpad = (((if (style.compact) 4 else 8)) * context.resources.displayMetrics.density).toInt()
            setViewPadding(R.id.item_root, 0, vpad, 0, vpad)
            setTextViewTextSize(R.id.item_title, android.util.TypedValue.COMPLEX_UNIT_SP, style.sp(14f))
            setTextViewTextSize(R.id.item_sub, android.util.TypedValue.COMPLEX_UNIT_SP, style.sp(12f))
            setTextColor(R.id.item_title, style.textPrimary)
            setTextColor(R.id.item_sub, style.textSecondary)
            // A drawn, filled check-circle (modern, matches the in-app completed mark) — these are finished tasks.
            setImageViewBitmap(R.id.item_check, WidgetBitmaps.checkCircle(WidgetBitmaps.dp(context, 22f).toInt(), style.success, true))
            val fill = Intent().putExtra(MainActivity.EXTRA_ACTION, "open_task:${r.id}")
            setOnClickFillInIntent(R.id.item_root, fill)
        }
    }
}
