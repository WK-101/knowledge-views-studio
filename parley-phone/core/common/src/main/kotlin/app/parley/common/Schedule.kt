package app.parley.common

import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/**
 * A weekly time window: [days] is a bit set (bit 0 = Monday … bit 6 = Sunday), times are minutes after
 * midnight. A window whose end is before its start crosses midnight ("22:00–07:00"): the part after
 * midnight belongs to the day it started on. start == end means the whole day.
 */
@Serializable
data class Schedule(val days: Int = ALL_DAYS, val startMinute: Int = 0, val endMinute: Int = 0) {

    fun hasDay(d: DayOfWeek): Boolean = days and (1 shl (d.value - 1)) != 0

    fun isActive(day: DayOfWeek, minuteOfDay: Int): Boolean {
        if (days and ALL_DAYS == 0) return false
        val start = startMinute.coerceIn(0, 1439)
        val end = endMinute.coerceIn(0, 1440)
        return when {
            start == end || (start == 0 && end >= 1440) -> hasDay(day)
            start < end -> hasDay(day) && minuteOfDay in start until end
            // Crosses midnight.
            else -> (hasDay(day) && minuteOfDay >= start) || (hasDay(day.minus(1)) && minuteOfDay < end)
        }
    }

    /** "Mon–Fri 22:00–07:00", "Every day", "Weekends all day". */
    fun describe(): String {
        val d = describeDays()
        val allDay = startMinute == endMinute
        return if (allDay) "$d, all day" else "$d ${hm(startMinute)}–${hm(endMinute)}"
    }

    fun describeDays(): String = when (days and ALL_DAYS) {
        ALL_DAYS -> "Every day"
        WEEKDAYS -> "Mon–Fri"
        WEEKEND -> "Weekends"
        0 -> "Never"
        else -> DayOfWeek.entries.filter { hasDay(it) }.joinToString(", ") { SHORT[it.value - 1] }
    }

    fun encode(): String = "$days:$startMinute:$endMinute"

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
        private val SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun hm(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

        fun decode(s: String?): Schedule? {
            if (s.isNullOrBlank()) return null
            val p = s.split(':').mapNotNull { it.toIntOrNull() }
            if (p.size != 3) return null
            return Schedule(p[0], p[1], p[2])
        }
    }
}
