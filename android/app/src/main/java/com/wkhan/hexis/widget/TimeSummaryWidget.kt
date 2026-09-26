package com.wkhan.hexis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.wkhan.hexis.App
import com.wkhan.hexis.MainActivity
import com.wkhan.hexis.R
import com.wkhan.hexis.domain.TimeStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * R107 — Time Summary. The read-only twin of the Time tracker: today's tracked total, the top activities
 * behind it, and the week's running total. Pure on-device aggregation (TimeStats.overview) over the local
 * timeline — the natural companion to the start/stop Time widget. Fully offline. Tap opens the Time tab.
 */
class TimeSummaryWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val today = LocalDate.now(zone)
                val ws = runCatching { app.repository.activeWs() }.getOrNull()
                val entries = runCatching { app.repository.timeEntriesOnce() }.getOrDefault(emptyList())
                    .let { list -> if (ws != null) list.filter { it.workspaceId == ws } else list }
                val activities = runCatching { app.repository.wsTimeActivitiesOnce() }.getOrDefault(emptyList())

                val day = TimeStats.overview(entries, activities, TimeStats.Range.DAY, today, zone, now)
                val week = TimeStats.overview(entries, activities, TimeStats.Range.WEEK, today, zone, now)
                val top = day.slices.take(3)

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_timesummary)
                    WidgetStyle.applyListCard(views, R.id.ts_card, context, id)
                    views.setTextColor(R.id.ts_label, style.accentText)
                    views.setTextColor(R.id.ts_total, style.textPrimary)
                    views.setTextColor(R.id.ts_week, style.textSecondary)
                    views.setTextViewText(R.id.ts_total, fmt(day.totalMin))

                    val rowIds = intArrayOf(R.id.ts_r0, R.id.ts_r1, R.id.ts_r2)
                    for (i in rowIds.indices) {
                        val s = top.getOrNull(i)
                        if (s == null) {
                            views.setViewVisibility(rowIds[i], if (i == 0 && top.isEmpty()) View.VISIBLE else View.GONE)
                            if (i == 0 && top.isEmpty()) {
                                views.setTextViewText(rowIds[0], "No time tracked yet today.")
                                views.setTextColor(rowIds[0], style.textSecondary)
                            }
                        } else {
                            views.setViewVisibility(rowIds[i], View.VISIBLE)
                            views.setTextViewText(rowIds[i], "${s.emoji?.plus(" ") ?: "• "}${s.name}   ·   ${fmt(s.minutes)}")
                            views.setTextColor(rowIds[i], style.textPrimary)
                        }
                    }
                    views.setTextViewText(R.id.ts_week, if (week.totalMin > 0) "This week · ${fmt(week.totalMin)}" else "")
                    views.setOnClickPendingIntent(R.id.ts_root, openTime(context))
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    /** Compact tracked-time label: "0m", "45m", "2h", "3h 20m". */
    private fun fmt(min: Int): String {
        if (min < 60) return "${min}m"
        val h = min / 60; val m = min % 60
        return if (m == 0) "${h}h" else "${h}h ${m}m"
    }

    private fun openTime(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_time")
        }
        return PendingIntent.getActivity(context, "open_time_sum".hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) = Widgets.broadcastUpdate(context, TimeSummaryWidget::class.java)
    }
}
