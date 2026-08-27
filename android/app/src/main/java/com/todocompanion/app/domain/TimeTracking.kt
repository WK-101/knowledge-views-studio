package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.TimeEntryEntity

/**
 * Tier S — pure maths for the time-tracker: how many minutes of each entry fall inside a day window,
 * and per-activity totals. Kept out of the UI so it can be unit-tested and reused by any future widget.
 * A running entry (endMillis == null) is clamped at "now".
 */
object TimeTracking {

    /** Milliseconds of one interval that overlap [winStart, winEnd). Handles entries that span midnight. */
    fun millisInWindow(startMillis: Long, endMillis: Long?, winStart: Long, winEnd: Long, now: Long): Long {
        val end = endMillis ?: now
        val lo = maxOf(startMillis, winStart)
        val hi = minOf(end, winEnd)
        return if (hi <= lo) 0L else hi - lo
    }

    /** Minutes of one interval that overlap [winStart, winEnd). Handles entries that span midnight. */
    fun minutesInWindow(startMillis: Long, endMillis: Long?, winStart: Long, winEnd: Long, now: Long): Int =
        (millisInWindow(startMillis, endMillis, winStart, winEnd, now) / 60_000L).toInt()

    /**
     * True if the interval has ANY real (>0ms) overlap with the window — even a few-second entry that
     * rounds down to 0 minutes. Used to decide which entries to LIST (so every single tracked entry of
     * the day/week/month shows up), independent of how their duration is totalled for the bars.
     */
    fun overlapsWindow(startMillis: Long, endMillis: Long?, winStart: Long, winEnd: Long, now: Long): Boolean =
        millisInWindow(startMillis, endMillis, winStart, winEnd, now) > 0L

    data class ActivityTotal(val activityId: String, val minutes: Int)

    /** Per-activity minute totals inside the window, largest first, zero-minute activities dropped. */
    fun totalsByActivity(entries: List<TimeEntryEntity>, winStart: Long, winEnd: Long, now: Long): List<ActivityTotal> =
        entries.groupBy { it.activityId }
            .map { (id, es) -> ActivityTotal(id, es.sumOf { minutesInWindow(it.startMillis, it.endMillis, winStart, winEnd, now) }) }
            .filter { it.minutes > 0 }
            .sortedByDescending { it.minutes }

    /** Total tracked minutes across all activities in the window. */
    fun totalMinutes(entries: List<TimeEntryEntity>, winStart: Long, winEnd: Long, now: Long): Int =
        entries.sumOf { minutesInWindow(it.startMillis, it.endMillis, winStart, winEnd, now) }
}
