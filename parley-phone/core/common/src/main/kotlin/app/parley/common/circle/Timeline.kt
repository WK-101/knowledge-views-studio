package app.parley.common.circle

import app.parley.common.CallEntry
import app.parley.common.EventDate
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * R2: a contact's timeline. Calls (from the call log), interactions, call notes and dates, newest first and grouped
 * by month. The source lists stay where they are; this only merges them.
 */
sealed interface TimelineEntry {
    val time: Long

    data class Call(val call: CallEntry) : TimelineEntry {
        override val time: Long get() = call.date
    }

    data class Logged(val id: Long, override val time: Long, val type: InteractionType, val channel: InteractionChannel?, val note: String?) : TimelineEntry

    data class Note(val id: Long, override val time: Long, val text: String) : TimelineEntry

    /** One occasion of a date (birthday, anniversary…) that fell inside the timeline's span. */
    data class Date(override val time: Long, val type: Int, val label: String?, val date: EventDate) : TimelineEntry
}

data class TimelineMonth(val month: YearMonth, val entries: List<TimelineEntry>)

object Timeline {
    /**
     * Merges [entries] newest first and groups them by calendar month in [zone]. Entries at the same time keep a
     * stable order: interactions, notes, calls, dates.
     */
    fun group(entries: List<TimelineEntry>, zone: ZoneId): List<TimelineMonth> {
        val sorted = entries.sortedWith(compareByDescending<TimelineEntry> { it.time }.thenBy { rank(it) })
        val out = ArrayList<TimelineMonth>()
        var current: YearMonth? = null
        var bucket = ArrayList<TimelineEntry>()
        for (e in sorted) {
            val m = YearMonth.from(Instant.ofEpochMilli(e.time).atZone(zone))
            if (m != current) {
                if (current != null) out += TimelineMonth(current, bucket)
                current = m
                bucket = ArrayList()
            }
            bucket += e
        }
        if (current != null) out += TimelineMonth(current, bucket)
        return out
    }

    private fun rank(e: TimelineEntry): Int = when (e) {
        is TimelineEntry.Logged -> 0
        is TimelineEntry.Note -> 1
        is TimelineEntry.Call -> 2
        is TimelineEntry.Date -> 3
    }

    /**
     * The days [date] fell on between [from] and [to] (inclusive), oldest first, so dates show among the months that
     * have other entries without filling an empty timeline. A date with a year never falls before that year.
     */
    fun occurrences(date: EventDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val out = ArrayList<LocalDate>()
        var y = from.year
        while (y <= to.year) {
            val d = date.next(LocalDate.of(y, 1, 1))
            if (d.year == y && !d.isBefore(from) && !d.isAfter(to) && (date.year == null || y >= date.year)) out += d
            y++
        }
        return out
    }

    /** Dates to show beside [others]: occurrences from the oldest other entry up to [today] (none without entries). */
    fun dates(events: List<Triple<Int, String?, EventDate>>, others: List<TimelineEntry>, today: LocalDate, zone: ZoneId): List<TimelineEntry.Date> {
        val oldest = others.minOfOrNull { it.time } ?: return emptyList()
        val from = Instant.ofEpochMilli(oldest).atZone(zone).toLocalDate()
        return events.flatMap { (type, label, d) ->
            occurrences(d, from, today).map { day -> TimelineEntry.Date(day.atStartOfDay(zone).toInstant().toEpochMilli(), type, label, d) }
        }
    }
}
