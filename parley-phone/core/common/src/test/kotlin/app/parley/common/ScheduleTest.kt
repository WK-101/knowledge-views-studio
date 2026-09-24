package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ScheduleTest {
    @Test fun across_midnight_belongs_to_the_starting_day() {
        val friNight = Schedule(1 shl 4, 22 * 60, 6 * 60)
        assertTrue(friNight.isActive(DayOfWeek.FRIDAY, 23 * 60))
        assertTrue(friNight.isActive(DayOfWeek.SATURDAY, 5 * 60 + 59))
        assertFalse(friNight.isActive(DayOfWeek.SATURDAY, 6 * 60))
        assertFalse(friNight.isActive(DayOfWeek.FRIDAY, 5 * 60))
        assertFalse(friNight.isActive(DayOfWeek.SATURDAY, 23 * 60))
    }

    @Test fun same_day_and_all_day() {
        val office = Schedule(Schedule.WEEKDAYS, 9 * 60, 17 * 60)
        assertTrue(office.isActive(DayOfWeek.MONDAY, 9 * 60))
        assertFalse(office.isActive(DayOfWeek.MONDAY, 17 * 60))
        assertFalse(office.isActive(DayOfWeek.SUNDAY, 10 * 60))
        val weekend = Schedule(Schedule.WEEKEND, 0, 0)
        assertTrue(weekend.isActive(DayOfWeek.SUNDAY, 0))
        assertTrue(weekend.isActive(DayOfWeek.SATURDAY, 1439))
        assertFalse(weekend.isActive(DayOfWeek.MONDAY, 0))
        assertEquals("Weekends, all day", weekend.describe())
        assertEquals("Mon–Fri 09:00–17:00", office.describe())
        assertFalse(Schedule(0, 0, 0).isActive(DayOfWeek.MONDAY, 0))
    }

    @Test fun encode_decode() {
        val s = Schedule(Schedule.WEEKDAYS, 22 * 60, 7 * 60)
        assertEquals(s, Schedule.decode(s.encode()))
        assertEquals(null, Schedule.decode("garbage"))
    }

    @Test fun screening_settings_codec_keeps_new_fields_and_survives_garbage() {
        val s = ScreeningSettings(
            blockInvalid = true, snoozeUntil = 42, emergencyExtras = listOf("+33123456789"),
            offHours = OffHours(enabled = true, allow = OffHoursAllow.LABEL, labelId = 5, labelTitle = "Family"),
            nonContactsSchedule = Schedule(Schedule.WEEKEND, 0, 0),
        )
        assertEquals(s, ScreeningSettings.decode(s.encode()))
        assertEquals(ScreeningSettings(), ScreeningSettings.decode("{not json"))
        assertEquals(ScreeningSettings(), ScreeningSettings.decode(null))
        // Unknown keys from a newer version are ignored.
        assertEquals(ScreeningSettings(blockInvalid = true), ScreeningSettings.decode("""{"blockInvalid":true,"future":1}"""))
    }
}
