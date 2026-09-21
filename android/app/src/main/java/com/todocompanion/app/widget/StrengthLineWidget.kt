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
import kotlin.math.roundToInt

/**
 * Habit strength as a Loop-style trend line: a trailing-28-day completion ratio sampled weekly over
 * ~6 months, so you see whether your practice is climbing or slipping — the "score" read that a
 * grid of streak counts can't give. Drawn as a bitmap; offline.
 */
class StrengthLineWidget : AppWidgetProvider() {
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
                val window = 28
                val span = 210
                val fromDay = today - (span - 1)
                val daily = HabitWidgetData.dailyCompletion(app, fromDay, today)
                val n = daily.size

                val values = ArrayList<Float>()
                var idx = window - 1
                while (idx < n) { values.add(daily.trailingRatio(idx, window)); idx += 7 }
                if (values.isEmpty()) values.add(daily.trailingRatio(n - 1, window))

                val current = daily.trailingRatio(n - 1, window)
                val monthAgoIdx = (n - 1 - 28).coerceAtLeast(window - 1)
                val monthAgo = daily.trailingRatio(monthAgoIdx, window)
                val curPct = (current * 100).roundToInt()
                val agoPct = (monthAgo * 100).roundToInt()
                val trend = when {
                    curPct - agoPct >= 3 -> "↑ up from $agoPct% a month ago"
                    agoPct - curPct >= 3 -> "↓ down from $agoPct% a month ago"
                    else -> "holding steady"
                }

                val wpx = WidgetBitmaps.dp(context, 300f).toInt()
                val hpx = WidgetBitmaps.dp(context, 90f).toInt()
                val area = (style.teal and 0x00FFFFFF) or 0x66000000
                val bmp = WidgetBitmaps.line(wpx, hpx, values.toFloatArray(), style.teal, area, style.accent)

                val views = RemoteViews(context.packageName, R.layout.widget_habitline)
                WidgetStyle.applyListCard(views, R.id.hl_card, context, id)
                views.setImageViewBitmap(R.id.hl_line, bmp)
                views.setTextViewText(R.id.hl_title, "Habit strength")
                views.setTextColor(R.id.hl_title, style.textPrimary)
                views.setTextViewText(R.id.hl_value, "$curPct%")
                views.setTextColor(R.id.hl_value, style.accentText)
                views.setTextViewText(R.id.hl_caption, trend)
                views.setTextColor(R.id.hl_caption, style.textSecondary)
                views.setOnClickPendingIntent(R.id.hl_root, openHabits(context))
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
            val ids = m.getAppWidgetIds(ComponentName(context, StrengthLineWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, StrengthLineWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
