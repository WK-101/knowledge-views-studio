package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.domain.habit.HabitStats
import java.time.Instant
import java.time.LocalDate
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
        val len = (endDay - startDay + 1).coerceAtLeast(1)
        val days = (startDay..endDay).toSet()
        val prevDays = ((startDay - len)..(startDay - 1)).toSet()

        fun tasksIn(d: Set<Long>) = ctx.tasks.count { t ->
            t.completedAt?.let { Instant.ofEpochMilli(it).atZone(ctx.zone).toLocalDate().toEpochDay() in d } == true
        }
        fun checkinsIn(d: Set<Long>) = ctx.checkins.count { c ->
            c.epochDay in d && c.status == "done" &&
                ctx.habits.firstOrNull { it.id == c.habitId }?.let { HabitStats.meetsGoal(it, c.count) } == true
        }
        fun focusIn(d: Set<Long>) = ctx.focus.filter { it.epochDay in d }.sumOf { it.minutes }
        fun millisOf(d0: Long, d1: Long): Pair<Long, Long> {
            val s = LocalDate.ofEpochDay(d0).atStartOfDay(ctx.zone).toInstant().toEpochMilli()
            val e = LocalDate.ofEpochDay(d1 + 1).atStartOfDay(ctx.zone).toInstant().toEpochMilli()
            return s to e
        }

        val tNow = tasksIn(days); val tPrev = tasksIn(prevDays)
        val cNow = checkinsIn(days); val cPrev = checkinsIn(prevDays)
        val fNow = focusIn(days); val fPrev = focusIn(prevDays)

        val (winStart, winEnd) = millisOf(startDay, endDay)
        val (pStart, pEnd) = millisOf(startDay - len, startDay - 1)
        val tracked = TimeTracking.totalMinutes(ctx.timeEntries, winStart, winEnd, ctx.now)
        val trackedPrev = TimeTracking.totalMinutes(ctx.timeEntries, pStart, pEnd, ctx.now)
        val byAct = TimeTracking.totalsByActivity(ctx.timeEntries, winStart, winEnd, ctx.now)
        val topAct = byAct.maxByOrNull { it.minutes }
        val topActName = topAct?.let { ta -> ctx.activities.firstOrNull { it.id == ta.activityId } }

        val best = habitStrengths(ctx.habits, ctx.checkins, endDay).maxByOrNull { it.second }

        // Track 1.2 — felt state over this window and the equally-long prior one, for the narrative + a felt line.
        val feltNow = FeltState.summarize(dayLogs, startDay, endDay)
        val feltPrev = FeltState.summarize(dayLogs, startDay - len, startDay - 1)

        val lines = buildList {
            add(Line("✓", "Tasks done", tNow.toString(), tNow - tPrev))
            if (ctx.habits.any { !it.archived }) add(Line("↻", "Habit check-ins", cNow.toString(), cNow - cPrev))
            if (fNow > 0 || fPrev > 0) add(Line("◇", "Focus", fmtHm(fNow), fNow - fPrev, "m"))
            if (tracked > 0 || trackedPrev > 0) add(Line("⧗", "Tracked", fmtHm(tracked), tracked - trackedPrev, "m"))
            if (topAct != null && topActName != null) add(Line((topActName.emoji ?: "★"), "Top activity", "${topActName.name} · ${fmtHm(topAct.minutes)}"))
            if (best != null) add(Line("🔥", "Strongest habit", "${best.first.name} · ${best.second}%"))
            // Felt lines — how the days felt, with a compact vs-before comparison baked into the value.
            if (feltNow.ratedDays > 0) {
                val cmp = ratingCompare(feltNow.avgRating, feltPrev.avgRating, feltPrev.ratedDays)
                add(Line("★", "Avg day rating", "${oneDp(feltNow.avgRating)}★$cmp"))
            }
            if (feltNow.moodDays > 0) add(Line("🌙", "Avg mood", oneDp(feltNow.avgMood)))
        }

        val hasData = tNow > 0 || cNow > 0 || fNow > 0 || tracked > 0 || feltNow.hasData
        val narrative = buildNarrative(tNow, tPrev, cNow, cPrev, tracked, trackedPrev, topActName?.name, best, hasData, feltNow, feltPrev)
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
