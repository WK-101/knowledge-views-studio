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

    data class Reliability(val score: Int, val kept: Int, val expected: Int, val streak: Int = 0)

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
        return Reliability(score, kept, expected, streak(task, activities, nowMillis))
    }

    /**
     * Q4 — a forgiving "kept streak": consecutive on-time completions, counting back from now, where a
     * single missed occurrence is granted grace (the streak-freeze idea, built in) — one honest slip on
     * a weekly review shouldn't zero the run. A gap wider than ~1.6 periods is a miss; two in a row ends it.
     */
    fun streak(task: TaskEntity, activities: List<ActivityEntity>, nowMillis: Long): Int {
        val period = task.rrule?.let { periodDays(it) } ?: return 0
        val periodMs = (period * 86_400_000L).toLong().coerceAtLeast(1)
        val done = activities.filter { it.taskId == task.id && it.type == "completed" }.map { it.at }.sortedDescending()
        if (done.isEmpty()) return 0
        // The most recent completion must be reasonably fresh, else the run is already broken.
        if (nowMillis - done.first() > periodMs * 2) return 0
        var count = 1; var graceUsed = false
        for (i in 1 until done.size) {
            val gap = done[i - 1] - done[i]
            when {
                gap <= periodMs * 16 / 10 -> count++            // on rhythm
                gap <= periodMs * 26 / 10 && !graceUsed -> { graceUsed = true; count++ }   // one slip forgiven
                else -> return count
            }
        }
        return count
    }

    /** Q3 — a 24-hour histogram of when this recurring task is actually completed (from stamped times). */
    fun completionHours(task: TaskEntity, activities: List<ActivityEntity>, zone: ZoneId = ZoneId.systemDefault()): IntArray {
        val arr = IntArray(24)
        activities.filter { it.taskId == task.id && it.type == "completed" }
            .forEach { arr[(java.time.Instant.ofEpochMilli(it.at).atZone(zone).hour).coerceIn(0, 23)]++ }
        return arr
    }

    /** Q3 — reliability trend: the score now vs one window ago, for an ↑/↓ indicator. Null if not enough data. */
    fun trend(task: TaskEntity, activities: List<ActivityEntity>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val now = score(task, activities, nowMillis, zone) ?: return null
        val period = task.rrule?.let { periodDays(it) } ?: return null
        val windowMs = ((period * 10).coerceIn(30.0, 180.0) * 86_400_000L).toLong()
        val prev = score(task, activities, nowMillis - windowMs / 2, zone) ?: return null
        return now.score - prev.score
    }
}
