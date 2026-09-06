package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.HabitEntity

/**
 * Turns a natural-language line — "meditate 10 min every morning", "read 20 pages daily",
 * "gym 3x a week", "journal every evening at 9pm" — into a habit draft, mirroring the task
 * quick-add. Pure and offline; the user can still refine everything in the full editor.
 */
object HabitQuickParser {
    fun parse(raw: String): HabitEntity {
        var t = " " + raw.trim().lowercase() + " "
        var freqType = HabitStats.FREQ_WEEKLY
        var freqParam = 0
        var scheduleDays = ""
        var unit: String? = null
        var target = 1
        val reminderMins = sortedSetOf<Int>()

        // Quantity + unit: "10 min", "20 pages", "8 glasses".
        Regex("""\b(\d{1,4})\s*(min(?:ute)?s?|pages?|glasses|steps|reps?|km|miles?|cups?|pomodoros?)\b""").find(t)?.let { m ->
            target = m.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
            unit = m.groupValues[2].let { u -> when {
                u.startsWith("min") -> "min"; u.startsWith("page") -> "pages"; u.startsWith("rep") -> "reps"
                u.startsWith("cup") -> "cups"; u.startsWith("pomodoro") -> "pomodoros"; else -> u
            } }
            t = t.replace(m.value, " ")
        }

        // Frequency.
        Regex("""\b(\d{1,2})\s*x\s*(?:/|per|a)?\s*week\b|\b(\d{1,2})\s*times?\s*(?:a|per)\s*week\b""").find(t)?.let { m ->
            freqType = HabitStats.FREQ_TIMES_WEEK
            freqParam = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()?.coerceIn(1, 7) ?: 3
            t = t.replace(m.value, " ")
        }
        Regex("""\b(\d{1,2})\s*x\s*(?:/|per|a)?\s*month\b|\b(\d{1,2})\s*times?\s*(?:a|per)\s*month\b""").find(t)?.let { m ->
            freqType = HabitStats.FREQ_TIMES_MONTH
            freqParam = (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()?.coerceIn(1, 30) ?: 4
            t = t.replace(m.value, " ")
        }
        Regex("""\bevery\s+(\d{1,3})\s*days?\b""").find(t)?.let { m ->
            freqType = HabitStats.FREQ_INTERVAL; freqParam = m.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 2
            t = t.replace(m.value, " ")
        }
        if (freqParam == 0) when {
            Regex("""\bweekdays?\b""").containsMatchIn(t) -> { scheduleDays = "1,2,3,4,5"; t = t.replace(Regex("""\bweekdays?\b"""), " ") }
            Regex("""\bweekends?\b""").containsMatchIn(t) -> { scheduleDays = "6,7"; t = t.replace(Regex("""\bweekends?\b"""), " ") }
            Regex("""\b(every\s*day|daily|each\s*day)\b""").containsMatchIn(t) -> { t = t.replace(Regex("""\b(every\s*day|daily|each\s*day)\b"""), " ") }
        }
        // Named weekdays: "on mon, wed, fri". Anchor as a whole weekday word (optionally the full name /
        // plural) so "every month", "money", "sunny" don't spuriously set — or strip — a weekday.
        val dayMap = mapOf("mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6, "sun" to 7)
        val dayRegex = mapOf(
            "mon" to """\bmon(day)?s?\b""", "tue" to """\btue(s|sday)?s?\b""", "wed" to """\bwed(nesday)?s?\b""",
            "thu" to """\bthu(r|rs|rsday)?s?\b""", "fri" to """\bfri(day)?s?\b""", "sat" to """\bsat(urday)?s?\b""", "sun" to """\bsun(day)?s?\b""",
        )
        val named = dayMap.filter { (k, _) -> Regex(dayRegex.getValue(k), RegexOption.IGNORE_CASE).containsMatchIn(t) }.values.sorted()
        if (freqParam == 0 && scheduleDays.isBlank() && named.isNotEmpty()) {
            scheduleDays = named.joinToString(","); dayRegex.values.forEach { t = t.replace(Regex(it, RegexOption.IGNORE_CASE), " ") }
        }

        // Reminder time-of-day.
        Regex("""\bat\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""").find(t)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues[3]
            if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
            if (h in 0..23) reminderMins.add(h * 60 + min)
            t = t.replace(m.value, " ")
        }
        when {
            Regex("""\bmorning\b""").containsMatchIn(t) -> { if (reminderMins.isEmpty()) reminderMins.add(8 * 60); t = t.replace(Regex("""\bmornings?\b"""), " ") }
            Regex("""\b(evening|night)\b""").containsMatchIn(t) -> { if (reminderMins.isEmpty()) reminderMins.add(20 * 60); t = t.replace(Regex("""\b(evenings?|nights?)\b"""), " ") }
            Regex("""\b(noon|afternoon)\b""").containsMatchIn(t) -> { if (reminderMins.isEmpty()) reminderMins.add(13 * 60); t = t.replace(Regex("""\b(noon|afternoons?)\b"""), " ") }
        }

        // Clean leftover filler and title-case the name.
        val name = t.replace(Regex("""\b(every|each|per|a|at|on|in|the|to)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
            .split(" ").filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        return HabitEntity(
            id = "", name = name.ifBlank { raw.trim() }, createdAt = 0L,
            unit = unit, targetPerDay = target,
            freqType = freqType, freqParam = freqParam, scheduleDays = scheduleDays,
            reminderTimes = reminderMins.joinToString(","),
        )
    }
}
