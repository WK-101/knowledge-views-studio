package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
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

                ids.forEach { id ->
                    // Per-widget: each Habit Zero can scope to one habit group (config) — compute inside the loop.
                    val group = WidgetPrefs.group(context, id)
                    val r = HabitZeroData.compute(app, today, group)
                    val style = WidgetStyle.resolve(context, id)
                    // Size-responsive: pick the density from the placed cell size. The list stays for tall
                    // widgets; smaller sizes collapse to a dot grid, a strip, or just the meter.
                    val opts = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                    val minW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                    val minH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
                    when (pickBucket(minW, minH)) {
                        Bucket.METER -> renderMeter(context, manager, id, r, style)
                        Bucket.STRIP -> renderStrip(context, manager, id, r, style)
                        Bucket.GRID -> renderGrid(context, manager, id, r, style)
                        Bucket.LIST -> renderList(context, manager, id, r, style, app, today, zone)
                    }
                }
            } finally { pending.finish() }
        }
    }

    /** The compact densities, chosen from the placed cell size (dp, from AppWidgetOptions). */
    private enum class Bucket { METER, STRIP, GRID, LIST }

    private fun pickBucket(minW: Int, minH: Int): Bucket = when {
        minW == 0 || minH == 0 -> Bucket.LIST   // options not reported yet → safe default
        minW < 110 -> Bucket.METER              // one cell wide → just the donut (any height)
        minH < 100 -> Bucket.STRIP              // wide & short → meter + a row of dots
        minH < 170 -> Bucket.GRID               // wide & medium → the dot grid
        else -> Bucket.LIST                     // wide & tall → the scrolling list
    }

    /** The full "vanishing list" density (unchanged behaviour) for tall widgets. */
    private suspend fun renderList(
        context: Context, manager: AppWidgetManager, id: Int,
        r: HabitZeroData.Result, style: WidgetStyle, app: App, today: Long, zone: ZoneId,
    ) {
        // The Zero reward: only when everything due is resolved. Prefer a keystone/correlation from the
        // on-device engine — the "reason" no single-purpose tracker can give — else the streak.
        val reward = if (r.remaining.isEmpty() && r.due > 0) runCatching {
            val habits = app.repository.wsHabitsOnce()
            val checkins = app.repository.getHabitCheckinsOnce()
            val tasks = app.repository.wsTasksOnce()
            HabitInsights.compute(habits, checkins, tasks, today, zone, 6)
                .firstOrNull { it.emoji == "🗝️" || it.emoji == "🔗" || it.emoji == "⚡" || it.emoji == "📉" }
                ?.text
                ?: if (r.bestStreak >= 2) "🔥 ${r.bestStreak}-day streak — don't break the chain." else null
        }.getOrNull() else null
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

    /** 1×1 "meter": just the whole-habits donut with the remaining count in the middle. */
    private fun renderMeter(context: Context, manager: AppWidgetManager, id: Int, r: HabitZeroData.Result, style: WidgetStyle) {
        val views = RemoteViews(context.packageName, R.layout.widget_habitzero_meter)
        WidgetStyle.applyListCard(views, R.id.hz_card, context, id)
        val edge = WidgetBitmaps.dp(context, 80f).toInt()
        val stroke = WidgetBitmaps.dp(context, 8f)
        val progress = if (r.due > 0) r.done.toFloat() / r.due else 0f
        val fill = if (r.due > 0 && r.done >= r.due) style.success else style.accent
        views.setImageViewBitmap(R.id.hzm_ring, WidgetBitmaps.ring(edge, stroke, progress, style.chip, fill))
        val left = r.remaining.size
        views.setTextViewText(R.id.hzm_count, if (r.due == 0) "–" else if (left == 0) "✓" else "$left")
        views.setTextColor(R.id.hzm_count, if (left == 0 && r.due > 0) style.success else style.textPrimary)
        views.setTextViewText(R.id.hzm_sub, when {
            r.due == 0 -> "none"
            left == 0 -> "done"
            else -> "left"
        })
        views.setTextColor(R.id.hzm_sub, style.textSecondary)
        views.setOnClickPendingIntent(R.id.hz_card, openHabits(context))
        manager.updateAppWidget(id, views)
    }

    /** 2×1 "strip": the meter, then the remaining habits as a row of tappable dots. */
    private fun renderStrip(context: Context, manager: AppWidgetManager, id: Int, r: HabitZeroData.Result, style: WidgetStyle) {
        val views = RemoteViews(context.packageName, R.layout.widget_habitzero_strip)
        WidgetStyle.applyListCard(views, R.id.hz_card, context, id)
        val edge = WidgetBitmaps.dp(context, 52f).toInt()
        val stroke = WidgetBitmaps.dp(context, 6f)
        val progress = if (r.due > 0) r.done.toFloat() / r.due else 0f
        val fill = if (r.due > 0 && r.done >= r.due) style.success else style.accent
        views.setImageViewBitmap(R.id.hzs_ring, WidgetBitmaps.ring(edge, stroke, progress, style.chip, fill))
        val left = r.remaining.size
        views.setTextViewText(R.id.hzs_count, if (r.due == 0) "–" else if (left == 0) "✓" else "$left")
        views.setTextColor(R.id.hzs_count, if (left == 0 && r.due > 0) style.success else style.textPrimary)
        val slots = intArrayOf(R.id.hzs_d0, R.id.hzs_d1, R.id.hzs_d2, R.id.hzs_d3, R.id.hzs_d4)
        fillDots(context, views, id, slots, r.remaining, style, WidgetBitmaps.dp(context, 46f).toInt())
        views.setOnClickPendingIntent(R.id.hz_card, openHabits(context))
        manager.updateAppWidget(id, views)
    }

    /** 2×2 "grid": the remaining habits as a dot grid, with a small meter cell in the last slot. */
    private fun renderGrid(context: Context, manager: AppWidgetManager, id: Int, r: HabitZeroData.Result, style: WidgetStyle) {
        val views = RemoteViews(context.packageName, R.layout.widget_habitzero_grid)
        WidgetStyle.applyListCard(views, R.id.hz_card, context, id)
        val edge = WidgetBitmaps.dp(context, 44f).toInt()
        val stroke = WidgetBitmaps.dp(context, 5f)
        val progress = if (r.due > 0) r.done.toFloat() / r.due else 0f
        val fill = if (r.due > 0 && r.done >= r.due) style.success else style.accent
        views.setImageViewBitmap(R.id.hzg_ring, WidgetBitmaps.ring(edge, stroke, progress, style.chip, fill))
        val left = r.remaining.size
        views.setTextViewText(R.id.hzg_count, if (r.due == 0) "–" else if (left == 0) "✓" else "$left")
        views.setTextColor(R.id.hzg_count, if (left == 0 && r.due > 0) style.success else style.textPrimary)
        val slots = intArrayOf(R.id.hzg_0, R.id.hzg_1, R.id.hzg_2, R.id.hzg_3, R.id.hzg_4, R.id.hzg_5, R.id.hzg_6, R.id.hzg_7)
        fillDots(context, views, id, slots, r.remaining, style, edge)
        views.setOnClickPendingIntent(R.id.hz_card, openHabits(context))
        manager.updateAppWidget(id, views)
    }

    /**
     * Fill the fixed dot slots from the remaining habits: each dot is the habit's emoji on its colour
     * (a progress ring for numeric / timed), tapped straight to [HabitZeroReceiver]. Extra slots go
     * INVISIBLE (not GONE) so the weighted grid stays even; a "+N" dot absorbs any overflow.
     */
    private fun fillDots(
        context: Context, views: RemoteViews, widgetId: Int,
        slots: IntArray, rem: List<HabitZeroData.Rem>, style: WidgetStyle, edgePx: Int,
    ) {
        val n = slots.size
        val overflow = rem.size > n
        val shown = if (overflow) n - 1 else minOf(rem.size, n)
        for (i in slots.indices) {
            val slot = slots[i]
            when {
                i < shown -> {
                    val h = rem[i]
                    val disc = h.color?.toInt() ?: style.accent
                    val ring = if (h.kind == "build") null else style.accent
                    views.setImageViewBitmap(slot, WidgetBitmaps.habitDot(edgePx, disc, h.emoji.ifBlank { "•" }, ring, style.chip, h.progress))
                    views.setViewVisibility(slot, View.VISIBLE)
                    views.setOnClickPendingIntent(slot, dotTapPI(context, widgetId, h.id))
                }
                overflow && i == n - 1 -> {
                    val extra = rem.size - shown
                    views.setImageViewBitmap(slot, WidgetBitmaps.habitDot(edgePx, style.accent, "+$extra"))
                    views.setViewVisibility(slot, View.VISIBLE)
                    views.setOnClickPendingIntent(slot, openHabits(context))
                }
                else -> views.setViewVisibility(slot, View.INVISIBLE)
            }
        }
    }

    /** A per-dot tap → [HabitZeroReceiver]. Unique requestCode per (widget, habit): PendingIntent equality
     *  ignores extras, so the habit id must ride the requestCode or every dot would share one intent. */
    private fun dotTapPI(context: Context, widgetId: Int, habitId: String): PendingIntent {
        val i = Intent(context, HabitZeroReceiver::class.java)
            .setAction(HabitZeroReceiver.ACTION_TAP)
            .putExtra(HabitZeroReceiver.EXTRA_HABIT_ID, habitId)
        return PendingIntent.getBroadcast(
            context, ("hz:$widgetId:$habitId").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
    data class Rem(val id: String, val emoji: String, val name: String, val meta: String, val kind: String,
                   val progress: Float = 0f, val color: Long? = null)
    data class Result(val due: Int, val done: Int, val skipped: Int, val bestStreak: Int, val remaining: List<Rem>)

    fun compute(app: App, today: Long, group: String = ""): Result {
        val habits = runBlocking { app.repository.wsHabitsOnce() }
            .filter { !it.archived && !it.paused }
            .filter { group.isBlank() || it.category.trim() == group.trim() }
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
                    // Ring progress for the compact dots: how far today's count is toward the target.
                    val prog = if ((timed || numeric) && h.targetPerDay > 0) (todayCount.toFloat() / h.targetPerDay).coerceIn(0f, 1f) else 0f
                    rem += Rem(h.id, h.emoji ?: "", h.name, meta, kind, prog, h.colorArgb)
                }
            }
        }
        return Result(due, done, skipped, best, rem)
    }
}

class HabitZeroService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        HabitZeroFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
}

private class HabitZeroFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<HabitZeroData.Rem> = emptyList()
    private val style by lazy { WidgetStyle.resolve(context, widgetId) }
    override fun onCreate() {}
    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val group = if (widgetId >= 0) WidgetPrefs.group(context, widgetId) else ""
        rows = runCatching { HabitZeroData.compute(app, today, group).remaining }.getOrDefault(emptyList())
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

/** Dispatches a tap on a remaining habit by type: a yes/no habit checks off in place (so it vanishes
 *  instantly and the meter climbs); a numeric or timed habit opens the lightweight [HabitQuickLogActivity]
 *  popup — the amount entry floats straight over the launcher, the whole app never opens. */
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
                val timed = h.unit?.startsWith("min") == true
                val numeric = !timed && (h.targetPerDay > 1 || h.unit != null || h.clickIncrement > 1)
                if (timed || numeric) {
                    context.startActivity(HabitQuickLogActivity.intent(context, habitId))
                } else {
                    val cur = app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == habitId && it.epochDay == today }?.count ?: 0
                    app.repository.cycleCheckin(habitId, today, h.targetPerDay, cur)
                    Widgets.refreshHabitWidgets(context)
                }
            } finally { pending.finish() }
        }
    }
    companion object {
        const val ACTION_TAP = "com.todocompanion.app.action.HABIT_ZERO_TAP"
        const val EXTRA_HABIT_ID = "habitId"
    }
}
