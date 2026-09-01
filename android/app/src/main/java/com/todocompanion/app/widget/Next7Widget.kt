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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Next-7-days workload widget: a bar per upcoming day showing committed time-estimate against
 * your daily capacity (Settings → Task editor → Daily capacity, per-weekday aware). Over-committed
 * days turn red so you can rebalance before the week buries you — planning intelligence neither MLO
 * nor TickTick offers. Offline; reads the local DB only.
 */
class Next7Widget : AppWidgetProvider() {
    private val dayIds = intArrayOf(R.id.n7_day0, R.id.n7_day1, R.id.n7_day2, R.id.n7_day3, R.id.n7_day4, R.id.n7_day5, R.id.n7_day6)
    private val barIds = intArrayOf(R.id.n7_bar0, R.id.n7_bar1, R.id.n7_bar2, R.id.n7_bar3, R.id.n7_bar4, R.id.n7_bar5, R.id.n7_bar6)
    private val hrsIds = intArrayOf(R.id.n7_hrs0, R.id.n7_hrs1, R.id.n7_hrs2, R.id.n7_hrs3, R.id.n7_hrs4, R.id.n7_hrs5, R.id.n7_hrs6)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val tasks = app.repository.wsTasksOnce()
                val settings = app.repository.settingsSnapshot()

                val views = RemoteViews(context.packageName, R.layout.widget_next7)
                var over = 0
                for (off in 0..6) {
                    val d = today.plusDays(off.toLong())
                    val dayTasks = tasks.filter {
                        !it.completed && !it.trashed && !it.abandoned && it.dueDate != null &&
                            Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate() == d
                    }
                    val min = dayTasks.sumOf { it.estimateMin ?: it.estimateMax ?: it.durationMin ?: 0 }
                    val capMin = (settings.capacityHoursFor(d.dayOfWeek) * 60).coerceAtLeast(30)
                    val overCap = min > capMin
                    if (overCap) over++
                    val frac = (min.toFloat() / capMin).coerceIn(0f, 1f)

                    views.setTextViewText(dayIds[off], d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                    views.setTextColor(dayIds[off], if (d == today) 0xFF6650A4.toInt() else 0xFFB9B4D0.toInt())
                    views.setProgressBar(barIds[off], 100, (frac * 100).toInt(), false)
                    views.setTextViewText(hrsIds[off], if (min > 0) "${(min + 30) / 60}h" else "—")
                    views.setTextColor(hrsIds[off], if (overCap) 0xFFE5484D.toInt() else 0xFFE9E5FF.toInt())
                }
                views.setTextViewText(R.id.n7_status, if (over == 0) "On track" else "$over over")
                views.setTextColor(R.id.n7_status, if (over == 0) 0xFF12A594.toInt() else 0xFFE5484D.toInt())
                views.setOnClickPendingIntent(R.id.n7_root, openNext7(context))
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun openNext7(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_next7")
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, Next7Widget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, Next7Widget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
