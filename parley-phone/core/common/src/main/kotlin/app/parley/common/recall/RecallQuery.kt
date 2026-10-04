package app.parley.common.recall

import app.parley.common.CallType
import app.parley.common.people.ContactSearch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A Recall query, read on the phone with plain rules (no network, no model): the date words ("march", "last week",
 * "yesterday", "2024", "12 may"), the call words ("missed", "incoming", "who called", "I called") and the small words
 * a question carries ("who", "in", "the") are taken out, and what is left ([words]) is searched like the Contacts
 * search ([ContactSearch.Query]). "plumber march" is the plumber, in March; "who called in March" is the calls that
 * came in during March; "bank last week" is the bank, last week.
 *
 * A word is read as a date or a call word only where it is one of those words; anything else stays a search word. A
 * query made only of small words keeps them ("the who" finds The Who).
 */
data class RecallQuery(
    /** The query as typed. */
    val raw: String,
    /** What is left to search, as typed (folded by [search]). */
    val words: String,
    /** The days asked about, or null for any time. */
    val dates: DateSpan?,
    /** The kinds of call asked about ("missed"), or null for any; only calls can match when set. */
    val callTypes: Set<CallType>?,
    /** A call word was used ("called", "calls"): only calls can match, of any kind unless [callTypes] says. */
    val callsOnly: Boolean,
) {
    /** The search words as a Contacts-search query, folded once. */
    val search: ContactSearch.Query = ContactSearch.Query(words)

    /** Nothing to look for. */
    val isEmpty: Boolean get() = search.isEmpty && dates == null && callTypes == null && !callsOnly

    /** Something besides the search words was understood (a date, a kind of call, question words). */
    val interpreted: Boolean get() = dates != null || callTypes != null || callsOnly || ContactSearch.fold(raw.trim()) != search.folded

    /** Only calls can answer this query. */
    val onlyCalls: Boolean get() = callsOnly || callTypes != null

    /** Whether [at] (epoch ms) falls on the days asked about (always true without a date). */
    fun inDates(at: Long, zone: ZoneId): Boolean = dates == null || dates.contains(at, zone)

    /**
     * Days [start] up to (not including) [end]. [kind] says how the span was asked for, so it can be said back in the
     * same words ("March 2026", "Last week").
     */
    data class DateSpan(val start: LocalDate, val end: LocalDate, val kind: Kind) {
        enum class Kind { DAY, TODAY, YESTERDAY, WEEK_SO_FAR, SINCE_LAST_WEEK, MONTH, SINCE_LAST_MONTH, YEAR, SINCE_LAST_YEAR }

        fun startMillis(zone: ZoneId): Long = start.atStartOfDay(zone).toInstant().toEpochMilli()

        fun endMillis(zone: ZoneId): Long = end.atStartOfDay(zone).toInstant().toEpochMilli()

        fun contains(at: Long, zone: ZoneId): Boolean = at >= startMillis(zone) && at < endMillis(zone)
    }

    companion object {
        /**
         * Reads [text] for [today]. Month and weekday names are understood in English and in [locale]'s language; a
         * week starts as [locale] has it.
         */
        fun parse(text: String, today: LocalDate, locale: Locale = Locale.getDefault()): RecallQuery = Parser(text, today, locale).run()
    }
}

/** The rules of [RecallQuery.parse]. */
private class Parser(private val raw: String, private val today: LocalDate, private val locale: Locale) {
    /** Each typed word, and its folded form for matching. */
    private val typed: List<String> = raw.trim().split(' ', '\t', '\n', ',', '?', '!').filter { it.isNotEmpty() }
    private val folded: List<String> = typed.map { ContactSearch.fold(it).trim('.', '\'', '"') }
    private val used = BooleanArray(typed.size)

    private var dates: RecallQuery.DateSpan? = null
    private var types: MutableSet<CallType>? = null
    private var callsOnly = false

    fun run(): RecallQuery {
        phrases()
        monthsAndYears()
        weekdays()
        callWords()
        smallWords()
        val words = typed.indices.filterNot { used[it] }.joinToString(" ") { typed[it] }
        return RecallQuery(raw, words, dates, types, callsOnly)
    }

    private fun at(i: Int): String? = folded.getOrNull(i)?.takeIf { !used[i] }

    private fun take(vararg indices: Int) = indices.forEach { used[it] = true }

    private fun setDates(span: RecallQuery.DateSpan) {
        if (dates == null) dates = span
    }

    /** "today", "yesterday", "this week", "last month", "last year"… */
    private fun phrases() {
        for (i in folded.indices) phraseAt(i)
    }

    private fun phraseAt(i: Int) {
        when (val w = at(i)) {
            "today" -> {
                setDates(RecallQuery.DateSpan(today, today.plusDays(1), RecallQuery.DateSpan.Kind.TODAY))
                take(i)
            }
            "yesterday" -> {
                setDates(RecallQuery.DateSpan(today.minusDays(1), today, RecallQuery.DateSpan.Kind.YESTERDAY))
                take(i)
            }
            "this", "last", "past" -> at(i + 1)?.let { unit -> period(unit, last = w != "this") }?.let { span ->
                setDates(span)
                take(i, i + 1)
            }
        }
    }

    /**
     * "last week" reads as from the start of last week until today, and so on: a call remembered as last week's may
     * well have been this week's, and the newest come first anyway.
     */
    private fun period(unit: String, last: Boolean): RecallQuery.DateSpan? {
        val end = today.plusDays(1)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(locale).firstDayOfWeek))
        return when (unit) {
            "week" -> if (last) {
                RecallQuery.DateSpan(weekStart.minusWeeks(1), end, RecallQuery.DateSpan.Kind.SINCE_LAST_WEEK)
            } else {
                RecallQuery.DateSpan(weekStart, end, RecallQuery.DateSpan.Kind.WEEK_SO_FAR)
            }
            "month" -> if (last) {
                RecallQuery.DateSpan(today.withDayOfMonth(1).minusMonths(1), end, RecallQuery.DateSpan.Kind.SINCE_LAST_MONTH)
            } else {
                RecallQuery.DateSpan(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1), RecallQuery.DateSpan.Kind.MONTH)
            }
            "year" -> if (last) {
                RecallQuery.DateSpan(today.withDayOfYear(1).minusYears(1), end, RecallQuery.DateSpan.Kind.SINCE_LAST_YEAR)
            } else {
                RecallQuery.DateSpan(today.withDayOfYear(1), today.withDayOfYear(1).plusYears(1), RecallQuery.DateSpan.Kind.YEAR)
            }
            else -> null
        }
    }

    /** A month name with an optional day ("12 march", "march 12") and year ("march 2024"), or a year alone. */
    private fun monthsAndYears() {
        val i = folded.indices.firstOrNull { at(it)?.let(::monthOf) != null }
        if (i != null) return setDates(monthSpan(i, monthOf(folded[i])!!))
        val y = folded.indices.firstOrNull { yearAt(it) != null } ?: return
        val first = LocalDate.of(yearAt(y)!!, 1, 1)
        take(y)
        setDates(RecallQuery.DateSpan(first, first.plusYears(1), RecallQuery.DateSpan.Kind.YEAR))
    }

    /** The month named at [i], with the day and year beside it (taken with it). */
    private fun monthSpan(i: Int, month: Month): RecallQuery.DateSpan {
        take(i)
        val year = yearAt(i + 1)?.also { take(i + 1) } ?: yearAt(i + 2)?.takeIf { dayAt(i + 1) != null }?.also { take(i + 2) }
        val day = dayAt(i - 1)?.also { take(i - 1) } ?: dayAt(i + 1)?.also { take(i + 1) }
        // A month without a year is the last one that has begun: "march" in February is last year's.
        val y = year ?: if (month.value <= today.monthValue) today.year else today.year - 1
        val first = LocalDate.of(y, month, 1)
        if (day == null || day > first.lengthOfMonth()) return RecallQuery.DateSpan(first, first.plusMonths(1), RecallQuery.DateSpan.Kind.MONTH)
        val d = first.withDayOfMonth(day)
        // "12 march" without a year that hasn't come yet this year is last year's.
        val shown = if (year == null && d.isAfter(today)) d.minusYears(1) else d
        return RecallQuery.DateSpan(shown, shown.plusDays(1), RecallQuery.DateSpan.Kind.DAY)
    }

    /** "monday": the last Monday, today included. */
    private fun weekdays() {
        for (i in folded.indices) {
            val day = at(i)?.let(::weekdayOf) ?: continue
            take(i)
            val d = today.with(TemporalAdjusters.previousOrSame(day))
            setDates(RecallQuery.DateSpan(d, d.plusDays(1), RecallQuery.DateSpan.Kind.DAY))
            return
        }
    }

    /** "missed", "incoming", "outgoing", "who called", "I called", "called". */
    private fun callWords() {
        for (i in folded.indices) {
            when (at(i)) {
                in MISSED -> addTypes(i, CallType.MISSED)
                in INCOMING -> addTypes(i, CallType.INCOMING)
                in OUTGOING -> addTypes(i, CallType.OUTGOING)
                in BLOCKED -> addTypes(i, CallType.BLOCKED)
                in CALLED -> called(i)
            }
        }
    }

    /** A call word at [i]: only calls, and which way when the words around say ("who called", "I called"). */
    private fun called(i: Int) {
        take(i)
        callsOnly = true
        val before = folded.getOrNull(i - 1)
        val after = folded.getOrNull(i + 1)
        when {
            // "who called", "called me": calls that came in (answered or not).
            before == "who" || after == "me" -> {
                types = (types ?: mutableSetOf()).apply { add(CallType.INCOMING); add(CallType.MISSED) }
                if (after == "me") take(i + 1)
            }
            // "I called": calls made.
            before == "i" -> {
                types = (types ?: mutableSetOf()).apply { add(CallType.OUTGOING) }
                take(i - 1)
            }
        }
    }

    private fun addTypes(i: Int, type: CallType) {
        take(i)
        types = (types ?: mutableSetOf()).apply { add(type) }
        // "missed calls": the call word is said already.
        if (at(i + 1) in CALLED) take(i + 1)
    }

    /** Question words, only when something else is left or was understood. */
    private fun smallWords() {
        val small = folded.indices.filter { !used[it] && folded[it] in SMALL }
        val rest = folded.indices.count { !used[it] && folded[it] !in SMALL }
        val understood = dates != null || types != null || callsOnly
        if (rest > 0 || understood) small.forEach { used[it] = true }
    }

    private fun yearAt(i: Int): Int? = at(i)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in YEARS }

    private fun dayAt(i: Int): Int? = at(i)?.removeSuffix("st")?.removeSuffix("nd")?.removeSuffix("rd")?.removeSuffix("th")
        ?.takeIf { it.length in 1..2 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..31 }

    private fun monthOf(w: String): Month? = months[w]

    private fun weekdayOf(w: String): DayOfWeek? = weekdays[w]

    /** Month names (full, and the abbreviations that aren't also common names) in English and the phone's language. */
    private val months: Map<String, Month> by lazy {
        val out = HashMap<String, Month>()
        languages().forEach { l ->
            Month.entries.forEach { m ->
                listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE).forEach { s ->
                    out[ContactSearch.fold(m.getDisplayName(s, l)).trimEnd('.')] = m
                }
            }
        }
        ABBREVIATIONS.forEach { (k, m) -> out[k] = m }
        out.keys.removeAll { it.isEmpty() || it.any(Char::isDigit) }
        out
    }

    private val weekdays: Map<String, DayOfWeek> by lazy {
        val out = HashMap<String, DayOfWeek>()
        languages().forEach { l ->
            DayOfWeek.entries.forEach { d ->
                listOf(TextStyle.FULL, TextStyle.FULL_STANDALONE).forEach { s -> out[ContactSearch.fold(d.getDisplayName(s, l))] = d }
            }
        }
        out.keys.removeAll { it.isEmpty() }
        out
    }

    private fun languages(): List<Locale> = listOf(Locale.ENGLISH, locale).distinctBy { it.language }

    companion object {
        val YEARS = 1970..2100
        val MISSED = setOf("missed", "unanswered")
        val INCOMING = setOf("incoming", "received", "answered")
        val OUTGOING = setOf("outgoing", "dialled", "dialed", "made")
        val BLOCKED = setOf("blocked")
        val CALLED = setOf("called", "call", "calls", "rang", "phoned")

        /** Words a question carries that never narrow a search. */
        val SMALL = setOf(
            "who", "whom", "what", "when", "which", "was", "were", "is", "did", "the", "a", "an", "in", "on", "at", "from", "during",
            "of", "with", "me", "my", "i", "that", "about", "to", "by", "for", "and",
        )

        /** English abbreviations that aren't also everyday names or words ("jan", "jun", "mar" and "may" are). */
        val ABBREVIATIONS = mapOf(
            "feb" to Month.FEBRUARY, "apr" to Month.APRIL, "aug" to Month.AUGUST, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
            "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
        )
    }
}
