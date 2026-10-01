package app.parley.common.calls

import app.parley.common.PhoneIdentity
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime

/** What turned "Expecting a call" on by itself (each is asked about once, and is off until accepted). */
@Serializable
enum class ExpectedSource {
    /** A note or promise that says someone will call on a day ("dentist will call Tue"). */
    NOTE,

    /** An item on the To call list: its number may call back until the item is due. */
    TO_CALL,

    /** A scanned QR code that looks like a parcel delivery: couriers call from numbers nobody saved. */
    DELIVERY_QR,
}

/**
 * A stretch of time in which unknown callers ring through screening, like "Expecting a call" turned on by hand.
 * [number] limits it to one line (a To call item); null lets every unknown caller ring. [label] names where it came
 * from ("Dentist" for a note on Dentist), shown only while the phone is unlocked; [key] tells windows from the same
 * source apart, so saving the same note again replaces its window instead of adding one.
 */
@Serializable
data class ExpectedWindow(
    val start: Long,
    val end: Long,
    val source: ExpectedSource,
    val key: String,
    val label: String? = null,
    val number: String? = null,
) {
    fun covers(now: Long): Boolean = now in start until end
}

/**
 * I7 expected-call hints: windows for "Expecting a call" worked out from notes, the To call list and delivery QR codes.
 * No language model: a note counts when one line has a word for calling and a day in plain English ("today",
 * "tomorrow", a weekday, "in 3 days", "12 Oct", "2026-10-12"), optionally a time ("at 3pm", "morning").
 */
object ExpectedCalls {
    /** A day without a time: the hours people usually get calls. */
    const val DAY_START_HOUR = 8
    const val DAY_END_HOUR = 20

    /** A time of day: from half an hour before to two hours after. */
    const val BEFORE_MINUTES = 30L
    const val AFTER_MINUTES = 120L

    /** Dates further ahead than this are plans, not calls to expect. */
    const val MAX_DAYS_AHEAD = 60L

    /** A To call item lets its number ring until a day after it's due, for at most a week. */
    const val TO_CALL_MAX_DAYS = 7L

    /** At most this many windows are kept; the oldest go first. */
    const val MAX_WINDOWS = 20

    private const val HOUR = 3_600_000L
    private const val MINUTE = 60_000L
    private const val DAY = 24 * HOUR
    private val I = setOf(RegexOption.IGNORE_CASE)

    private val callWord = Regex("""\b(call|calls|calling|called back|ring|rings|ringing|phone|phones|callback|call-back)\b""", I)
    private val doneBox = Regex("""^\s*(?:[-*]\s+)?\[[xX]]""")
    private val todayWord = Regex("""\b(today|tonight|this (morning|afternoon|evening))\b""", I)
    private val tomorrow = Regex("""\b(tomorrow|tmrw|tmr)\b""", I)
    private val inDays = Regex("""\bin\s+(\d{1,2}|a|one|two|three|four|five|six|seven)\s+days?\b""", I)
    private val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?"
    private val dayMonth = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$MONTH(?![a-z])""", I)
    private val monthDay = Regex("""\b$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?\b""", I)

    // Full weekday names in any case; three-letter ones only capitalised ("Sat", not "sat down"), or with a dot.
    private val weekdayFull = Regex("""\b(next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", I)
    private val weekdayShort = Regex(
        """\b([Nn]ext\s+)?(Mon|Tue|Tues|Wed|Weds|Thu|Thur|Thurs|Fri|Sat|Sun)\b""" +
            """|\b([Nn]ext\s+)?(mon|tue|tues|wed|weds|thu|thur|thurs|fri|sat|sun)\.""",
    )

    private val clock12 = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)""", I)
    private val clock24 = Regex("""\b(?:at\s+)?([01]?\d|2[0-3])[:.h]([0-5]\d)\b""", I)
    private val atHour = Regex("""\bat\s+(\d{1,2})\b(?!\s*(?:days?|weeks?|%|:))""", I)
    private val morning = Regex("""\bmorning\b""", I)
    private val afternoon = Regex("""\bafternoon\b""", I)
    private val evening = Regex("""\b(evening|tonight)\b""", I)

    /**
     * The first window still to come that [note] promises a call for, or null. Each line is read on its own; a ticked
     * promise ("[x] …") is done and counts no more. A window that has already ended doesn't count.
     */
    fun fromNote(note: String?, now: Long, zone: ZoneId): Pair<Long, Long>? {
        if (note.isNullOrBlank()) return null
        val nowAt = Instant.ofEpochMilli(now).atZone(zone)
        return note.lines()
            .filterNot { doneBox.containsMatchIn(it) }
            .filter { callWord.containsMatchIn(it) }
            .mapNotNull { line -> dayOf(line, nowAt.toLocalDate())?.let { windowOn(it, line, zone) } }
            .filter { (_, end) -> end > now && end - now <= (MAX_DAYS_AHEAD + 1) * DAY }
            .minByOrNull { it.first }
    }

    /** The day [line] names, or null. */
    internal fun dayOf(line: String, today: LocalDate): LocalDate? {
        iso.find(line)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        dayMonth.find(line)?.let { m -> return monthDate(m.groupValues[2], m.groupValues[1].toInt(), today) }
        monthDay.find(line)?.let { m -> return monthDate(m.groupValues[1], m.groupValues[2].toInt(), today) }
        if (tomorrow.containsMatchIn(line)) return today.plusDays(1)
        inDays.find(line)?.let { m -> return today.plusDays(wordNumber(m.groupValues[1]).toLong()) }
        weekday(line)?.let { (day, next) ->
            val ahead = ((day.value - today.dayOfWeek.value) + 7) % 7
            return today.plusDays(if (ahead == 0 && next) 7L else ahead.toLong())
        }
        if (todayWord.containsMatchIn(line)) return today
        return null
    }

    private fun weekday(line: String): Pair<DayOfWeek, Boolean>? {
        weekdayFull.find(line)?.let { m -> return dayFor(m.groupValues[2]) to m.groupValues[1].isNotBlank() }
        weekdayShort.find(line)?.let { m ->
            val name = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val next = (m.groupValues[1] + m.groupValues[3]).isNotBlank()
            return dayFor(name) to next
        }
        return null
    }

    private fun dayFor(name: String): DayOfWeek = when (name.lowercase().take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    private fun wordNumber(w: String): Int = when (w.lowercase()) {
        "a", "one" -> 1
        "two" -> 2
        "three" -> 3
        "four" -> 4
        "five" -> 5
        "six" -> 6
        "seven" -> 7
        else -> w.toIntOrNull() ?: 0
    }

    /** "12 Oct": this year, or next year when that day is more than a day gone. */
    private fun monthDate(month: String, day: Int, today: LocalDate): LocalDate? {
        val m = months.indexOf(month.lowercase().take(3)).takeIf { it >= 0 } ?: return null
        val d = runCatching { LocalDate.of(today.year, Month.of(m + 1), day) }.getOrNull() ?: return null
        return if (d.isBefore(today.minusDays(1))) runCatching { d.plusYears(1) }.getOrNull() else d
    }

    /** The hours on [day] that [line] points to: a time, part of the day, or the usual daytime hours. */
    private fun windowOn(day: LocalDate, line: String, zone: ZoneId): Pair<Long, Long> {
        fun at(t: LocalTime): Long = ZonedDateTime.of(day, t, zone).toInstant().toEpochMilli()
        timeOf(line)?.let { t ->
            val start = (at(t) - BEFORE_MINUTES * MINUTE).coerceAtLeast(at(LocalTime.MIDNIGHT))
            return start to at(t) + AFTER_MINUTES * MINUTE
        }
        val (from, to) = when {
            morning.containsMatchIn(line) -> DAY_START_HOUR to 12
            afternoon.containsMatchIn(line) -> 12 to 18
            evening.containsMatchIn(line) -> 17 to 21
            else -> DAY_START_HOUR to DAY_END_HOUR
        }
        return at(LocalTime.of(from, 0)) to at(LocalTime.of(to, 0))
    }

    /** "3pm", "3:30 pm", "15:00", "at 3" (1 to 7 o'clock without am/pm read as afternoon). */
    internal fun timeOf(line: String): LocalTime? {
        clock12.find(line)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min > 59) return null
            val pm = m.groupValues[3].lowercase().startsWith("p")
            return LocalTime.of((h % 12) + if (pm) 12 else 0, min)
        }
        clock24.find(line)?.let { m -> return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        atHour.find(line)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h !in 0..23) return null
            return LocalTime.of(if (h in 1..7) h + 12 else h, 0)
        }
        return null
    }

    /** A To call item due at [dueAt]: its number rings through until a day after, for at most a week. */
    fun forToCall(dueAt: Long, now: Long): Pair<Long, Long> =
        now to (maxOf(dueAt, now) + DAY).coerceAtMost(now + TO_CALL_MAX_DAYS * DAY)

    private val deliveryWords = Regex(
        """\b(track|tracking|trace|parcel|package|delivery|deliveries|deliver|courier|shipment|shipping|consignment|""" +
            """dhl|ups|fedex|dpd|gls|usps|evri|hermes|yodel|inpost|parcelforce|royal\s?mail|postnl|colissimo|chronopost|""" +
            """aftership|17track|canadapost|auspost|paket|sendung|colis|paquete|envio)\b""",
        I,
    )

    /** A scanned QR code that looks like a parcel: a tracking link or text with a carrier's or courier's words. */
    fun isDelivery(text: String?): Boolean = !text.isNullOrBlank() && deliveryWords.containsMatchIn(text.take(2000))

    /** After a delivery QR code: until 8 pm today (at least two hours), or tomorrow's daytime when scanned that late. */
    fun forDelivery(now: Long, zone: ZoneId): Pair<Long, Long> {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val endToday = t.toLocalDate().atTime(DAY_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        if (now < endToday - 2 * HOUR) return now to endToday
        if (now < endToday) return now to now + 2 * HOUR
        val next = t.toLocalDate().plusDays(1)
        return next.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli() to
            next.atTime(DAY_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * The window that lets [number] ring at [now]: one for that number first, then one for everyone; null when none.
     * A hidden number ([number] null) only matches windows for everyone.
     */
    fun covering(windows: List<ExpectedWindow>, number: String?, region: String?, now: Long): ExpectedWindow? {
        val live = windows.filter { it.covers(now) }
        if (number != null) live.firstOrNull { it.number != null && PhoneIdentity.same(it.number, number, region) }?.let { return it }
        return live.firstOrNull { it.number == null }
    }

    /** [windows] with [w] in place of any window from the same source, without ended ones, at most [MAX_WINDOWS]. */
    fun put(windows: List<ExpectedWindow>, w: ExpectedWindow, now: Long): List<ExpectedWindow> =
        (prune(windows, now).filterNot { it.source == w.source && it.key == w.key } + w).sortedBy { it.start }.takeLast(MAX_WINDOWS)

    /** Without the windows that have ended. */
    fun prune(windows: List<ExpectedWindow>, now: Long): List<ExpectedWindow> = windows.filter { it.end > now }

    /** Without the windows of [source] (its toggle turned off). */
    fun without(windows: List<ExpectedWindow>, source: ExpectedSource): List<ExpectedWindow> = windows.filterNot { it.source == source }
}
