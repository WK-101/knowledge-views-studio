package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        // The island is the widget's own flat, themeable rounded card (theme + opacity honoured), with
        // the minimal action glyphs drawn on top — the modern launcher-shortcut look.
        WidgetStyle.applyListCard(views, R.id.qb_card, context, id)
        views.setViewVisibility(R.id.qb_card, android.view.View.VISIBLE)

        val count = WidgetPrefs.quickCount(context, id)
        val slots = WidgetPrefs.quickSlots(context, id).take(count)

        // The face: minimal monochrome line glyphs on a clean grid — a larger primary in the centre
        // (on one subtle tonal chip) and the other actions in the corners. Sized to the widget's pixels.
        val opts = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val wDp = (opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180) ?: 180).coerceIn(100, 640)
        val hDp = (opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140) ?: 140).coerceIn(70, 400)
        val wPx = WidgetBitmaps.dp(context, wDp.toFloat()).toInt()
        val hPx = WidgetBitmaps.dp(context, hDp.toFloat()).toInt()
        views.setImageViewBitmap(
            R.id.qb_face,
            WidgetBitmaps.quickCluster(wPx, hPx, slots, style.textPrimary, style.accent, style.surfaceVariant),
        )

        // Transparent per-count tap grid whose cells sit exactly over the drawn discs
        // (weights mirror WidgetBitmaps.clusterPositions). Filled into qb_grid at runtime.
        views.removeAllViews(R.id.qb_grid)
        val grid = RemoteViews(context.packageName, clusterLayoutFor(slots.size))
        slots.forEachIndexed { i, key ->
            val cellId = CELL_IDS[i]
            grid.setOnClickPendingIntent(cellId, pendingFor(context, id, i, key))
            grid.setContentDescription(cellId, labelFor(key))
        }
        views.addView(R.id.qb_grid, grid)
        manager.updateAppWidget(id, views)
    }

    /** The cluster tap-grid layout for a given action count (4–7); other counts clamp to the range. */
    private fun clusterLayoutFor(n: Int): Int = when (n.coerceIn(4, 7)) {
        4 -> R.layout.widget_qb_cluster_4
        5 -> R.layout.widget_qb_cluster_5
        6 -> R.layout.widget_qb_cluster_6
        else -> R.layout.widget_qb_cluster_7
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
            "dailynote" -> appIntent(context, "new_daily_note")
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
        "search" -> "Search"; "closeday" -> "Close"; "weekreview" -> "Review"
        "dailynote" -> "Daily note"; else -> key
    }

    companion object {
        /** Fixed tap-cell ids, in reading order, matching qb_c0..qb_c6 across every widget_qb_cluster_N. */
        private val CELL_IDS = intArrayOf(
            R.id.qb_c0, R.id.qb_c1, R.id.qb_c2, R.id.qb_c3, R.id.qb_c4, R.id.qb_c5, R.id.qb_c6,
        )

        /** Human-readable names for the settings screen. */
        fun displayName(key: String): String = when (key) {
            "task" -> "Quick add task"; "note" -> "Quick add note"; "habit" -> "Quick habit check"
            "time" -> "Quick time track"; "search" -> "Quick search"; "closeday" -> "Close the day"
            "weekreview" -> "Weekly review"; "dailynote" -> "Today's daily note"; else -> key
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
