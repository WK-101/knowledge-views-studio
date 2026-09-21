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
import com.todocompanion.app.domain.habit.HabitStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Today's practice as a ring: how much is done, with done/left/skipped and the coach's top move. Offline. */
class HabitStatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val habits = app.repository.wsHabitsOnce().filter { !it.archived && !it.paused }
                val checkins = app.repository.getHabitCheckinsOnce()
                var due = 0; var done = 0; var skipped = 0; var bestStreak = 0
                habits.forEach { h ->
                    val hc = checkins.filter { it.habitId == h.id }
                    val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                    val skipDays = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
                    val relapse = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                    val todayCount = hc.firstOrNull { it.epochDay == today }?.count ?: 0
                    // Break/quit habits are never "due" (success is passive), so excluding them from the
                    // due/done tally stops them being counted as always-done; their clean streak still counts.
                    val scheduled = h.habitType != "break" && (HabitStats.isExpectedDay(h, today) || h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH)
                    if (scheduled) {
                        due++
                        when {
                            !HabitStats.dueToday(h, today, doneDays, todayCount) -> done++
                            today in skipDays -> skipped++
                        }
                    }
                    bestStreak = maxOf(bestStreak, HabitStats.currentStreak(h, doneDays, skipDays, relapse, today))
                }
                val left = (due - done - skipped).coerceAtLeast(0)
                val progress = if (due > 0) done.toFloat() / due else 0f

                // N5: the coach brief on the home screen — top move for the day.
                val brief = runCatching {
                    val tasks = app.repository.wsTasksOnce()
                    com.todocompanion.app.domain.habit.HabitInsights.dailyBrief(
                        app.repository.wsHabitsOnce(), checkins, tasks, today, ZoneId.systemDefault()
                    )?.moves?.firstOrNull()?.let { "${it.emoji} ${it.text}" }
                }.getOrNull()

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_habitstats)
                    WidgetStyle.applyListCard(views, R.id.hs_card, context, id)

                    val edge = WidgetBitmaps.dp(context, 132f).toInt()
                    val stroke = WidgetBitmaps.dp(context, 11f)
                    val fill = if (due > 0 && done >= due) style.success else style.teal
                    views.setImageViewBitmap(R.id.hs_ring, WidgetBitmaps.ring(edge, stroke, progress, style.chip, fill))

                    views.setTextViewText(R.id.hs_done, if (due == 0) "—" else "$done/$due")
                    views.setTextColor(R.id.hs_done, style.textPrimary)
                    views.setTextViewText(R.id.hs_pct, if (due == 0) "no habits due" else "${(progress * 100).roundToInt()}%")
                    views.setTextColor(R.id.hs_pct, style.textSecondary)

                    val counts = buildString {
                        append("✓ $done")
                        if (left > 0) append("  ·  $left left")
                        if (skipped > 0) append("  ·  $skipped skipped")
                        if (bestStreak >= 2) append("  ·  🔥 $bestStreak")
                    }
                    views.setTextViewText(R.id.hs_counts, if (due == 0) "" else counts)
                    views.setTextColor(R.id.hs_counts, style.textSecondary)

                    views.setTextViewText(R.id.hs_brief, brief ?: "")
                    views.setTextColor(R.id.hs_brief, style.textPrimary)
                    views.setViewVisibility(R.id.hs_brief, if (brief.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE)

                    views.setOnClickPendingIntent(R.id.hs_root, openHabits(context))
                    manager.updateAppWidget(id, views)
                }
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
            val ids = m.getAppWidgetIds(ComponentName(context, HabitStatsWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, HabitStatsWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
