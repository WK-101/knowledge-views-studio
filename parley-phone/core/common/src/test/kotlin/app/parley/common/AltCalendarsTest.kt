package app.parley.common

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rules for dates kept by another calendar, with a made-up calendar: 12 months of 30 days except month 2
 * (29 days in even years), and a leap month 4 in years divisible by 3; year 1, month 1, day 1 is 2000-01-01. Real
 * calendars are checked against known dates in core/data (IcuCalendarsTest).
 */
class AltCalendarsTest {
    private object Toy : CalendarConverter {
        private fun months(y: Int) = buildList {
            for (m in 1..12) {
                add(Triple(m, false, if (m == 2 && y % 2 == 0) 29 else 30))
                if (m == 4 && y % 3 == 0) add(Triple(4, true, 30))
            }
        }
        private val start = LocalDate.of(2000, 1, 1)

        override fun toAlt(calendar: AltCalendar, date: LocalDate): AltDay {
            var days = java.time.temporal.ChronoUnit.DAYS.between(start, date)
            var y = 1
            while (true) {
                for ((m, leap, len) in months(y)) {
                    if (days < len) return AltDay(y, m, days.toInt() + 1, leap)
                    days -= len
                }
                y++
            }
        }

        override fun toGregorian(calendar: AltCalendar, day: AltDay): LocalDate? {
            var days = 0L
            for (y in 1 until day.year) days += months(y).sumOf { it.third }
            for ((m, leap, len) in months(day.year)) {
                if (m == day.month && leap == day.leap) return if (day.day in 1..len) start.plusDays(days + day.day - 1) else null
                days += len
            }
            return null
        }
    }

    @Test fun the_next_occurrence_is_the_same_month_and_day_of_the_calendar() {
        val born = Toy.toGregorian(AltCalendar.CHINESE, AltDay(1, 5, 10))!!
        val today = Toy.toGregorian(AltCalendar.CHINESE, AltDay(4, 1, 1))!!
        assertEquals(Toy.toGregorian(AltCalendar.CHINESE, AltDay(4, 5, 10)), AltCalendars.next(born, AltCalendar.CHINESE, today, Toy))
        // On the day itself it's today, not next year.
        val onDay = Toy.toGregorian(AltCalendar.CHINESE, AltDay(4, 5, 10))!!
        assertEquals(onDay, AltCalendars.next(born, AltCalendar.CHINESE, onDay, Toy))
    }

    @Test fun a_missing_day_or_leap_month_falls_back() {
        // Born on the 30th of month 2 in an odd year: in even years month 2 has 29 days.
        val born = AltDay(1, 2, 30)
        assertEquals(Toy.toGregorian(AltCalendar.CHINESE, AltDay(2, 2, 29)), AltCalendars.occurrence(born, 2, AltCalendar.CHINESE, Toy))
        // Born in leap month 4 (year 3): in year 4 there's only the ordinary month 4.
        val leap = AltDay(3, 4, 7, leap = true)
        assertEquals(Toy.toGregorian(AltCalendar.CHINESE, AltDay(4, 4, 7)), AltCalendars.occurrence(leap, 4, AltCalendar.CHINESE, Toy))
        assertEquals(Toy.toGregorian(AltCalendar.CHINESE, AltDay(6, 4, 7, leap = true)), AltCalendars.occurrence(leap, 6, AltCalendar.CHINESE, Toy))
    }

    @Test fun effective_moves_the_date_to_its_next_day_and_keeps_the_year() {
        val born = Toy.toGregorian(AltCalendar.HEBREW, AltDay(1, 5, 10))!!
        val today = Toy.toGregorian(AltCalendar.HEBREW, AltDay(4, 1, 1))!!
        val next = AltCalendars.next(born, AltCalendar.HEBREW, today, Toy)!!
        val e = AltCalendars.effective(EventDate(born.year, born.monthValue, born.dayOfMonth), AltCalendar.HEBREW, today, Toy)!!
        assertEquals(born.year, e.year)
        assertEquals(next, e.next(today))
    }

    @Test fun plain_dates_and_dates_without_a_year_are_unchanged() {
        val d = EventDate(1990, 3, 12)
        val today = LocalDate.of(2026, 1, 1)
        assertSame(d, AltCalendars.effective(d, null, today, Toy))
        assertSame(d, AltCalendars.effective(d, AltCalendar.HIJRI, today, null))
        val noYear = EventDate(null, 3, 12)
        assertSame(noYear, AltCalendars.effective(noYear, AltCalendar.HIJRI, today, Toy))
    }

    @Test fun calendars_are_read_by_their_cldr_names() {
        assertEquals(AltCalendar.CHINESE, AltCalendar.byKey("Chinese"))
        assertEquals(AltCalendar.HIJRI, AltCalendar.byKey("islamic-civil"))
        assertEquals(AltCalendar.HIJRI, AltCalendar.byKey("islamic-umalqura"))
        assertNull(AltCalendar.byKey("gregorian"))
        assertNull(AltCalendar.byKey(null))
    }
}
