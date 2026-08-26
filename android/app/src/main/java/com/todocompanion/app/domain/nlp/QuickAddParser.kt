package com.todocompanion.app.domain.nlp

import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.domain.recurrence.Freq
import com.todocompanion.app.domain.recurrence.Recur
import com.todocompanion.app.domain.recurrence.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Result of parsing a quick-add string. Times are local; caller converts to epoch millis. */
data class ParsedQuickAdd(
    val title: String,
    val dateTime: LocalDateTime? = null,
    val hasTime: Boolean = false,
    val priority: PriorityLevel? = null,
    val tags: List<String> = emptyList(),
    val contexts: List<String> = emptyList(),
    val list: String? = null,
    val rrule: String? = null,
    /** Reminder lead time in minutes before the due date, from a "!30m / !2h / !1d" shortcut. */
    val reminderOffsetMin: Int? = null,
) {
    /** Compact chips for the quick-add UI. */
    fun chips(): List<Chip> = buildList {
        dateTime?.let { add(Chip(ChipType.DATE, formatDate(it, hasTime))) }
        priority?.let { if (it != PriorityLevel.NONE) add(Chip(ChipType.PRIORITY, it.label)) }
        reminderOffsetMin?.let { add(Chip(ChipType.REMINDER, "🔔 ${reminderLabel(it)}")) }
        tags.forEach { add(Chip(ChipType.TAG, "#$it")) }
        contexts.forEach { add(Chip(ChipType.CONTEXT, "@$it")) }
    }

    companion object {
        internal fun reminderLabel(min: Int): String = when {
            min == 0 -> "on time"
            min % 1440 == 0 -> "${min / 1440}d before"
            min % 60 == 0 -> "${min / 60}h before"
            else -> "${min}m before"
        }
        private fun formatDate(dt: LocalDateTime, hasTime: Boolean): String {
            val d = dt.toLocalDate()
            val today = LocalDate.now()
            val dayLabel = when (d) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> "${d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${d.dayOfMonth}"
            }
            return if (hasTime) "$dayLabel ${"%02d:%02d".format(dt.hour, dt.minute)}" else dayLabel
        }
    }
}

enum class ChipType { DATE, PRIORITY, TAG, CONTEXT, REMINDER }
data class Chip(val type: ChipType, val text: String)

/**
 * A pragmatic on-device natural-language parser for the quick-add box. No network, no ML.
 * Handles: #tags, @contexts, !/!!/!!! or p1..p4 priority, and common date/time phrases.
 */
object QuickAddParser {

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): ParsedQuickAdd {
        val strip = mutableListOf<IntRange>()

        // #tags and @contexts
        val tags = Regex("(?<=\\s|^)#([\\p{L}0-9_-]+)").findAll(text)
            .onEach { strip.add(it.range) }.map { it.groupValues[1] }.toList()
        val contexts = Regex("(?<=\\s|^)@([\\p{L}0-9_-]+)").findAll(text)
            .onEach { strip.add(it.range) }.map { it.groupValues[1] }.toList()
        // ~list
        val list = Regex("(?<=\\s|^)~([\\p{L}0-9_-]+)").find(text)?.let { strip.add(it.range); it.groupValues[1] }

        // reminder shortcut: "!30m", "!2h", "!1d", "!1w" → lead time before the due date.
        // Parsed before priority so the "!" + digit form is claimed here, not by the "!" priority.
        var reminderOffsetMin: Int? = null
        Regex("(?<=\\s|^)!(\\d{1,4})\\s*(m|min|mins|h|hr|hrs|hour|hours|d|day|days|w|wk|week|weeks)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2].lowercase()
            reminderOffsetMin = when {
                unit.startsWith("w") -> n * 10080
                unit.startsWith("d") -> n * 1440
                unit.startsWith("h") -> n * 60
                else -> n
            }
            strip.add(m.range)
        }

        // priority: p1..p4 or !!!/!!/!
        var priority: PriorityLevel? = null
        Regex("(?<=\\s|^)p([1-4])\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            priority = when (it.groupValues[1]) {
                "1" -> PriorityLevel.HIGH; "2" -> PriorityLevel.MEDIUM; "3" -> PriorityLevel.LOW; else -> PriorityLevel.NONE
            }
            strip.add(it.range)
        }
        if (priority == null) {
            Regex("(?<=\\s|^)(!{1,3})(?=\\s|$)").find(text)?.let {
                priority = when (it.groupValues[1].length) {
                    3 -> PriorityLevel.HIGH; 2 -> PriorityLevel.MEDIUM; else -> PriorityLevel.LOW
                }
                strip.add(it.range)
            }
        }

        // recurrence: "daily/weekly/monthly/yearly", "every [N] day|week|month|year|weekday",
        // "every other <unit|weekday>" (interval 2), or "every mon,wed and fri" → weekly on those days.
        var rrule: String? = null
        // CU1: "every other tuesday" → weekly interval 2 on that weekday; "every other week/day/month" → interval 2.
        run {
            val wd = WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")
            Regex("(?<=\\s|^)every\\s+other\\s+($wd)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                WEEKDAYS[m.groupValues[1].lowercase()]?.let { day ->
                    rrule = Recurrence.encode(Recur(Freq.WEEKLY, 2, byDays = setOf(day.value))); strip.add(m.range)
                }
            } ?: Regex("(?<=\\s|^)every\\s+other\\s+(day|week|month|year)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val freq = when (m.groupValues[1].lowercase()) { "day" -> Freq.DAILY; "week" -> Freq.WEEKLY; "month" -> Freq.MONTHLY; else -> Freq.YEARLY }
                rrule = Recurrence.encode(Recur(freq, 2)); strip.add(m.range)
            }
        }
        if (rrule == null) Regex("(?<=\\s|^)every\\s+(\\d+)?\\s*(weekdays?|days?|weeks?|months?|years?)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 1
            val unit = m.groupValues[2].lowercase()
            val freq = when {
                unit.startsWith("weekday") -> Freq.WEEKDAYS
                unit.startsWith("day") -> Freq.DAILY
                unit.startsWith("week") -> Freq.WEEKLY
                unit.startsWith("month") -> Freq.MONTHLY
                else -> Freq.YEARLY
            }
            rrule = Recurrence.encode(Recur(freq, n.coerceAtLeast(1))); strip.add(m.range)
        }
        if (rrule == null) {
            val wd = WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")
            Regex("(?<=\\s|^)every\\s+((?:$wd)(?:(?:\\s*(?:,|and)\\s*|\\s+)(?:$wd))*)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val days = m.groupValues[1].lowercase().split(Regex("[,&\\s]+")).mapNotNull { WEEKDAYS[it]?.value }.toSet()
                if (days.isNotEmpty()) { rrule = Recurrence.encode(Recur(Freq.WEEKLY, 1, byDays = days)); strip.add(m.range) }
            }
        }
        if (rrule == null) {
            for ((w, f) in listOf("daily" to Freq.DAILY, "weekly" to Freq.WEEKLY, "monthly" to Freq.MONTHLY, "yearly" to Freq.YEARLY, "annually" to Freq.YEARLY)) {
                if (rrule == null) Regex("(?<=\\s|^)$w\\b", RegexOption.IGNORE_CASE).find(text)?.let { rrule = Recurrence.encode(Recur(f)); strip.add(it.range) }
            }
        }

        // date + time
        var date: LocalDate? = null
        var time: LocalTime? = null

        // explicit time: 5pm, 5:30pm, 17:00, at 5
        Regex("(?<=\\s|^)(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            var h = it.groupValues[1].toInt() % 12
            if (it.groupValues[3].lowercase() == "pm") h += 12
            val min = it.groupValues[2].toIntOrNull() ?: 0
            time = LocalTime.of(h.coerceIn(0, 23), min.coerceIn(0, 59))
            strip.add(it.range)
        } ?: Regex("(?<=\\s|^)([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(text)?.let {
            time = LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            strip.add(it.range)
        }
        // named times
        for ((word, t) in listOf("noon" to LocalTime.NOON, "midnight" to LocalTime.MIDNIGHT,
            "morning" to LocalTime.of(9, 0), "afternoon" to LocalTime.of(15, 0), "evening" to LocalTime.of(18, 0))) {
            if (time == null) Regex("(?<=\\s|^)$word\\b", RegexOption.IGNORE_CASE).find(text)?.let {
                time = t; strip.add(it.range)
            }
        }
        // CU1: bare "at 3" (no am/pm) → assume the sensible waking hour (1–7 → afternoon/evening).
        if (time == null) Regex("(?<=\\s|^)at\\s+(\\d{1,2})\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            val raw = it.groupValues[1].toInt()
            if (raw in 0..23) { time = LocalTime.of(if (raw in 1..7) raw + 12 else raw, 0); strip.add(it.range) }
        }

        // relative day words
        val today = now.toLocalDate()
        fun setDate(d: LocalDate, r: IntRange) { if (date == null) { date = d; strip.add(r) } }

        Regex("(?<=\\s|^)today\\b", RegexOption.IGNORE_CASE).find(text)?.let { setDate(today, it.range) }
        Regex("(?<=\\s|^)tonight\\b", RegexOption.IGNORE_CASE).find(text)?.let { setDate(today, it.range); if (time == null) time = LocalTime.of(20, 0) }
        // "day after tomorrow" must be tested before "tomorrow" (which would otherwise claim the inner word).
        Regex("(?<=\\s|^)(day\\s+after\\s+tomorrow|overmorrow)\\b", RegexOption.IGNORE_CASE).find(text)?.let { setDate(today.plusDays(2), it.range) }
        Regex("(?<=\\s|^)tomorrow\\b", RegexOption.IGNORE_CASE).find(text)?.let { setDate(today.plusDays(1), it.range) }
        Regex("(?<=\\s|^)next\\s+week\\b", RegexOption.IGNORE_CASE).find(text)?.let { setDate(today.plusWeeks(1), it.range) }
        // CU1: relative month / year, and "this/next weekend" → the upcoming Saturday.
        Regex("(?<=\\s|^)next\\s+month\\b", RegexOption.IGNORE_CASE).find(text)?.let { setDate(today.plusMonths(1), it.range) }
        Regex("(?<=\\s|^)next\\s+year\\b", RegexOption.IGNORE_CASE).find(text)?.let { setDate(today.plusYears(1), it.range) }
        Regex("(?<=\\s|^)(this\\s+|next\\s+)?weekend\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            var d = ((DayOfWeek.SATURDAY.value - today.dayOfWeek.value + 7) % 7)
            if (d == 0) d = 7
            setDate(today.plusDays(d.toLong()), it.range)
        }
        // CU1: "end of month" / "eom" → last day of the current month.
        Regex("(?<=\\s|^)(end\\s+of\\s+month|eom)\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            setDate(today.withDayOfMonth(today.lengthOfMonth()), it.range)
        }

        // in N days/weeks/hours/months/years — also "in a/an <unit>" (= 1).
        Regex("(?<=\\s|^)in\\s+(a|an|\\d{1,3})\\s+(hour|hours|day|days|week|weeks|month|months|year|years)\\b", RegexOption.IGNORE_CASE).find(text)?.let {
            val nRaw = it.groupValues[1].lowercase()
            val n = if (nRaw == "a" || nRaw == "an") 1L else nRaw.toLong()
            when (it.groupValues[2].lowercase().removeSuffix("s")) {
                "hour" -> { date = today; time = (time ?: now.toLocalTime()).plusHours(n) }
                "day" -> setDate(today.plusDays(n), it.range)
                "week" -> setDate(today.plusWeeks(n), it.range)
                "month" -> setDate(today.plusMonths(n), it.range)
                "year" -> setDate(today.plusYears(n), it.range)
            }
            strip.add(it.range)
        }

        // weekday name (optionally "next")
        if (date == null) {
            Regex("(?<=\\s|^)(next\\s+)?([a-z]+)\\b", RegexOption.IGNORE_CASE).findAll(text).firstOrNull { m ->
                WEEKDAYS.containsKey(m.groupValues[2].lowercase())
            }?.let { m ->
                val target = WEEKDAYS.getValue(m.groupValues[2].lowercase())
                var days = ((target.value - today.dayOfWeek.value + 7) % 7)
                if (days == 0) days = 7
                setDate(today.plusDays(days.toLong()), m.range)
            }
        }

        // month name + day  ("aug 24", "august 24")
        if (date == null) {
            Regex("(?<=\\s|^)([a-z]{3,9})\\s+(\\d{1,2})\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val mi = MONTHS.indexOfFirst { m.groupValues[1].lowercase().startsWith(it) }
                if (mi >= 0) {
                    val day = m.groupValues[2].toInt().coerceIn(1, 28)
                    var d = LocalDate.of(today.year, mi + 1, day)
                    if (d.isBefore(today)) d = d.plusYears(1)
                    setDate(d, m.range)
                }
            }
        }
        // CU1: day-of-month — "on the 15th" / "the 15th" → the 15th of this month, else next month.
        if (date == null) {
            Regex("(?<=\\s|^)(?:on\\s+)?the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                val dom = m.groupValues[1].toInt()
                if (dom in 1..31) {
                    val base = if (dom <= today.lengthOfMonth()) today.withDayOfMonth(dom) else today
                    var d = base
                    if (d.isBefore(today) || dom > today.lengthOfMonth()) {
                        val nm = today.plusMonths(1)
                        d = nm.withDayOfMonth(dom.coerceAtMost(nm.lengthOfMonth()))
                    }
                    setDate(d, m.range)
                }
            }
        }

        val dateTime: LocalDateTime?
        val hasTime: Boolean
        when {
            date != null -> { dateTime = LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT); hasTime = time != null }
            time != null -> { dateTime = LocalDateTime.of(today, time); hasTime = true }
            else -> { dateTime = null; hasTime = false }
        }

        val title = stripRanges(text, strip).replace(Regex("\\s+"), " ").trim()
        return ParsedQuickAdd(
            title = title,
            dateTime = dateTime,
            hasTime = hasTime,
            priority = priority,
            tags = tags,
            contexts = contexts,
            list = list,
            rrule = rrule,
            reminderOffsetMin = reminderOffsetMin,
        )
    }

    private fun stripRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val remove = BooleanArray(text.length)
        for (r in ranges) for (i in r) if (i in text.indices) remove[i] = true
        val sb = StringBuilder()
        for (i in text.indices) if (!remove[i]) sb.append(text[i]) else sb.append(' ')
        return sb.toString()
    }
}
