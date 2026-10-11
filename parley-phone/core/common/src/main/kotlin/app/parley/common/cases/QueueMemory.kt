package app.parley.common.cases

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Queue memory: "Your last 4 calls waited 6–25 min. Shortest before 9:30 on weekdays." From the hold times a case file
 * kept for your calls to an organisation (hold mode measured them). [calls] is how many it is based on, [shortestSec]
 * and [longestSec] the range, and [best] the part of the day when the waits were all shorter, when there is one.
 */
data class QueueMemory(val calls: Int, val shortestSec: Long, val longestSec: Long, val best: QueueTime?)

/** "Shortest before 9:30" ([before]) or "after 14:00", at [minuteOfDay] local time; [weekdays]: those calls were all Monday to Friday. */
data class QueueTime(val minuteOfDay: Int, val before: Boolean, val weekdays: Boolean)

/** Works out [QueueMemory] from a case file's timeline. Pure. */
object QueueMemories {
    /** Fewer timed calls than this say nothing: a small sample isn't a pattern. */
    const val MIN_CALLS = 3

    /** Only the latest timed calls count: queues change. */
    const val RECENT = 8

    /** A better time is named only when its waits were all at least this much shorter than the others. */
    const val MIN_GAIN_SEC = 120L

    private const val SLOT_MIN = 30
    private const val DAY_MIN = 24 * 60

    /** The memory for [entries] (a case timeline's, any order) in [zone]; null with fewer than [MIN_CALLS] timed calls of yours. */
    fun of(entries: List<CaseEntry>, zone: ZoneId): QueueMemory? {
        val held = entries.filterIsInstance<CaseEntry.Call>().filter { !it.incoming && it.holdSec > 0 }.sortedByDescending { it.at }.take(RECENT)
        if (held.size < MIN_CALLS) return null
        return QueueMemory(held.size, held.minOf { it.holdSec }, held.maxOf { it.holdSec }, bestTime(held, zone))
    }

    private class Point(val minute: Int, val wait: Long, val weekday: Boolean)

    /**
     * The cut of the day that splits the calls cleanly: every call on one side (at least two of them) waited less than
     * every call on the other, by [MIN_GAIN_SEC] or more. The cut is on a half hour, between the two sides. The widest
     * gain wins; null when the waits don't split by time of day.
     */
    private fun bestTime(held: List<CaseEntry.Call>, zone: ZoneId): QueueTime? {
        val points = held.map { c ->
            val t = Instant.ofEpochMilli(c.at).atZone(zone)
            Point(t.hour * 60 + t.minute, c.holdSec, t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY)
        }.sortedBy { it.minute }
        var best: Pair<Long, QueueTime>? = null
        for (k in 1 until points.size) {
            val early = points.subList(0, k)
            val late = points.subList(k, points.size)
            val beforeCut = (early.last().minute / SLOT_MIN + 1) * SLOT_MIN
            val beforeGain = late.minOf { it.wait } - early.maxOf { it.wait }
            val beforeFits = beforeCut <= late.first().minute && beforeCut < DAY_MIN
            if (early.size >= 2 && beforeGain >= MIN_GAIN_SEC && beforeFits) {
                if (best == null || beforeGain > best.first) best = beforeGain to QueueTime(beforeCut, before = true, weekdays = early.all { it.weekday })
            }
            val afterCut = late.first().minute / SLOT_MIN * SLOT_MIN
            val afterGain = early.minOf { it.wait } - late.maxOf { it.wait }
            if (late.size >= 2 && afterGain >= MIN_GAIN_SEC && afterCut > early.last().minute) {
                if (best == null || afterGain > best.first) best = afterGain to QueueTime(afterCut, before = false, weekdays = late.all { it.weekday })
            }
        }
        return best?.second
    }

    /**
     * When "Remind me" should ring for [time]: half an hour before the cut for "before 9:30" (9:00), at the cut for
     * "after 14:00"; the next such moment after [now], on a weekday when [QueueTime.weekdays].
     */
    fun remindAt(time: QueueTime, now: Long, zone: ZoneId): Long {
        val minute = reminderMinute(time)
        val start = Instant.ofEpochMilli(now).atZone(zone)
        var at: ZonedDateTime = start.truncatedTo(ChronoUnit.DAYS).plusMinutes(minute.toLong())
        while (!at.isAfter(start) || (time.weekdays && weekend(at))) {
            at = at.plusDays(1).truncatedTo(ChronoUnit.DAYS).plusMinutes(minute.toLong())
        }
        return at.toInstant().toEpochMilli()
    }

    private fun weekend(t: ZonedDateTime): Boolean = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY

    /** The minute of the day "Remind me" is set for: half an hour before a "before" cut, at an "after" one. */
    fun reminderMinute(time: QueueTime): Int = if (time.before) (time.minuteOfDay - SLOT_MIN).coerceAtLeast(0) else time.minuteOfDay
}
