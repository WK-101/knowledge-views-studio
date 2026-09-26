package com.wkhan.hexis.widget

import com.wkhan.hexis.App
import com.wkhan.hexis.domain.habit.HabitStats
import kotlinx.coroutines.runBlocking

/**
 * Shared, read-only aggregation for the drawing widgets (grid, strength line, and the moat widgets
 * later). It turns the raw habit + check-in tables into a per-day "how much of what was due did I
 * do" series that both a contribution heatmap and a trend line can read. Offline; one DB read.
 */
object HabitWidgetData {
    /** due[i] / done[i] across all active build-habits for the day (fromDay + i). */
    class Daily(val fromDay: Long, val toDay: Long, val due: IntArray, val done: IntArray) {
        val size get() = due.size
        fun intensity(i: Int): Float = if (i in due.indices && due[i] > 0) done[i].toFloat() / due[i] else 0f
        /** Completion ratio over the trailing [window] days ending at index [endIdx] (0..1). */
        fun trailingRatio(endIdx: Int, window: Int): Float {
            var d = 0; var n = 0
            var i = (endIdx - window + 1).coerceAtLeast(0)
            while (i <= endIdx && i < due.size) { n += due[i]; d += done[i]; i++ }
            return if (n > 0) d.toFloat() / n else 0f
        }
    }

    fun dailyCompletion(app: App, fromDay: Long, toDay: Long): Daily {
        val n = (toDay - fromDay + 1).toInt().coerceIn(1, 400)
        val due = IntArray(n); val done = IntArray(n)
        val habits = runBlocking { app.repository.wsHabitsOnce() }
            .filter { !it.archived && !it.paused && it.habitType != "break" }
        val checkins = runBlocking { app.repository.getHabitCheckinsOnce() }
        val byHabit = checkins.groupBy { it.habitId }
        habits.forEach { h ->
            val hc = byHabit[h.id] ?: emptyList()
            val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }
                .map { it.epochDay }.toHashSet()
            val start = runCatching { h.startEpochDay() }.getOrDefault(fromDay)
            var d = maxOf(fromDay, start)
            while (d <= toDay) {
                if (HabitStats.isExpectedDay(h, d)) {
                    val i = (d - fromDay).toInt()
                    if (i in 0 until n) { due[i]++; if (d in doneDays) done[i]++ }
                }
                d++
            }
        }
        return Daily(fromDay, toDay, due, done)
    }

    /** Monday-based weekday (0=Mon … 6=Sun) for an epoch day. 1970-01-01 was a Thursday. */
    fun weekdayMon0(epochDay: Long): Int = (((epochDay % 7).toInt() + 3) % 7 + 7) % 7
}
