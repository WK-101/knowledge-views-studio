package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.habit.HabitInsights
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.domain.habit.Insight
import com.todocompanion.app.domain.habit.InsightAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * R106 — one Habit Insight widget with a mode picker, folding four former single-purpose widgets
 * (Keystone, Streaks, Habit Strength, plain-language Correlation) into a single entry in the widget
 * picker. Each placed instance chooses its lens in config; all four lenses read the same on-device
 * habit analysis a standalone tracker structurally can't compute. Fully offline — no network, no LLM.
 */
class HabitInsightWidget : BaseWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone).toEpochDay()
                val habits = app.repository.wsHabitsOnce()
                val checkins = app.repository.getHabitCheckinsOnce()
                val tasks = app.repository.wsTasksOnce()
                val insights = runCatching { HabitInsights.compute(habits, checkins, tasks, today, zone, max = 8) }
                    .getOrDefault(emptyList())

                // Computed once, only if a keystone-mode instance actually needs them.
                val ksInsight by lazy(LazyThreadSafetyMode.NONE) { insights.firstOrNull { it.emoji == "🗝️" } }
                val autoHabit by lazy(LazyThreadSafetyMode.NONE) {
                    val ksId = (ksInsight?.action as? InsightAction.Open)?.habitId
                        ?: habits.filter { !it.archived && !it.paused && it.habitType != "break" }
                            .maxByOrNull { HabitStats.streakFor(it, checkins, today) }?.id
                    habits.firstOrNull { it.id == ksId }
                }

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    when (WidgetPrefs.insightMode(context, id)) {
                        "streaks" -> renderStreaks(context, manager, id, style, habits, checkins, today)
                        "strength" -> renderStrength(context, manager, id, style, app, today)
                        "correlation" -> renderCorrelation(context, manager, id, style, insights)
                        else -> renderKeystone(context, manager, id, style, habits, checkins, ksInsight, autoHabit, today)
                    }
                }
            } finally { pending.finish() }
        }
    }

    // ---- Keystone lens: the one habit that best predicts a good day, with a check-off. ----
    private fun renderKeystone(
        context: Context, manager: AppWidgetManager, id: Int, style: WidgetStyle,
        habits: List<HabitEntity>, checkins: List<HabitCheckinEntity>,
        ksInsight: Insight?, autoHabit: HabitEntity?, today: Long,
    ) {
        // A config-pinned habit overrides the auto keystone for this specific widget.
        val habit = WidgetPrefs.habitId(context, id)?.let { p -> habits.firstOrNull { it.id == p } } ?: autoHabit
        val doneToday = habit != null &&
            (checkins.firstOrNull { it.habitId == habit.id && it.epochDay == today }?.let { HabitStats.meetsGoal(habit, it.count) } == true)
        val views = RemoteViews(context.packageName, R.layout.widget_keystone)
        WidgetStyle.applyListCard(views, R.id.ks_card, context, id)
        views.setTextColor(R.id.ks_label, style.accentText)
        views.setTextColor(R.id.ks_name, style.textPrimary)
        views.setTextColor(R.id.ks_body, style.textSecondary)
        if (habit == null) {
            views.setTextViewText(R.id.ks_name, "Add a couple of habits")
            views.setTextViewText(R.id.ks_body, "Your keystone — the one that best predicts a good day — appears here as you log.")
            views.setImageViewBitmap(R.id.ks_check, WidgetBitmaps.checkCircle((WidgetBitmaps.dp(context, 30f) * 2f).toInt(), style.chip, false))
            views.setOnClickPendingIntent(R.id.ks_root, openHabits(context, "open_habit_add"))
        } else {
            views.setTextViewText(R.id.ks_name, (habit.emoji?.plus(" ") ?: "") + habit.name)
            // The keystone verdict text only applies to the auto-picked keystone, not a pinned override.
            views.setTextViewText(R.id.ks_body, (if (habit.id == autoHabit?.id) ksInsight?.text else null)
                ?: "Start your day with this one — it sets the tone for the rest.")
            val color = habit.colorArgb?.toInt() ?: style.accent
            views.setImageViewBitmap(R.id.ks_check, WidgetBitmaps.checkCircle((WidgetBitmaps.dp(context, 30f) * 2f).toInt(), color, doneToday))
            views.setOnClickPendingIntent(R.id.ks_check, checkIntent(context, habit.id))
            views.setOnClickPendingIntent(R.id.ks_root, openHabits(context, "open_habits"))
        }
        manager.updateAppWidget(id, views)
    }

    // ---- Streaks lens: live streaks, longest first — the "don't break the chain" leaderboard. ----
    private fun renderStreaks(
        context: Context, manager: AppWidgetManager, id: Int, style: WidgetStyle,
        allHabits: List<HabitEntity>, checkins: List<HabitCheckinEntity>, today: Long,
    ) {
        val habits = allHabits.filter { !it.archived && !it.paused }
        val byHabit = checkins.groupBy { it.habitId }
        data class Row(val name: String, val streak: Int, val quit: Boolean)
        val rows = habits.mapNotNull { h ->
            val s = HabitStats.streakFor(h, byHabit[h.id] ?: emptyList(), today)
            if (s >= 1) Row((h.emoji?.plus(" ") ?: "") + h.name, s, h.habitType == "break") else null
        }.sortedByDescending { it.streak }.take(6)

        val views = RemoteViews(context.packageName, R.layout.widget_streaks)
        WidgetStyle.applyListCard(views, R.id.st_card, context, id)
        views.setTextColor(R.id.st_title, style.textPrimary)
        val rowIds = intArrayOf(R.id.st_r0, R.id.st_r1, R.id.st_r2, R.id.st_r3, R.id.st_r4, R.id.st_r5)
        for (i in rowIds.indices) {
            val r = rows.getOrNull(i)
            if (r == null) {
                views.setViewVisibility(rowIds[i], if (i == 0 && rows.isEmpty()) View.VISIBLE else View.GONE)
                if (i == 0 && rows.isEmpty()) {
                    views.setTextViewText(rowIds[0], "No active streaks yet — check one off today.")
                    views.setTextColor(rowIds[0], style.textSecondary)
                }
            } else {
                views.setViewVisibility(rowIds[i], View.VISIBLE)
                val flame = if (r.quit) "🧊" else "🔥"
                val unit = if (r.streak == 1) "day" else "days"
                views.setTextViewText(rowIds[i], "$flame ${r.streak} $unit   ${r.name}")
                views.setTextColor(rowIds[i], style.textPrimary)
            }
        }
        views.setOnClickPendingIntent(R.id.st_root, openHabits(context, "open_habits"))
        manager.updateAppWidget(id, views)
    }

    // ---- Strength lens: a trailing-28-day completion trend line over ~6 months. ----
    private fun renderStrength(
        context: Context, manager: AppWidgetManager, id: Int, style: WidgetStyle, app: App, today: Long,
    ) {
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
        views.setOnClickPendingIntent(R.id.hl_root, openHabits(context, "open_habits"))
        manager.updateAppWidget(id, views)
    }

    // ---- Correlation lens: a plain-language cross-module pattern the coach found. ----
    private fun renderCorrelation(
        context: Context, manager: AppWidgetManager, id: Int, style: WidgetStyle, insights: List<Insight>,
    ) {
        val corr = insights.firstOrNull { it.emoji in setOf("🔗", "⚡", "📉", "🗝️") }
        val views = RemoteViews(context.packageName, R.layout.widget_correlation)
        WidgetStyle.applyListCard(views, R.id.co_card, context, id)
        views.setTextColor(R.id.co_label, style.accentText)
        views.setTextColor(R.id.co_body, style.textPrimary)
        views.setTextViewText(R.id.co_emoji, corr?.emoji ?: "🔗")
        views.setTextViewText(R.id.co_body, corr?.text
            ?: "Keep logging habits and tasks — the links between them (“days you meditate, +N% tasks”) appear here.")
        views.setOnClickPendingIntent(R.id.co_root, openHabits(context, "open_habits"))
        manager.updateAppWidget(id, views)
    }

    private fun checkIntent(context: Context, habitId: String): PendingIntent {
        val i = Intent(context, HabitCheckReceiver::class.java).setAction(HabitCheckReceiver.ACTION_CHECK)
            .putExtra(HabitCheckReceiver.EXTRA_HABIT_ID, habitId)
        return PendingIntent.getBroadcast(context, ("hi:$habitId").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openHabits(context: Context, action: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun updateOne(context: Context, id: Int) {
            context.sendBroadcast(Intent(context, HabitInsightWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
            })
        }

        fun refresh(context: Context) = Widgets.broadcastUpdate(context, HabitInsightWidget::class.java)
    }
}
