package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RescuePlanTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: ZoneId = paris) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    @Test fun now_and_in_minutes_count_from_the_tap() {
        val now = at(2026, 10, 4, 14, 2, 30)
        assertEquals(now, RescuePlan.fireAt(RescueWhen.NOW, now))
        assertEquals(now + 60_000, RescuePlan.fireAt(RescueWhen.IN_1, now))
        assertEquals(now + 5 * 60_000, RescuePlan.fireAt(RescueWhen.IN_5, now))
        assertEquals(now + 15 * 60_000, RescuePlan.fireAt(RescueWhen.IN_15, now))
    }

    @Test fun at_a_time_is_later_today_else_tomorrow() {
        val now = at(2026, 10, 4, 14, 2, 30)
        assertEquals(at(2026, 10, 4, 18, 0), RescuePlan.fireAt(RescueWhen.AT_TIME, now, 18 * 60, paris))
        // Already past today: tomorrow.
        assertEquals(at(2026, 10, 5, 9, 15), RescuePlan.fireAt(RescueWhen.AT_TIME, now, 9 * 60 + 15, paris))
        // The current minute has begun: tomorrow, never "a moment ago".
        assertEquals(at(2026, 10, 5, 14, 2), RescuePlan.fireAt(RescueWhen.AT_TIME, now, 14 * 60 + 2, paris))
        // A minute ahead: today.
        assertEquals(at(2026, 10, 4, 14, 3), RescuePlan.fireAt(RescueWhen.AT_TIME, now, 14 * 60 + 3, paris))
    }

    @Test fun at_a_time_keeps_the_wall_clock_across_a_clock_change() {
        // The clocks go back on 25 October 2026 in Paris: 08:00 the next day is still 08:00 on the clock.
        val now = at(2026, 10, 24, 22, 0)
        assertEquals(at(2026, 10, 25, 8, 0), RescuePlan.fireAt(RescueWhen.AT_TIME, now, 8 * 60, paris))
        assertEquals(10 * 3_600_000L + 3_600_000L, at(2026, 10, 25, 8, 0) - now)
        // 02:30 doesn't exist on 29 March 2026 (clocks go forward): it moves forward rather than vanishing.
        val spring = at(2026, 3, 28, 23, 0)
        assertTrue(RescuePlan.fireAt(RescueWhen.AT_TIME, spring, 2 * 60 + 30, paris) > spring)
    }

    @Test fun out_of_range_minutes_are_clamped() {
        val now = at(2026, 10, 4, 12, 0)
        assertEquals(at(2026, 10, 4, 23, 59), RescuePlan.nextTime(99_999, now, paris))
        assertEquals(at(2026, 10, 5, 0, 0), RescuePlan.nextTime(-5, now, paris))
    }

    @Test fun an_alarm_rings_only_the_call_still_waiting_and_only_once() {
        val r = RescueRequest("a", "Mum", null, 1_000_000)
        assertEquals(RescuePlan.Due.RING, RescuePlan.onAlarm(r, "a", 1_000_000))
        assertEquals(RescuePlan.Due.RING, RescuePlan.onAlarm(r, "a", 1_000_000 + 4 * 60_000))
        // Cancelled (nothing waiting) or replaced by another call: the old alarm rings nothing.
        assertEquals(RescuePlan.Due.GONE, RescuePlan.onAlarm(null, "a", 1_000_000))
        assertEquals(RescuePlan.Due.GONE, RescuePlan.onAlarm(r, "b", 1_000_000))
        // Far too late (the phone was off): the moment has passed.
        assertEquals(RescuePlan.Due.STALE, RescuePlan.onAlarm(r, "a", 1_000_000 + RescuePlan.STALE_MS + 1))
        // Early by more than a moment: it waits on.
        assertEquals(RescuePlan.Due.EARLY, RescuePlan.onAlarm(r, "a", 1_000_000 - 120_000))
        assertEquals(RescuePlan.Due.RING, RescuePlan.onAlarm(r, "a", 1_000_000 - 10_000))
    }

    @Test fun only_short_waits_keep_the_phone_awake() {
        assertFalse("now needs no wait", RescuePlan.keepsAwake(0))
        assertTrue(RescuePlan.keepsAwake(60_000))
        assertTrue(RescuePlan.keepsAwake(15 * 60_000))
        assertFalse("a time hours away relies on the inexact alarm", RescuePlan.keepsAwake(3 * 3_600_000))
        assertFalse(RescuePlan.keepsAwake(-1))
    }

    @Test fun a_real_call_always_wins() {
        assertTrue(RescuePlan.mayRing(realCallsLive = 0, systemInCall = false))
        assertFalse("Parley has a call", RescuePlan.mayRing(realCallsLive = 1, systemInCall = false))
        assertFalse("another phone app has a call", RescuePlan.mayRing(realCallsLive = 0, systemInCall = true))
    }

    @Test fun the_shown_name_is_trimmed_and_short() {
        assertEquals("Mum", RescuePlan.shownName("  Mum ", "Jane Doe"))
        assertEquals("Jane Doe", RescuePlan.shownName("   ", "Jane Doe"))
        assertNull(RescuePlan.shownName("", null))
        assertEquals(RescuePlan.MAX_NAME, RescuePlan.shownName("x".repeat(100), null)!!.length)
    }
}
