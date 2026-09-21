package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * A full-year contribution grid of your whole practice — 52 weeks × 7 days, each cell shaded by how
 * much of that day's due habits you completed. The "GitHub graph for showing up" that no consumer
 * tracker puts on the home screen. Drawn as a bitmap; offline.
 */
class HabitGridWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        render(context, manager, id)
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val style = WidgetStyle.resolve(context, id)
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val cols = 52; val rows = 7
                val mondayOfToday = today - HabitWidgetData.weekdayMon0(today)
                val fromDay = mondayOfToday - (cols - 1) * 7L
                val daily = HabitWidgetData.dailyCompletion(app, fromDay, today)

                val emptyCell = if (style.dark) 0x1FFFFFFF else 0x14000000
                val full = style.teal
                val targetW = WidgetBitmaps.dp(context, 320f)
                val gap = WidgetBitmaps.dp(context, 1.5f)
                val cell = ((targetW - (cols - 1) * gap) / cols).coerceAtLeast(2f)

                val bmp = WidgetBitmaps.heatmap(cols, rows, cell, gap) { col, row ->
                    val cellMonday = mondayOfToday - (cols - 1 - col) * 7L
                    val epochDay = cellMonday + row
                    if (epochDay > today || epochDay < fromDay) emptyCell
                    else {
                        val i = (epochDay - fromDay).toInt()
                        val intensity = daily.intensity(i)
                        if (intensity <= 0f) emptyCell
                        else {
                            // Bucket into four steps so a light day still reads as distinct from a full one.
                            val bucket = when {
                                intensity >= 0.999f -> 1f
                                intensity >= 0.66f -> 0.8f
                                intensity >= 0.34f -> 0.58f
                                else -> 0.36f
                            }
                            WidgetBitmaps.blend(emptyCell, full, bucket)
                        }
                    }
                }

                var activeDays = 0
                for (i in 0 until daily.size) if (daily.done[i] > 0) activeDays++

                val views = RemoteViews(context.packageName, R.layout.widget_habitgrid)
                WidgetStyle.applyListCard(views, R.id.hg_card, context, id)
                views.setImageViewBitmap(R.id.hg_grid, bmp)
                views.setTextViewText(R.id.hg_title, "Your year")
                views.setTextColor(R.id.hg_title, style.textPrimary)
                views.setTextViewText(R.id.hg_caption, "$activeDays days you showed up")
                views.setTextColor(R.id.hg_caption, style.textSecondary)
                views.setOnClickPendingIntent(R.id.hg_root, openHabits(context))
                manager.updateAppWidget(id, views)
            } catch (_: Throwable) {
            } finally { pending.finish() }
        }
    }

    private fun openHabits(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_habits")
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, HabitGridWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, HabitGridWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
