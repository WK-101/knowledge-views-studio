package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R

/**
 * R107 — the Quick-bar widget: a modular toolbar of quick-capture buttons (Google-Keep-style), fully
 * configurable. The number of buttons (4–7) and the function of each slot are chosen in the widget's
 * settings, so you build the exact launcher you want. Every button opens a compact popup (task, note,
 * habit check, time-track, search) or jumps straight into a flow (close the day, weekly review) — no
 * full-app launch for the capture actions. Offline; the popups read/write the local DB only.
 */
class QuickBarWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        render(context, manager, id)

    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPrefs.clear(context, it) } }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val style = WidgetStyle.resolve(context, id)
        val views = RemoteViews(context.packageName, R.layout.widget_quickbar)
        WidgetStyle.applyListCard(views, R.id.qb_card, context, id)
        views.removeAllViews(R.id.qb_row0)
        views.removeAllViews(R.id.qb_row1)

        val count = WidgetPrefs.quickCount(context, id)
        val slots = WidgetPrefs.quickSlots(context, id).take(count)
        val minH = runCatching { manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) }.getOrDefault(0)
        // Labels off when compact, or when the widget is too short to fit icon + caption cleanly.
        val showLabel = !WidgetPrefs.compact(context, id) && (minH == 0 || minH >= 110)
        val iconPx = WidgetBitmaps.dp(context, if (showLabel) 46f else 50f).toInt()
        val row0n = (count + 1) / 2   // ceil — top row holds the extra when odd

        slots.forEachIndexed { i, key ->
            val btn = RemoteViews(context.packageName, R.layout.widget_quickbar_button)
            btn.setImageViewBitmap(R.id.qb_icon, WidgetBitmaps.actionIcon(iconPx, style.accent, style.onAccent, key))
            btn.setContentDescription(R.id.qb_icon, labelFor(key))
            if (showLabel) {
                btn.setViewVisibility(R.id.qb_label, View.VISIBLE)
                btn.setTextViewText(R.id.qb_label, labelFor(key))
                btn.setTextColor(R.id.qb_label, style.textSecondary)
            } else {
                btn.setViewVisibility(R.id.qb_label, View.GONE)
            }
            btn.setOnClickPendingIntent(R.id.qb_btn_root, pendingFor(context, id, i, key))
            views.addView(if (i < row0n) R.id.qb_row0 else R.id.qb_row1, btn)
        }
        manager.updateAppWidget(id, views)
    }

    private fun pendingFor(context: Context, widgetId: Int, index: Int, key: String): PendingIntent {
        val reqCode = widgetId * 10 + index
        val intent = when (key) {
            "task" -> Intent(context, QuickCaptureActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            "note" -> Intent(context, QuickNoteActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            "habit" -> Intent(context, QuickHabitsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            "time" -> Intent(context, QuickTimeActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            "search" -> Intent(context, QuickSearchActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            "closeday" -> appIntent(context, "open_close_day")
            "weekreview" -> appIntent(context, "open_weekreview")
            else -> appIntent(context, "open_today")
        }
        return PendingIntent.getActivity(context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun appIntent(context: Context, action: String) =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
        }

    private fun labelFor(key: String): String = when (key) {
        "task" -> "Task"; "note" -> "Note"; "habit" -> "Habit"; "time" -> "Time"
        "search" -> "Search"; "closeday" -> "Close"; "weekreview" -> "Review"; else -> key
    }

    companion object {
        /** Human-readable names for the settings screen. */
        fun displayName(key: String): String = when (key) {
            "task" -> "Quick add task"; "note" -> "Quick add note"; "habit" -> "Quick habit check"
            "time" -> "Quick time track"; "search" -> "Quick search"; "closeday" -> "Close the day"
            "weekreview" -> "Weekly review"; else -> key
        }

        fun updateOne(context: Context, id: Int) {
            val m = AppWidgetManager.getInstance(context) ?: return
            QuickBarWidget().render(context, m, id)
        }

        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, QuickBarWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, QuickBarWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
