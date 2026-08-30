package com.todocompanion.app.domain.calendar

import com.todocompanion.app.domain.recurrence.Freq
import com.todocompanion.app.domain.recurrence.Recur
import com.todocompanion.app.domain.recurrence.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * R38 — a rules-based natural-language parser for Quick Add, in the Fantastical / KashCal tradition.
 * Deterministic, on-device, no LLM. "Lunch with Sam Friday 1pm for 90m at Cafe every week" →
 * title/date/time/duration/location/recurrence. A leading "todo"/"reminder"/"task" makes it a task.
 */
object EventParser {

    data class Draft(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val location: String = "",
        val rrule: String = "",
        val alertsMinutes: String = "",
        val isTask: Boolean = false,
    )

    private val WEEKDAYS = mapOf(
        "monday" to 1, "mon" to 1, "tuesday" to 2, "tue" to 2, "tues" to 2, "wednesday" to 3, "wed" to 3,
        "thursday" to 4, "thu" to 4, "thurs" to 4, "friday" to 5, "fri" to 5, "saturday" to 6, "sat" to 6,
        "sunday" to 7, "sun" to 7,
    )
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
        "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    fun parse(input: String, now: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), defaultDurationMin: Int = 60): Draft? {
        var text = input.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.US)

        var isTask = false
        Regex("^(todo|reminder|task|remind me to)\\s+", RegexOption.IGNORE_CASE).find(text)?.let {
            isTask = true; text = text.removeRange(it.range)
        }

        val consumed = StringBuilder(text)
        fun eat(regex: Regex): MatchResult? {
            val m = regex.find(consumed.toString()) ?: return null
            consumed.replace(m.range.first, m.range.last + 1, " ")
            return m
        }

        // ── recurrence ──
        var rrule = ""
        eat(Regex("\\bevery\\s+(day|week|month|year|weekday|weekdays|\\d+\\s+(?:days|weeks|months|years)|mon|tue|wed|thu|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE))?.let { m ->
            val g = m.groupValues[1].lowercase(Locale.US).trim()
            rrule = when {
                g == "day" -> Recurrence.encode(Recur(Freq.DAILY))
                g == "week" -> Recurrence.encode(Recur(Freq.WEEKLY))
                g == "month" -> Recurrence.encode(Recur(Freq.MONTHLY))
                g == "year" -> Recurrence.encode(Recur(Freq.YEARLY))
                g.startsWith("weekday") -> Recurrence.encode(Recur(Freq.WEEKDAYS))
                WEEKDAYS.containsKey(g) -> Recurrence.encode(Recur(Freq.WEEKLY, byDays = setOf(WEEKDAYS.getValue(g))))
                else -> {
                    val num = Regex("(\\d+)\\s+(days|weeks|months|years)").find(g)
                    if (num != null) {
                        val n = num.groupValues[1].toInt().coerceAtLeast(1)
                        when (num.groupValues[2]) {
                            "days" -> Recurrence.encode(Recur(Freq.DAILY, n)); "weeks" -> Recurrence.encode(Recur(Freq.WEEKLY, n))
                            "months" -> Recurrence.encode(Recur(Freq.MONTHLY, n)); else -> Recurrence.encode(Recur(Freq.YEARLY, n))
                        }
                    } else ""
                }
            }
        }

        // ── alert ("alert 30m", "alert 1h before") ──
        var alerts = ""
        eat(Regex("\\balert\\s+(\\d+)\\s*(m|min|mins|minutes|h|hour|hours|d|day|days)\\b", RegexOption.IGNORE_CASE))?.let { m ->
            val n = m.groupValues[1].toInt(); val unit = m.groupValues[2].lowercase(Locale.US)
            alerts = when { unit.startsWith("h") -> (n * 60).toString(); unit.startsWith("d") -> (n * 1440).toString(); else -> n.toString() }
        }

        // ── duration ("for 90m", "for 2h", "for 1.5h") ──
        var durationMin: Int? = null
        eat(Regex("\\bfor\\s+(\\d+(?:\\.\\d+)?)\\s*(m|min|mins|minutes|h|hr|hrs|hour|hours)\\b", RegexOption.IGNORE_CASE))?.let { m ->
            val v = m.groupValues[1].toDouble(); val unit = m.groupValues[2].lowercase(Locale.US)
            durationMin = if (unit.startsWith("h")) (v * 60).toInt() else v.toInt()
        }

        // ── time range ("2-3pm", "1:30pm to 3pm") or single time ("3pm", "15:30") ──
        var startTime: LocalTime? = null
        var endTime: LocalTime? = null
        eat(Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*(?:-|–|to|until)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE))?.let { m ->
            val e = parseClock(m.groupValues[4], m.groupValues[5], m.groupValues[6])
            val s = parseClock(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifBlank { m.groupValues[6] })
            startTime = s; endTime = e
        }
        if (startTime == null) eat(Regex("\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE))?.let { m ->
            startTime = parseClock(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        if (startTime == null) eat(Regex("\\b(?:at\\s+)(\\d{1,2}):(\\d{2})\\b"))?.let { m ->
            startTime = LocalTime.of(m.groupValues[1].toInt().coerceIn(0, 23), m.groupValues[2].toInt().coerceIn(0, 59))
        }

        // ── date ──
        var date: LocalDate? = null
        eat(Regex("\\btoday\\b", RegexOption.IGNORE_CASE))?.let { date = now }
        if (date == null) eat(Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE))?.let { date = now.plusDays(1) }
        if (date == null) eat(Regex("\\b(next\\s+)?(monday|mon|tuesday|tue|tues|wednesday|wed|thursday|thu|thurs|friday|fri|saturday|sat|sunday|sun)\\b", RegexOption.IGNORE_CASE))?.let { m ->
            val wd = WEEKDAYS[m.groupValues[2].lowercase(Locale.US)]!!
            var d = now.plusDays(1)
            while (d.dayOfWeek.value != wd) d = d.plusDays(1)
            if (m.groupValues[1].isNotBlank()) { /* "next" — already the coming one; keep */ }
            date = d
        }
        if (date == null) eat(Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b"))?.let { m ->
            val mo = m.groupValues[1].toIntOrNull(); val da = m.groupValues[2].toIntOrNull()
            val yr = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: now.year
            if (mo != null && da != null && mo in 1..12 && da in 1..31) date = runCatching { LocalDate.of(yr, mo, da) }.getOrNull()
        }
        if (date == null) eat(Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE))?.let { m ->
            val mo = MONTHS[m.groupValues[1].lowercase(Locale.US)]; val da = m.groupValues[2].toIntOrNull()
            if (mo != null && da != null) { var d = runCatching { LocalDate.of(now.year, mo, da) }.getOrNull(); if (d != null && d.isBefore(now)) d = d.plusYears(1); date = d }
        }

        // ── location ("at Cafe", "@ Cafe") — take the trailing phrase ──
        var location = ""
        eat(Regex("\\s(?:at|@)\\s+([A-Za-z0-9][A-Za-z0-9 '&.-]{1,40}?)\\s*$"))?.let { m -> location = m.groupValues[1].trim() }

        // Whatever's left is the title.
        val title = consumed.toString().replace(Regex("\\s+"), " ").trim().trim(',', '-', ':').ifBlank { input.trim() }

        // If nothing time/date/recurrence-like was found, this isn't a structured entry.
        if (date == null && startTime == null && rrule.isBlank() && durationMin == null) return null

        val day = date ?: now
        val allDay = startTime == null
        val startMillis: Long; val endMillis: Long
        if (allDay) {
            startMillis = day.atStartOfDay(zone).toInstant().toEpochMilli()
            endMillis = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        } else {
            val st = startTime!!
            startMillis = day.atTime(st).atZone(zone).toInstant().toEpochMilli()
            val dur = durationMin ?: endTime?.let { ((it.toSecondOfDay() - st.toSecondOfDay()) / 60).takeIf { d -> d > 0 } } ?: defaultDurationMin
            endMillis = startMillis + dur * 60000L
        }
        return Draft(title, startMillis, endMillis, allDay, location, rrule, alerts, isTask)
    }

    private fun parseClock(h: String, m: String, ampm: String): LocalTime {
        var hour = h.toIntOrNull() ?: 0
        val min = m.toIntOrNull() ?: 0
        val ap = ampm.lowercase(Locale.US)
        if (ap == "pm" && hour < 12) hour += 12
        if (ap == "am" && hour == 12) hour = 0
        return LocalTime.of(hour.coerceIn(0, 23), min.coerceIn(0, 59))
    }
}
