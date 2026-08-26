package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.TimeEntryEntity

/**
 * Tier S — pure maths for the time-tracker: how many minutes of each entry fall inside a day window,
 * and per-activity totals. Kept out of the UI so it can be unit-tested and reused by any future widget.
 * A running entry (endMillis == null) is clamped at "now".
 */
object TimeTracking {

    /** Minutes of one interval that overlap [winStart, winEnd). Handles entries that span midnight. */
    fun minutesInWindow(startMillis: Long, endMillis: Long?, winStart: Long, winEnd: Long, now: Long): Int {
        val end = endMillis ?: now
        val lo = maxOf(startMillis, winStart)
        val hi = minOf(end, winEnd)
        return if (hi <= lo) 0 else ((hi - lo) / 60_000L).toInt()
    }

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
