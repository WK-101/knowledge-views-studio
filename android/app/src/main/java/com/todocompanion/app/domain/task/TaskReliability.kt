package com.todocompanion.app.domain.task

import com.todocompanion.app.data.entity.ActivityEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.recurrence.Freq
import com.todocompanion.app.domain.recurrence.Recurrence
import java.time.ZoneId

/**
 * P1 — carry the habit "strength" idea over to recurring tasks. A recurring commitment ("pay rent",
 * "weekly review", "water plants") is a habit in disguise; this scores how reliably you actually keep
 * it as a 0–100 rate: completions logged in a recent window ÷ occurrences expected in that window.
 * Entirely on-device, computed from the task activity log.
 */
object TaskReliability {

    data class Reliability(val score: Int, val kept: Int, val expected: Int)

    /** Rough days between occurrences for a recurrence rule (used to size the window + expectation). */
    fun periodDays(rrule: String): Double? {
        val r = Recurrence.parse(rrule) ?: return null
        val i = r.interval.coerceAtLeast(1)
        return when (r.freq) {
            Freq.DAILY -> i.toDouble()
            Freq.WEEKDAYS -> 7.0 / 5.0
            Freq.WEEKLY -> (7.0 * i) / (r.byDays.size.coerceAtLeast(1))
            Freq.MONTHLY -> 30.0 * i
            Freq.YEARLY -> 365.0 * i
        }
    }

    /**
     * Reliability over a window sized to ~10 occurrences (capped 30–180 days). Returns null for a
     * non-recurring task or when fewer than ~3 occurrences are expected (too little to judge).
     */
    fun score(task: TaskEntity, activities: List<ActivityEntity>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Reliability? {
        val rrule = task.rrule?.takeIf { it.isNotBlank() } ?: return null
        val period = periodDays(rrule) ?: return null
        if (period <= 0) return null
        val windowDays = (period * 10).coerceIn(30.0, 180.0)
        val since = nowMillis - (windowDays * 86_400_000L).toLong()
        val expected = (windowDays / period).toInt()
        if (expected < 3) return null
        val kept = activities.count { it.taskId == task.id && it.type == "completed" && it.at >= since }
        val score = ((kept.toDouble() / expected) * 100).toInt().coerceIn(0, 100)
        return Reliability(score, kept, expected)
    }
}
