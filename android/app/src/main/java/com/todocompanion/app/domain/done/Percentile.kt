package com.todocompanion.app.domain.done

import java.time.LocalDate

/**
 * Frontier F4 — a private percentile against your OWN past. No leaderboard, no other people: the only
 * benchmark is your own history, already on the device. Answers "was that a big one for me?" — top-effort
 * finishes, and standout weeks — from the record alone.
 */
object Percentile {
    /** A short rank for one finished item by effort, versus every finish you've ever logged with a duration.
     *  Only surfaces when it's genuinely notable (top quartile) so it stays meaningful. */
    fun effortRank(a: Accomplishment, all: List<Accomplishment>): String? {
        if (!a.isTaskLike || a.durationMin <= 0) return null
        val efforts = all.filter { it.isTaskLike && it.durationMin > 0 }.map { it.durationMin }
        if (efforts.size < 5) return null
        val below = efforts.count { it < a.durationMin }
        val pct = (100 - (below * 100 / efforts.size)).coerceIn(1, 100)
        return if (pct <= 25) "Top $pct% effort you've logged" else null
    }

    /** Is the current week your best in a while (by finishes)? Phrased as "best since <month year>". */
    fun bestWeekSince(all: List<Accomplishment>, today: LocalDate = LocalDate.now()): String? {
        if (all.isEmpty()) return null
        val byWeekStart = HashMap<Long, Int>()
        all.forEach {
            val d = LocalDate.ofEpochDay(it.epochDay)
            val ws = d.minusDays(((d.dayOfWeek.value % 7)).toLong()).toEpochDay() // week anchored to Sunday
            byWeekStart[ws] = (byWeekStart[ws] ?: 0) + 1
        }
        val thisWs = today.minusDays((today.dayOfWeek.value % 7).toLong()).toEpochDay()
        val thisCount = byWeekStart[thisWs] ?: 0
        if (thisCount < 3) return null   // don't crow about a quiet week
        // The most recent earlier week that beat or tied this one.
        val earlierBeat = byWeekStart.filterKeys { it < thisWs }.filterValues { it >= thisCount }.keys.maxOrNull()
        return if (earlierBeat == null) "Your most-finished week on record — $thisCount done"
        else {
            val since = LocalDate.ofEpochDay(earlierBeat)
            "Your best week since ${since.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${since.year} — $thisCount done"
        }
    }

    /** Where today sits among your active days by number of finishes. */
    fun todayStandout(all: List<Accomplishment>, today: LocalDate = LocalDate.now()): String? {
        val counts = all.groupBy { it.epochDay }.mapValues { it.value.size }
        val todayCount = counts[today.toEpochDay()] ?: 0
        if (todayCount < 3 || counts.size < 5) return null
        val rank = counts.values.count { it > todayCount } + 1
        return if (rank == 1) "Your biggest day ever — $todayCount finished" else "Your #$rank day ever — $todayCount finished"
    }
}
