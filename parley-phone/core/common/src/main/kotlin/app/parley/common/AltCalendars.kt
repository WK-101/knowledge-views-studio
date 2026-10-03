package app.parley.common

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Calendars a yearly date can follow instead of the Gregorian one: a birthday kept by the Chinese lunar, Hebrew
 * or Hijri (Islamic, Umm al-Qura) calendar falls on a different Gregorian day each year.
 *
 * Storage: the event row keeps its Gregorian date (the day it happened, which every other app shows correctly) and
 * Parley marks the calendar it recurs by in the row's DATA14 ([COLUMN]) with [key], the calendar's CLDR / RFC 7529
 * name; vCard carries it as the date's `CALSCALE` parameter. A date without a year can't say which day of another
 * calendar it was, so the mark only counts with a year.
 */
enum class AltCalendar(val key: String) {
    CHINESE("chinese"),
    HEBREW("hebrew"),
    HIJRI("islamic-umalqura"),
    ;

    companion object {
        /** The event row's column that holds the calendar's [key]. Event uses DATA1–DATA3 only. */
        const val COLUMN = "data14"

        /** The calendar named [key] ("islamic", "islamic-civil"… are read as Hijri); null for Gregorian or unknown. */
        fun byKey(key: String?): AltCalendar? {
            val k = key?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == k } ?: if (k.startsWith("islamic")) HIJRI else null
        }
    }
}

/**
 * A day of an [AltCalendar]: [year] as the calendar counts it, [month] 1-based in the calendar's own numbering
 * (ICU's, for Hebrew: Tishri = 1 … Adar I = 6, Adar = 7, Nisan = 8 …), [leap] for a Chinese leap month.
 */
data class AltDay(val year: Int, val month: Int, val day: Int, val leap: Boolean = false)

/** The calendar arithmetic; Android's ICU calendars implement it (android.icu, in core/data). */
interface CalendarConverter {
    fun toAlt(calendar: AltCalendar, date: LocalDate): AltDay

    /** The Gregorian day of [day], or null when that day doesn't exist in its year (a 30th in a 29-day month…). */
    fun toGregorian(calendar: AltCalendar, day: AltDay): LocalDate?
}

/** When a date kept by another calendar comes round again, and how the rest of Parley sees it. */
object AltCalendars {
    /** Hebrew Adar I and Adar, in [AltDay.month]'s numbering. */
    private const val ADAR_I = 6
    private const val ADAR = 7

    /**
     * The anniversary of [original] in [year] of [calendar]: the same month and day; a leap month that year
     * doesn't have becomes the ordinary month, Adar I becomes Adar in a common Hebrew year, and a 30th that the
     * month lacks becomes its last day (the 29th), as these calendars' customs have it.
     */
    fun occurrence(original: AltDay, year: Int, calendar: AltCalendar, converter: CalendarConverter): LocalDate? {
        val months = buildList {
            add(original.month to original.leap)
            if (original.leap) add(original.month to false)
            if (calendar == AltCalendar.HEBREW && original.month == ADAR_I) add(ADAR to false)
        }
        for ((m, leap) in months) {
            for (d in original.day downTo maxOf(1, original.day - 1)) {
                converter.toGregorian(calendar, AltDay(year, m, d, leap))?.let { return it }
            }
        }
        return null
    }

    /** One time a date comes round in its calendar: the Gregorian [date], in [year] as that calendar counts it. */
    data class Round(val date: LocalDate, val year: Int)

    /** The first Gregorian day on or after [today] when [original] comes round in [calendar]. */
    fun next(original: LocalDate, calendar: AltCalendar, today: LocalDate, converter: CalendarConverter): LocalDate? =
        nextRound(original, calendar, today, converter)?.date

    /** [next], with the year of [calendar] it falls in. */
    fun nextRound(original: LocalDate, calendar: AltCalendar, today: LocalDate, converter: CalendarConverter): Round? {
        val born = converter.toAlt(calendar, original)
        val now = converter.toAlt(calendar, today).year
        return (now - 1..now + 2).asSequence()
            .mapNotNull { y -> occurrence(born, y, calendar, converter)?.let { Round(it, y) } }
            .filter { !it.date.isBefore(today) && !it.date.isBefore(original) }
            .minByOrNull { it.date }
    }

    /**
     * The next time a date is due, as reminders and lists count it.
     *
     * @property date the date as the rest of Parley handles dates (days until, reminders): for one kept by another
     *   calendar, the same date moved to its next Gregorian day, so `date.next(today)` is that day.
     * @property round names this one occurrence: the Gregorian year it falls in, or for another calendar that
     *   calendar's own year (a Hijri date can come round twice in one Gregorian year, a lunar New Year date in
     *   January or February of either).
     * @property turning the age or the years reached then, counted in the date's own calendar; null without a year.
     */
    data class Due(val date: EventDate, val round: String, val turning: Int?)

    /** When [date], kept by [calendar] (null: Gregorian), is next due; null when that is more than a year away. */
    fun due(date: EventDate, calendar: AltCalendar?, today: LocalDate, converter: CalendarConverter?): Due? {
        val y = date.year
        val original = y?.let { runCatching { LocalDate.of(it, date.month, date.day) }.getOrNull() }
        if (calendar == null || converter == null || original == null) return plain(date, today)
        val round = nextRound(original, calendar, today, converter) ?: return plain(date, today)
        // A long lunar year: nothing is due before then.
        if (ChronoUnit.DAYS.between(today, round.date) >= YEAR_DAYS) return null
        val moved = EventDate(y, round.date.monthValue, round.date.dayOfMonth)
        if (moved.next(today) != round.date) return null
        return Due(moved, "${calendar.key}-${round.year}", round.year - converter.toAlt(calendar, original).year)
    }

    private fun plain(date: EventDate, today: LocalDate) = Due(date, date.next(today).year.toString(), date.turning(today))

    /**
     * [date] as the rest of Parley handles dates ([Due.date]). Null when its next day is more than a year away.
     * Plain dates come back unchanged.
     */
    fun effective(date: EventDate, calendar: AltCalendar?, today: LocalDate, converter: CalendarConverter?): EventDate? =
        due(date, calendar, today, converter)?.date

    private const val YEAR_DAYS = 365L
}
