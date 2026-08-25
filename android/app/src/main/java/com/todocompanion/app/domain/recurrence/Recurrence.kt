package com.todocompanion.app.domain.recurrence

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * An offline recurrence model — richer than a bare interval but lighter than full RFC-5545.
 * Serialised compactly into [com.todocompanion.app.data.entity.TaskEntity.rrule], e.g.
 * `FREQ=WEEKLY;INT=1;DAYS=1,3,5;COUNT=10` or `FREQ=MONTHLY;INT=2;UNTIL=20270101`.
 *
 * Supports: daily / every-weekday / weekly (optionally on specific weekdays) / monthly / yearly,
 * an interval, and an end condition (never, until a date, or after N more occurrences).
 */
enum class Freq { DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }

data class Recur(
    val freq: Freq,
    val interval: Int = 1,
    val byDays: Set<Int> = emptySet(),   // ISO weekday 1=Mon..7=Sun (WEEKLY only)
    val untilEpochDay: Long? = null,
    val count: Int? = null,
    // Monthly "nth weekday" (e.g. 3rd Tuesday, last Friday): pos 1..4 or -1 for last, + ISO weekday.
    val bySetPos: Int? = null,
    val byWeekday: Int? = null,
    // Regenerate the next occurrence from the completion date rather than the fixed schedule.
    val fromCompletion: Boolean = false,
    // Monthly "first working day of the month" (Mon–Fri), overriding day-of-month.
    val firstWorkday: Boolean = false,
    // What happens to subtasks when the task recurs: "all" reset all, "allDone" reset only if all
    // were completed, "keep" leave them as they are.
    val subtaskReset: String = "all",
)

object Recurrence {

    /** Quick presets for the editor's frequency row. */
    val PRESETS: List<Pair<String?, String>> = listOf(
        null to "Does not repeat",
        encode(Recur(Freq.DAILY)) to "Daily",
        encode(Recur(Freq.WEEKDAYS)) to "Every weekday",
        encode(Recur(Freq.WEEKLY)) to "Weekly",
        encode(Recur(Freq.WEEKLY, 2)) to "Every 2 weeks",
        encode(Recur(Freq.MONTHLY)) to "Monthly",
        encode(Recur(Freq.YEARLY)) to "Yearly",
    )

    fun encode(r: Recur): String = buildString {
        append("FREQ=").append(r.freq.name)
        append(";INT=").append(r.interval)
        if (r.byDays.isNotEmpty()) append(";DAYS=").append(r.byDays.sorted().joinToString(","))
        r.bySetPos?.let { append(";POS=").append(it) }
        r.byWeekday?.let { append(";WD=").append(it) }
        if (r.firstWorkday) append(";FWD=1")
        if (r.subtaskReset != "all") append(";SR=").append(r.subtaskReset)
        if (r.fromCompletion) append(";COMP=1")
        r.untilEpochDay?.let { append(";UNTIL=").append(it) }
        r.count?.let { append(";COUNT=").append(it) }
    }

    fun parse(rule: String?): Recur? {
        if (rule.isNullOrBlank()) return null
        // Back-compat with the old "FREQ:INTERVAL" colon form.
        if (!rule.contains("=") && rule.contains(":")) {
            val (f, i) = rule.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
            val freq = runCatching { Freq.valueOf(f.uppercase()) }.getOrNull() ?: return null
            return Recur(freq, i)
        }
        val m = rule.split(";").mapNotNull { p -> p.split("=").takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val freq = runCatching { Freq.valueOf(m["FREQ"]?.uppercase() ?: return null) }.getOrNull() ?: return null
        return Recur(
            freq = freq,
            interval = m["INT"]?.toIntOrNull() ?: 1,
            byDays = m["DAYS"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet(),
            bySetPos = m["POS"]?.toIntOrNull(),
            byWeekday = m["WD"]?.toIntOrNull(),
            firstWorkday = m["FWD"] == "1",
            subtaskReset = m["SR"] ?: "all",
            fromCompletion = m["COMP"] == "1",
            untilEpochDay = m["UNTIL"]?.toLongOrNull(),
            count = m["COUNT"]?.toIntOrNull(),
        )
    }

    fun label(rule: String?): String? {
        val r = parse(rule) ?: return null
        val every = if (r.interval == 1) "" else "${r.interval} "
        val base = when (r.freq) {
            Freq.DAILY -> if (r.interval == 1) "Daily" else "Every $every days".trim()
            Freq.WEEKDAYS -> "Every weekday"
            Freq.WEEKLY -> {
                val days = if (r.byDays.isEmpty()) "" else " on " + r.byDays.sorted().joinToString(", ") { DAY_ABBR[it - 1] }
                (if (r.interval == 1) "Weekly" else "Every ${every}weeks") + days
            }
            Freq.MONTHLY -> {
                val head = if (r.interval == 1) "Monthly" else "Every ${every}months"
                when {
                    r.firstWorkday -> "$head on the first working day"
                    r.bySetPos != null && r.byWeekday != null -> "$head on the ${posLabel(r.bySetPos)} ${DAY_ABBR[r.byWeekday - 1]}"
                    else -> head
                }
            }
            Freq.YEARLY -> if (r.interval == 1) "Yearly" else "Every ${every}years"
        }
        val comp = if (r.fromCompletion) ", after completion" else ""
        val end = when {
            r.count != null -> ", ×${r.count}"
            r.untilEpochDay != null -> ", until " + java.time.LocalDate.ofEpochDay(r.untilEpochDay).toString()
            else -> ""
        }
        return base + comp + end
    }

    fun posLabel(pos: Int): String = when (pos) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; 4 -> "4th"; else -> "last" }

    private val DAY_ABBR = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** First working day (Mon–Fri) of the given year-month. */
    private fun firstWorkdayOf(ym: java.time.YearMonth): java.time.LocalDate {
        var d = ym.atDay(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    /** The date of the nth (or last) [weekday] in the given year-month. */
    private fun nthWeekdayOf(ym: java.time.YearMonth, pos: Int, weekday: Int): java.time.LocalDate {
        if (pos < 0) {
            var d = ym.atEndOfMonth()
            while (d.dayOfWeek.value != weekday) d = d.minusDays(1)
            return d
        }
        var d = ym.atDay(1)
        while (d.dayOfWeek.value != weekday) d = d.plusDays(1)
        val candidate = d.plusWeeks((pos - 1).toLong())
        return if (candidate.month == ym.month) candidate else d // clamp: fall back to first if nth overflows
    }

    /** Next occurrence strictly after [fromMillis], preserving time-of-day. Ignores end conditions. */
    fun next(rule: String, fromMillis: Long, zone: ZoneId): Long {
        val r = parse(rule) ?: return fromMillis
        val dt = Instant.ofEpochMilli(fromMillis).atZone(zone)
        val nd = when (r.freq) {
            Freq.DAILY -> dt.plusDays(r.interval.toLong())
            Freq.MONTHLY -> {
                val ym = java.time.YearMonth.from(dt).plusMonths(r.interval.toLong())
                when {
                    r.firstWorkday -> firstWorkdayOf(ym).atTime(dt.toLocalTime()).atZone(zone)
                    r.bySetPos != null && r.byWeekday != null -> nthWeekdayOf(ym, r.bySetPos, r.byWeekday).atTime(dt.toLocalTime()).atZone(zone)
                    else -> dt.plusMonths(r.interval.toLong())
                }
            }
            Freq.YEARLY -> dt.plusYears(r.interval.toLong())
            Freq.WEEKDAYS -> {
                var d = dt.plusDays(1)
                while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                d
            }
            Freq.WEEKLY -> {
                if (r.byDays.isEmpty()) dt.plusWeeks(r.interval.toLong())
                else {
                    // Next selected weekday later this week; else jump `interval` weeks to the first selected day.
                    var d = dt.plusDays(1)
                    var hops = 0
                    while (d.dayOfWeek.value !in r.byDays && hops < 7) { d = d.plusDays(1); hops++ }
                    if (d.dayOfWeek.value !in r.byDays) dt.plusWeeks(r.interval.toLong()) else d
                }
            }
        }
        return nd.toInstant().toEpochMilli()
    }

    /**
     * Roll a repeating task forward on completion. Returns the next due-millis and the (possibly
     * updated) rule, or `null` next when the recurrence has ended and the task should just complete.
     */
    fun advance(rule: String, fromMillis: Long, zone: ZoneId, completionMillis: Long? = null): Pair<Long?, String?> {
        val r = parse(rule) ?: return null to null
        // "After completion": count the interval from when it was actually completed, keeping the
        // original time-of-day. Otherwise roll forward on the fixed schedule from the due date.
        val base = if (r.fromCompletion && completionMillis != null) {
            val dueTime = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalTime()
            val compDate = Instant.ofEpochMilli(completionMillis).atZone(zone).toLocalDate()
            compDate.atTime(dueTime).atZone(zone).toInstant().toEpochMilli()
        } else fromMillis
        val nextMs = next(rule, base, zone)
        val nextDay = Instant.ofEpochMilli(nextMs).atZone(zone).toLocalDate().toEpochDay()
        if (r.untilEpochDay != null && nextDay > r.untilEpochDay) return null to null
        if (r.count != null) {
            if (r.count <= 1) return null to null
            return nextMs to encode(r.copy(count = r.count - 1))
        }
        return nextMs to rule
    }
}
