package app.parley.data.people

import app.parley.common.AltCalendar
import app.parley.common.AltCalendars
import app.parley.common.AltDay
import app.parley.common.EventDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
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

    @Test fun reminders_see_the_moved_date() {
        val e = AltCalendars.effective(EventDate(1990, 1, 27), AltCalendar.CHINESE, d("2025-06-01"), IcuCalendars)!!
        assertEquals(EventDate(1990, 2, 17), e)
        assertEquals(36, e.turning(d("2025-06-01")))
    }
}
