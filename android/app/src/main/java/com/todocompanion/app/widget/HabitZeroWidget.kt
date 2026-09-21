package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.domain.habit.HabitInsights
import com.todocompanion.app.domain.habit.HabitStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * Habit Zero — the "vanishing" widget. It shows only the habits still due today; each one you finish
 * leaves the list, the meter ring climbs, and when the last clears, the empty state celebrates with an
 * on-device cross-module reason. Fully offline, no new permission — every tap re-renders (RemoteViews),
 * so the shrink + meter growth need no animation or live timer.
 *
 * Per-type tap ([HabitZeroReceiver]): a yes/no habit checks off in place; a numeric one opens the
 * amount-entry popup (so "8,000 steps" is typed, never blindly +1'd); a timed one opens its timer.
 */
class HabitZeroWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        render(context, manager, intArrayOf(id))

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone).toEpochDay()
                val r = HabitZeroData.compute(app, today)
                // The Zero reward: only when everything due is resolved. Prefer a keystone/correlation from
                // the on-device engine — the "reason" no single-purpose tracker can give — else the streak.
                val reward = if (r.remaining.isEmpty() && r.due > 0) runCatching {
                    val habits = app.repository.wsHabitsOnce()
                    val checkins = app.repository.getHabitCheckinsOnce()
                    val tasks = app.repository.wsTasksOnce()
                    HabitInsights.compute(habits, checkins, tasks, today, zone, 6)
                        .firstOrNull { it.emoji == "🗝️" || it.emoji == "🔗" || it.emoji == "⚡" || it.emoji == "📉" }
                        ?.text
                        ?: if (r.bestStreak >= 2) "🔥 ${r.bestStreak}-day streak — don't break the chain." else null
                }.getOrNull() else null

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_habitzero)
                    WidgetStyle.applyListCard(views, R.id.hz_card, context, id)

                    val edge = WidgetBitmaps.dp(context, 52f).toInt()
                    val stroke = WidgetBitmaps.dp(context, 6.5f)
                    val progress = if (r.due > 0) r.done.toFloat() / r.due else 0f
                    val fill = if (r.due > 0 && r.done >= r.due) style.success else style.accent
                    views.setImageViewBitmap(R.id.hz_ring, WidgetBitmaps.ring(edge, stroke, progress, style.chip, fill))

                    val left = r.remaining.size
                    views.setTextViewText(R.id.hz_left, when {
                        r.due == 0 -> "No habits due"
                        left == 0 -> "All done ✓"
                        else -> "$left to go"
                    })
                    views.setTextColor(R.id.hz_left, style.textPrimary)
                    val sub = buildString {
                        if (r.due > 0) append("${r.done} of ${r.due} done")
                        if (r.skipped > 0) append(" · ${r.skipped} skipped")
                        if (r.bestStreak >= 2) append(" · 🔥${r.bestStreak}")
                    }
                    views.setTextViewText(R.id.hz_count, sub)
                    views.setTextColor(R.id.hz_count, style.textSecondary)

                    // The vanishing list of remaining habits.
                    val svc = Intent(context, HabitZeroService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                    }
                    views.setRemoteAdapter(R.id.hz_list, svc)
                    views.setEmptyView(R.id.hz_list, R.id.hz_empty)
                    views.setPendingIntentTemplate(R.id.hz_list, tapTemplate(context))

                    views.setTextViewText(R.id.hz_empty, when {
                        r.due == 0 -> "No habits scheduled today."
                        else -> "🎉  All wrapped for today" + (reward?.let { "\n$it" } ?: "")
                    })
                    views.setTextColor(R.id.hz_empty, style.textPrimary)

                    views.setOnClickPendingIntent(R.id.hz_header, openHabits(context))
                    manager.updateAppWidget(id, views)
                    manager.notifyAppWidgetViewDataChanged(id, R.id.hz_list)
                }
            } finally { pending.finish() }
        }
    }

    private fun tapTemplate(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0x2E20,
            Intent(context, HabitZeroReceiver::class.java).setAction(HabitZeroReceiver.ACTION_TAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun openHabits(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_habits")
        }
        return PendingIntent.getActivity(context, 0x2E21, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, HabitZeroWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, HabitZeroWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
            m.notifyAppWidgetViewDataChanged(ids, R.id.hz_list)
        }
        fun updateOne(context: Context, id: Int) {
            val m = AppWidgetManager.getInstance(context) ?: return
            HabitZeroWidget().render(context, m, intArrayOf(id))
        }
    }
}

/** Shared "what's still due today" computation, used by both the provider (for the meter) and the list
 *  factory (for the rows), so the count and the list can never disagree. */
object HabitZeroData {
    data class Rem(val id: String, val emoji: String, val name: String, val meta: String, val kind: String)
    data class Result(val due: Int, val done: Int, val skipped: Int, val bestStreak: Int, val remaining: List<Rem>)

    fun compute(app: App, today: Long): Result {
        val habits = runBlocking { app.repository.wsHabitsOnce() }.filter { !it.archived && !it.paused }
        val checkins = runBlocking { app.repository.getHabitCheckinsOnce() }
        var due = 0; var done = 0; var skipped = 0; var best = 0
        val rem = ArrayList<Rem>()
        habits.forEach { h ->
            val hc = checkins.filter { it.habitId == h.id }
            val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skipDays = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val relapse = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            val todayCount = hc.firstOrNull { it.epochDay == today }?.count ?: 0
            best = maxOf(best, HabitStats.currentStreak(h, doneDays, skipDays, relapse, today))
            // Break/quit habits are never a "to-do" — success is passive, resolved only at midnight — so they
            // stay out of the vanishing list and the meter entirely.
            val scheduled = h.habitType != "break" &&
                (HabitStats.isExpectedDay(h, today) || h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH)
            if (!scheduled) return@forEach
            due++
            val stillDue = HabitStats.dueToday(h, today, doneDays, todayCount)
            when {
                !stillDue -> done++
                today in skipDays -> skipped++
                else -> {
                    val timed = h.unit?.startsWith("min") == true
                    val numeric = !timed && (h.targetPerDay > 1 || h.unit != null || h.clickIncrement > 1)
                    val kind = if (timed) "timed" else if (numeric) "numeric" else "build"
                    val meta = when {
                        timed -> "▶ $todayCount/${h.targetPerDay} min"
                        numeric -> "⌨ $todayCount/${h.targetPerDay}" + (h.unit?.let { " $it" } ?: "")
                        else -> "○"
                    }
                    rem += Rem(h.id, h.emoji ?: "", h.name, meta, kind)
                }
            }
        }
        return Result(due, done, skipped, best, rem)
    }
}

class HabitZeroService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = HabitZeroFactory(applicationContext)
}

private class HabitZeroFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<HabitZeroData.Rem> = emptyList()
    private val style by lazy { WidgetStyle.resolve(context, -1) }
    override fun onCreate() {}
    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        rows = runCatching { HabitZeroData.compute(app, today).remaining }.getOrDefault(emptyList())
    }
    override fun onDestroy() { rows = emptyList() }
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewAt(position: Int): RemoteViews {
        val r = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_habitzero_item)
        return RemoteViews(context.packageName, R.layout.widget_habitzero_item).apply {
            setTextViewText(R.id.hz_i_emoji, r.emoji.ifBlank { "•" })
            setTextViewText(R.id.hz_i_name, r.name)
            setTextColor(R.id.hz_i_name, style.textPrimary)
            setTextViewText(R.id.hz_i_meta, r.meta)
            setTextColor(R.id.hz_i_meta, style.textSecondary)
            setOnClickFillInIntent(R.id.hz_i_root, Intent().putExtra(HabitZeroReceiver.EXTRA_HABIT_ID, r.id))
        }
    }
}

/** Dispatches a tap on a remaining habit by type: build → check off in place; numeric → amount popup;
 *  timed → the timer. Only the build case writes here (so it vanishes instantly); the others open the app. */
class HabitZeroReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val h = app.repository.getHabitsOnce().firstOrNull { it.id == habitId } ?: return@launch
                val timed = h.habitType != "break" && h.unit?.startsWith("min") == true
                val numeric = !timed && h.habitType != "break" && (h.targetPerDay > 1 || h.unit != null || h.clickIncrement > 1)
                when {
                    timed -> context.startActivity(route(context, "focus_habit:$habitId"))
                    numeric -> context.startActivity(route(context, "habit_value:$habitId"))
                    else -> {
                        val cur = app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == habitId && it.epochDay == today }?.count ?: 0
                        app.repository.cycleCheckin(habitId, today, h.targetPerDay, cur)
                        Widgets.refreshHabitWidgets(context)
                    }
                }
            } finally { pending.finish() }
        }
    }
    private fun route(context: Context, action: String) = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_ACTION, action)
    }
    companion object {
        const val ACTION_TAP = "com.todocompanion.app.action.HABIT_ZERO_TAP"
        const val EXTRA_HABIT_ID = "habitId"
    }
}
