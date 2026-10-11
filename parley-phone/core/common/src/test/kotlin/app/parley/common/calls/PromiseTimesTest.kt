package app.parley.common.calls

import app.parley.common.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromiseTimesTest {
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val karachi: ZoneId = ZoneId.of("Asia/Karachi")

    private fun t(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    // ---- the choices

    @Test fun times_are_on_the_quarter_hour_and_end_of_a_situation_comes_first() {
        val now = t(london, 2026, 9, 29, 14, 7)
        val options = PromiseTimes.options(now, london, null, freeAt = t(london, 2026, 9, 29, 15, 20))
        assertEquals(
            listOf(
                PromiseOption(PromiseChoice.IN_AN_HOUR, t(london, 2026, 9, 29, 15, 15)),
                PromiseOption(PromiseChoice.WHEN_FREE, t(london, 2026, 9, 29, 15, 30)),
                PromiseOption(PromiseChoice.THIS_EVENING, t(london, 2026, 9, 29, 18)),
                PromiseOption(PromiseChoice.TOMORROW_MORNING, t(london, 2026, 9, 30, 9)),
            ),
            options,
        )
    }

    @Test fun a_situation_ending_too_soon_or_too_late_is_not_offered() {
        val now = t(london, 2026, 9, 29, 14)
        assertTrue(PromiseTimes.options(now, london, null, now + 5 * 60_000L).none { it.choice == PromiseChoice.WHEN_FREE })
        assertTrue(PromiseTimes.options(now, london, null, now + 13 * ToCall.HOUR_MS).none { it.choice == PromiseChoice.WHEN_FREE })
    }

    @Test fun their_evening_is_offered_when_their_clock_differs() {
        // 10:00 in London is 14:00 in Karachi: their early evening is 18:00 there, 14:00 here.
        val now = t(london, 2026, 9, 29, 10)
        val theirs = PromiseTimes.options(now, london, karachi, null).single { it.choice == PromiseChoice.THEIR_EVENING }
        assertEquals(t(karachi, 2026, 9, 29, 18), theirs.at)
        assertTrue(PromiseTimes.options(now, london, london, null).none { it.choice == PromiseChoice.THEIR_EVENING })
        assertEquals(karachi, PromiseTimes.saidIn(karachi, london, now))
        assertNull(PromiseTimes.saidIn(london, london, now))
    }

    @Test fun at_most_four_choices_and_never_two_for_one_moment() {
        val now = t(london, 2026, 9, 29, 10)
        val options = PromiseTimes.options(now, london, karachi, freeAt = t(london, 2026, 9, 29, 18))
        assertTrue(options.size <= 4)
        assertEquals(options.size, options.map { it.at }.toSet().size)
    }

    @Test fun days_ahead_are_counted_on_the_clock_it_is_said_in() {
        // 18:00 in London is 22:00 in Karachi; 22:00 in London is 02:00 tomorrow there.
        val now = t(london, 2026, 9, 29, 18)
        val at = t(london, 2026, 9, 29, 22)
        assertEquals(0L, PromiseTimes.daysAhead(at, now, london))
        assertEquals(1L, PromiseTimes.daysAhead(at, now, karachi))
    }

    @Test fun free_at_is_the_end_chosen_or_the_end_of_the_scheduled_window() {
        val now = t(london, 2026, 9, 29, 23)
        assertEquals(now + 3_600_000L, PromiseTimes.freeAt(now + 3_600_000L, scheduled = false, schedule = null, now = now, zone = london))
        // Night 22:00–07:00, on by its schedule: free at 07:00 tomorrow.
        val night = Schedule(startMinute = 22 * 60, endMinute = 7 * 60)
        assertEquals(t(london, 2026, 9, 30, 7), PromiseTimes.freeAt(null, scheduled = true, schedule = night, now = now, zone = london))
        // On by hand with no end, or all day: no end to promise.
        assertNull(PromiseTimes.freeAt(null, scheduled = false, schedule = night, now = now, zone = london))
        assertNull(PromiseTimes.freeAt(null, scheduled = true, schedule = Schedule(), now = now, zone = london))
    }

    // ---- kept on the To call item

    @Test fun a_promise_rides_on_the_to_call_item_until_the_call_is_made() {
        val now = t(london, 2026, 9, 29, 14)
        val at = t(london, 2026, 9, 29, 15, 30)
        var s = ToCall.remind(ToCallState(), "k", "+447700900123", at, now, promised = PromiseTimes.record(at, now, null))
        assertEquals(at, ToCall.promisedTo(s, "k")?.at)
        assertEquals(at, ToCall.entries(s, emptyList(), now).single().promisedAt)
        // Moving the reminder keeps what you told them.
        s = ToCall.snooze(s, ToCall.entries(s, emptyList(), now).single(), at + ToCall.HOUR_MS, now)
        assertEquals(at, ToCall.promisedTo(s, "k")?.at)
        // A call with them settles it, and the promise goes with the item.
        s = ToCall.settle(s, listOf(SettlingCall("k", now + 60_000)), now + 120_000)
        assertNull(ToCall.promisedTo(s, "k"))
    }

    @Test fun a_time_kept_only_for_yourself_is_never_said_back() {
        val now = t(london, 2026, 9, 29, 14)
        val s = ToCall.remind(ToCallState(), "k", "+447700900123", now + 1, now, promised = PromisedTime(now + 1, now, told = false))
        assertNull(ToCall.promisedTo(s, "k"))
        assertNull(ToCall.entries(s, emptyList(), now).single().promisedAt)
    }

    @Test fun the_record_survives_a_round_trip_and_older_lists_read_without_it() {
        val now = t(london, 2026, 9, 29, 14)
        val s = ToCall.remind(ToCallState(), "k", "+92300", now, now, promised = PromiseTimes.record(now, now, karachi))
        assertEquals(s, ToCall.decode(ToCall.encode(s)))
        assertEquals(karachi.id, ToCall.decode(ToCall.encode(s)).items.single().promised?.zone)
        assertNull(ToCall.decode("""{"items":[{"key":"k","number":"1","since":1,"at":2}]}""").items.single().promised)
    }
}
