package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import java.util.Locale

/**
 * Ω5 — the any-period recap. Pick any date range and get the one cross-module story: what you
 * finished, tracked and kept, versus the equally-long window before it. Generalises the weekly digest
 * to an arbitrary span — the unified account only a store holding tasks, habits and time can write.
 *
 * Track 1.2 — the recap now also folds the day logs' felt state (via [FeltState]) so the narrative and a
 * felt line can say how the days actually *felt*, not just what got done — and how that compares to the
 * equally-long window before. The felt inputs are optional (default empty), so callers that don't pass
 * day logs get exactly the old task/habit/time recap.
 */
object PeriodRecap {
    data class Line(val icon: String, val label: String, val value: String, val delta: Int = 0, val deltaUnit: String = "")
    data class Recap(val title: String, val lines: List<Line>, val narrative: String, val hasData: Boolean)

    fun compute(startDay: Long, endDay: Long, title: String, ctx: OmegaContext, dayLogs: List<DayLogEntity> = emptyList()): Recap {
        // Track 1 — the recap now DERIVES from [ReviewRollup]: fold this window and the equally-long window before
        // it into two Rollups (which already carry the tasks / check-ins / focus / tracked / felt aggregates), then
        // build the recap from them. This is the same aggregation the Day-review roll-up and the digest read, so
        // the three surfaces cannot disagree, and the recap no longer re-folds the raw entities.
        val len = (endDay - startDay + 1).coerceAtLeast(1)
        val current = ReviewRollup.compute(
            startDay, endDay, dayLogs, emptyList(), ctx.habits, ctx.checkins,
            ctx.timeEntries, ctx.activities, ctx.zone, ctx.now, tasks = ctx.tasks, focusSessions = ctx.focus,
        )
        val prior = ReviewRollup.compute(
            startDay - len, startDay - 1, dayLogs, emptyList(), ctx.habits, ctx.checkins,
            ctx.timeEntries, ctx.activities, ctx.zone, ctx.now, tasks = ctx.tasks, focusSessions = ctx.focus,
        )
        return fromRollups(current, prior, title, ctx.habits, ctx.checkins)
    }

    /**
     * Build the recap straight from a [current] window Rollup and its equally-long [prior] window Rollup. The
     * period counts (tasks, check-ins, focus, tracked) and the felt state are read off the Rollups; only the
     * "strongest habit" (a full-history strength as of the window end, not a period aggregate) is derived from
     * [habits]/[checkins] here, since it is not something a period roll-up carries.
     */
    fun fromRollups(
        current: ReviewRollup.Rollup,
        prior: ReviewRollup.Rollup,
        title: String,
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
    ): Recap {
        val tNow = current.completedTasks; val tPrev = prior.completedTasks
        val cNow = current.checkinsMeetingGoal; val cPrev = prior.checkinsMeetingGoal
        val fNow = current.focusMinutes; val fPrev = prior.focusMinutes
        val tracked = current.trackedMinutes; val trackedPrev = prior.trackedMinutes
        // The most-tracked activity (Rollup keeps them sorted); "—" is the marker for a time entry whose activity
        // has been deleted, which the recap omits (as it always did).
        val topAct = current.topActivities.firstOrNull()?.takeIf { it.name != "—" }
        val best = habitStrengths(habits, checkins, current.endDay).maxByOrNull { it.second }
        val feltNow = current.felt
        val feltPrev = prior.felt

        val lines = buildList {
            add(Line("✓", "Tasks done", tNow.toString(), tNow - tPrev))
            if (habits.any { !it.archived }) add(Line("↻", "Habit check-ins", cNow.toString(), cNow - cPrev))
            if (fNow > 0 || fPrev > 0) add(Line("◇", "Focus", fmtHm(fNow), fNow - fPrev, "m"))
            if (tracked > 0 || trackedPrev > 0) add(Line("⧗", "Tracked", fmtHm(tracked), tracked - trackedPrev, "m"))
            if (topAct != null) add(Line((topAct.emoji ?: "★"), "Top activity", "${topAct.name} · ${fmtHm(topAct.minutes)}"))
            if (best != null) add(Line("🔥", "Strongest habit", "${best.first.name} · ${best.second}%"))
            // Felt lines — how the days felt, with a compact vs-before comparison baked into the value.
            if (feltNow.ratedDays > 0) {
                val cmp = ratingCompare(feltNow.avgRating, feltPrev.avgRating, feltPrev.ratedDays)
                add(Line("★", "Avg day rating", "${oneDp(feltNow.avgRating)}★$cmp"))
            }
            if (feltNow.moodDays > 0) add(Line("🌙", "Avg mood", oneDp(feltNow.avgMood)))
        }

        val hasData = tNow > 0 || cNow > 0 || fNow > 0 || tracked > 0 || feltNow.hasData
        val narrative = buildNarrative(tNow, tPrev, cNow, cPrev, tracked, trackedPrev, topAct?.name, best, hasData, feltNow, feltPrev)
        return Recap(title, lines, narrative, hasData)
    }

    /** " · up from 3.2" / " · down from 4.1" / "" — a compact prior-window comparison for the rating line. */
    private fun ratingCompare(now: Double, prev: Double, prevRatedDays: Int): String {
        if (prevRatedDays <= 0) return ""
        val d = now - prev
        return when {
            d >= 0.1 -> " · up from ${oneDp(prev)}"
            d <= -0.1 -> " · down from ${oneDp(prev)}"
            else -> ""
        }
    }

    private fun buildNarrative(
        tNow: Int, tPrev: Int, cNow: Int, cPrev: Int, tracked: Int, trackedPrev: Int,
        topActivity: String?, best: Pair<com.todocompanion.app.data.entity.HabitEntity, Int>?, hasData: Boolean,
        feltNow: FeltState.FeltSummary, feltPrev: FeltState.FeltSummary,
    ): String {
        if (!hasData) return "A quiet span — nothing logged yet. Finish one task or keep one habit and the story starts here."
        val parts = mutableListOf<String>()
        parts += "You finished $tNow task${if (tNow == 1) "" else "s"}" +
            when { tNow > tPrev -> " (up ${tNow - tPrev})"; tNow < tPrev -> " (down ${tPrev - tNow})"; else -> "" } + "."
        if (cNow > 0) parts += "kept $cNow habit check-in${if (cNow == 1) "" else "s"}" +
            (if (cNow > cPrev) " — more than the window before" else "") + "."
        if (tracked > 0) {
            parts += "tracked ${fmtHm(tracked)}" + (topActivity?.let { ", mostly on $it" } ?: "") + "."
            if (trackedPrev > 0 && tracked > trackedPrev) parts += "That's ${fmtHm(tracked - trackedPrev)} more than before."
        }
        best?.let { if (it.second >= 60) parts += "“${it.first.name}” is holding strong at ${it.second}%." }
        // The felt clause — how the days felt, and how that moved vs the window before.
        if (feltNow.ratedDays > 0) {
            val move = if (feltPrev.ratedDays > 0) {
                val d = feltNow.avgRating - feltPrev.avgRating
                when { d >= 0.1 -> ", up from ${oneDp(feltPrev.avgRating)}★"; d <= -0.1 -> ", down from ${oneDp(feltPrev.avgRating)}★"; else -> "" }
            } else ""
            parts += "And it showed: your days averaged ${oneDp(feltNow.avgRating)}★$move."
        }
        if (feltNow.dominantEmotion.isNotBlank() && feltNow.dominantEmotionCount >= 2) {
            parts += "Most often you felt ${feltNow.dominantEmotion.lowercase(Locale.getDefault())}."
        }
        return parts.joinToString(" ")
    }

    private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)
}
