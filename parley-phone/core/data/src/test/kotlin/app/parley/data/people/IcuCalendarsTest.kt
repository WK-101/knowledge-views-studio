package app.parley.data.people

import app.parley.common.AltCalendar
import app.parley.common.AltCalendars
import app.parley.common.AltDay
import app.parley.common.EventDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android's ICU calendars behind dates kept by another calendar, against well-known dates. */
@RunWith(RobolectricTestRunner::class)
class IcuCalendarsTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun chinese_new_year() {
        assertEquals(Triple(1, 1, false), IcuCalendars.toAlt(AltCalendar.CHINESE, d("2025-01-29")).let { Triple(it.month, it.day, it.leap) })
        assertEquals(1 to 1, IcuCalendars.toAlt(AltCalendar.CHINESE, d("2026-02-17")).let { it.month to it.day })
        // Born on Chinese New Year 1990 (27 January): the lunar birthday next falls on New Year 2026.
        assertEquals(d("2026-02-17"), AltCalendars.next(d("1990-01-27"), AltCalendar.CHINESE, d("2025-06-01"), IcuCalendars))
        assertEquals(d("2025-01-29"), AltCalendars.next(d("1990-01-27"), AltCalendar.CHINESE, d("2025-01-01"), IcuCalendars))
    }

    @Test fun a_chinese_leap_month_birthday_falls_in_the_ordinary_month() {
        // 1 April 2023 is day 11 of 2023's leap second month; 2024 has no leap month 2.
        val born = IcuCalendars.toAlt(AltCalendar.CHINESE, d("2023-04-01"))
        assertEquals(Triple(2, 11, true), Triple(born.month, born.day, born.leap))
        assertEquals(d("2024-03-20"), AltCalendars.next(d("2023-04-01"), AltCalendar.CHINESE, d("2024-01-01"), IcuCalendars))
    }

    @Test fun rosh_hashanah() {
        assertEquals(AltDay(5786, 1, 1), IcuCalendars.toAlt(AltCalendar.HEBREW, d("2025-09-23")))
        assertEquals(d("2026-09-12"), IcuCalendars.toGregorian(AltCalendar.HEBREW, AltDay(5787, 1, 1)))
        // Born on Rosh Hashanah 5761 (30 September 2000).
        assertEquals(d("2025-09-23"), AltCalendars.next(d("2000-09-30"), AltCalendar.HEBREW, d("2025-01-01"), IcuCalendars))
        assertEquals(d("2026-09-12"), AltCalendars.next(d("2000-09-30"), AltCalendar.HEBREW, d("2025-09-24"), IcuCalendars))
    }

    @Test fun born_in_adar_i_keeps_the_day_in_adar_of_a_common_year() {
        // 20 February 2024 is 11 Adar I 5784; 5785 has one Adar, whose 11th is 11 March 2025.
        assertEquals(AltDay(5784, 6, 11), IcuCalendars.toAlt(AltCalendar.HEBREW, d("2024-02-20")))
        assertEquals(d("2025-03-11"), AltCalendars.next(d("2024-02-20"), AltCalendar.HEBREW, d("2024-12-01"), IcuCalendars))
    }

    @Test fun eid_dates_by_umm_al_qura() {
        // Eid al-Fitr 1446 (1 Shawwal) and Eid al-Adha 1446 (10 Dhu al-Hijjah).
        assertEquals(AltDay(1446, 10, 1), IcuCalendars.toAlt(AltCalendar.HIJRI, d("2025-03-30")))
        assertEquals(d("2025-06-06"), IcuCalendars.toGregorian(AltCalendar.HIJRI, AltDay(1446, 12, 10)))
        // A Hijri birthday comes about eleven days earlier each Gregorian year.
        val next = AltCalendars.next(d("2025-03-30"), AltCalendar.HIJRI, d("2025-04-01"), IcuCalendars)!!
        assertEquals(AltDay(1447, 10, 1), IcuCalendars.toAlt(AltCalendar.HIJRI, next))
        assertEquals(2026, next.year)
    }

    @Test fun a_day_a_month_lacks_is_no_day() {
        assertEquals(null, IcuCalendars.toGregorian(AltCalendar.HIJRI, AltDay(1446, 1, 31)))
        assertEquals(null, IcuCalendars.toGregorian(AltCalendar.HEBREW, AltDay(5785, 6, 1)))
    }

    /**
     * For [years] after [born] in [calendar]: every occurrence is its own occasion and the age counts that calendar's
     * years. Returns how many times the Gregorian count ("next year minus birth year") would have been wrong.
     */
    private fun checkAges(calendar: AltCalendar, born: AltDay, years: Int): Int {
        val bornDay = IcuCalendars.toGregorian(calendar, born)!!
        val stored = EventDate(bornDay.year, bornDay.monthValue, bornDay.dayOfMonth)
        val rounds = HashSet<String>()
        var gregorianWrong = 0
        for (y in born.year + 1..born.year + years) {
            val day = AltCalendars.occurrence(born, y, calendar, IcuCalendars)!!
            val due = AltCalendars.due(stored, calendar, day, IcuCalendars)!!
            assertEquals("$calendar $y", day, due.date.next(day))
            assertEquals("$calendar $y", y - born.year, due.turning)
            assertTrue("$calendar $y is its own occasion", rounds.add(due.round))
            if (stored.turning(day) != due.turning) gregorianWrong++
        }
        return gregorianWrong
    }

    @Test fun a_hijri_birthday_twice_in_2025_is_two_occasions_with_two_ages() {
        // 5 Rajab falls on 5 January 2025 (1446) and again on 25 December 2025 (1447).
        val first = IcuCalendars.toGregorian(AltCalendar.HIJRI, AltDay(1446, 7, 5))!!
        val second = IcuCalendars.toGregorian(AltCalendar.HIJRI, AltDay(1447, 7, 5))!!
        assertEquals(2025, first.year)
        assertEquals(2025, second.year)
        val born = IcuCalendars.toGregorian(AltCalendar.HIJRI, AltDay(1420, 7, 5))!!
        val stored = EventDate(born.year, born.monthValue, born.dayOfMonth)
        val a = AltCalendars.due(stored, AltCalendar.HIJRI, first, IcuCalendars)!!
        val b = AltCalendars.due(stored, AltCalendar.HIJRI, first.plusDays(1), IcuCalendars)!!
        assertEquals(second, b.date.next(first.plusDays(1)))
        assertNotEquals(a.round, b.round)
        assertEquals(26, a.turning)
        assertEquals(27, b.turning)
        // Over a lifetime the Hijri age runs ahead of the Gregorian count.
        assertTrue(checkAges(AltCalendar.HIJRI, AltDay(1400, 7, 5), 60) > 0)
    }

    @Test fun lunar_new_year_edges_count_the_age_in_the_calendar() {
        // Late in the Chinese year (month 12) or in Hebrew Tevet, the day moves between December and January.
        assertTrue(checkAges(AltCalendar.CHINESE, AltDay(4627, 12, 2), 40) > 0)
        assertTrue(checkAges(AltCalendar.HEBREW, AltDay(5751, 4, 10), 40) > 0)
        // Near New Year itself (1 Tishri, 1 of month 1) nothing goes wrong either way.
        checkAges(AltCalendar.HEBREW, AltDay(5751, 1, 1), 40)
        checkAges(AltCalendar.CHINESE, AltDay(4627, 1, 1), 40)
    }

    @Test fun reminders_see_the_moved_date() {
        val e = AltCalendars.effective(EventDate(1990, 1, 27), AltCalendar.CHINESE, d("2025-06-01"), IcuCalendars)!!
        assertEquals(EventDate(1990, 2, 17), e)
        assertEquals(36, e.turning(d("2025-06-01")))
    }
}
