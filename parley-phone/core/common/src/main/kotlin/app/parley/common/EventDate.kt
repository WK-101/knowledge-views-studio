package app.parley.common

import java.util.Locale
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit

/**
 * A contact event date as stored by ContactsContract: "yyyy-MM-dd", or year-less "--MM-dd"
 * (also "--MMdd", "yyyyMMdd", and Android's year-1604 "no year" convention).
 */
data class EventDate(val year: Int?, val month: Int, val day: Int) {

    /** Next occurrence on or after [today] (Feb 29 falls back to Feb 28 in non-leap years). */
    fun next(today: LocalDate): LocalDate {
        fun at(y: Int): LocalDate = if (month == 2 && day == 29 && !java.time.Year.isLeap(y.toLong())) LocalDate.of(y, 2, 28) else LocalDate.of(y, month, day)
        val thisYear = at(today.year)
        return if (thisYear.isBefore(today)) at(today.year + 1) else thisYear
    }

    fun daysUntil(today: LocalDate): Long = ChronoUnit.DAYS.between(today, next(today))

    /** Age reached on the next occurrence (e.g. "turns 40"); null without a year. */
    fun turning(today: LocalDate): Int? = year?.let { next(today).year - it }

    /** Current age in whole years; null without a year. */
    fun age(today: LocalDate): Int? = year?.let { y ->
        val had = !MonthDay.of(month, if (month == 2 && day == 29) 28 else day).isAfter(MonthDay.from(today))
        today.year - y - if (had) 0 else 1
    }

    /** Storage form: "yyyy-MM-dd" or "--MM-dd". */
    fun format(): String = if (year != null) "%04d-%02d-%02d".format(Locale.ROOT, year, month, day) else "--%02d-%02d".format(Locale.ROOT, month, day)

    companion object {
        fun parse(raw: String?): EventDate? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            val m = Regex("^(\\d{4}|--|-)-?(\\d{2})-?(\\d{2})").find(s) ?: return null
            val y = m.groupValues[1].toIntOrNull()?.takeIf { it != 1604 && it > 0 }
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            if (mo !in 1..12 || d !in 1..31) return null
            return EventDate(y, mo, d)
        }
    }
}
