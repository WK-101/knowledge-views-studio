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
class QuickBarWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        render(context, manager, id)

    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetPrefs.clear(context, it) } }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val style = WidgetStyle.resolve(context, id)
        val views = RemoteViews(context.packageName, R.layout.widget_quickbar)
        // The whole island — a solid rounded card plus the minimal action glyphs — is drawn into qb_face
        // sized to the widget's real aspect, so the card layer isn't needed and nothing gets stretched.
        views.setViewVisibility(R.id.qb_card, android.view.View.GONE)

        val count = WidgetPrefs.quickCount(context, id)
        val slots = WidgetPrefs.quickSlots(context, id).take(count)

        // Size the bitmap to the widget's ACTUAL current dimensions (orientation-aware), so `fitXY` maps
        // 1:1 and never squashes the square chip / round icons the way the min-size did.
        val opts = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wDp = ((if (portrait) opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)) ?: 0)
            .let { if (it <= 0) 160 else it }.coerceIn(90, 640)
        val hDp = ((if (portrait) opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)) ?: 0)
            .let { if (it <= 0) 160 else it }.coerceIn(90, 640)
        val wPx = WidgetBitmaps.dp(context, wDp.toFloat()).toInt()
        val hPx = WidgetBitmaps.dp(context, hDp.toFloat()).toInt()

        // Every slot is a clickable action; slot 0 sits in the centre (defaults to "app" — the brand
        // mark that opens Kairo). Solid theme card, faded to the per-widget opacity.
        val op = WidgetPrefs.opacity(context, id).coerceIn(0, 100)
        val cardColor = ((255 * op / 100) shl 24) or (style.surface and 0x00FFFFFF)
        views.setImageViewBitmap(
            R.id.qb_face,
            WidgetBitmaps.quickCluster(context, wPx, hPx, slots, cardColor, style.textPrimary, style.accent),
        )

        // Transparent tap grid: each cell fires its slot's action (weights mirror clusterPositions).
        views.removeAllViews(R.id.qb_grid)
        val grid = RemoteViews(context.packageName, clusterLayoutFor(count))
        slots.forEachIndexed { i, key ->
            val cellId = CELL_IDS[i]
            grid.setOnClickPendingIntent(cellId, pendingFor(context, id, i, key))
            grid.setContentDescription(cellId, labelFor(key))
        }
        views.addView(R.id.qb_grid, grid)
        manager.updateAppWidget(id, views)
    }

    /** The tap-grid layout for N total actions (5–9) — the brand-plus-ring N-cell grid. */
    private fun clusterLayoutFor(n: Int): Int = when (n.coerceIn(5, 9)) {
        5 -> R.layout.widget_qb_cluster_5
        6 -> R.layout.widget_qb_cluster_6
        7 -> R.layout.widget_qb_cluster_7
        8 -> R.layout.widget_qb_cluster_8
        else -> R.layout.widget_qb_cluster_9
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
            "app" -> appIntent(context, "open_today")
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
        "app" -> "Open Kairo"; "task" -> "Task"; "note" -> "Note"; "habit" -> "Habit"; "time" -> "Time"
        "search" -> "Search"; "closeday" -> "Close"; "weekreview" -> "Review"
        "dailynote" -> "Daily note"; else -> key
    }

    companion object {
        /** Fixed tap-cell ids, in reading order, matching qb_c0..qb_c6 across every widget_qb_cluster_N. */
        private val CELL_IDS = intArrayOf(
            R.id.qb_c0, R.id.qb_c1, R.id.qb_c2, R.id.qb_c3, R.id.qb_c4, R.id.qb_c5, R.id.qb_c6, R.id.qb_c7, R.id.qb_c8,
        )

        /** Human-readable names for the settings screen. */
        fun displayName(key: String): String = when (key) {
            "app" -> "Open Kairo"; "task" -> "Quick add task"; "note" -> "Quick add note"; "habit" -> "Quick habit check"
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
