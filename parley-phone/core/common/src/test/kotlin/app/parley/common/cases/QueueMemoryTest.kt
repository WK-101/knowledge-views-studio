package app.parley.common.cases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class QueueMemoryTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-10-05 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun call(at: Long, holdMin: Long, incoming: Boolean = false) = CaseEntry.Call(at, null, incoming, 600, holdSec = holdMin * 60)

    @Test fun says_nothing_under_three_timed_calls_of_yours() {
        val two = listOf(call(at(5, 9), 6), call(at(6, 9), 8), call(at(7, 9), 30, incoming = true), call(at(8, 9), 0))
        assertNull(QueueMemories.of(two, zone))
    }

    @Test fun the_range_and_a_clean_morning_split() {
        val calls = listOf(call(at(5, 8, 40), 6), call(at(6, 9, 10), 7), call(at(7, 11), 20), call(at(8, 15), 25))
        val q = requireNotNull(QueueMemories.of(calls, zone))
        assertEquals(4, q.calls)
        assertEquals(6 * 60L, q.shortestSec)
        assertEquals(25 * 60L, q.longestSec)
        assertEquals(QueueTime(9 * 60 + 30, before = true, weekdays = true), q.best)
    }

    @Test fun an_afternoon_split_and_no_split_when_the_waits_are_mixed() {
        val later = listOf(call(at(5, 9), 30), call(at(6, 10), 25), call(at(7, 14, 20), 5), call(at(8, 16), 4))
        assertEquals(QueueTime(14 * 60, before = false, weekdays = true), QueueMemories.of(later, zone)!!.best)
        val mixed = listOf(call(at(5, 9), 5), call(at(6, 10), 25), call(at(7, 14), 6), call(at(8, 16), 24))
        assertNull(QueueMemories.of(mixed, zone)!!.best)
        // A minute's difference isn't a better time.
        val close = listOf(call(at(5, 9), 10), call(at(6, 9, 5), 10), call(at(7, 14), 11))
        assertNull(QueueMemories.of(close, zone)!!.best)
    }

    @Test fun a_weekend_call_on_the_better_side_drops_on_weekdays() {
        val calls = listOf(call(at(4, 8), 3), call(at(5, 9), 4), call(at(6, 12), 20))
        val best = QueueMemories.of(calls, zone)!!.best!!
        assertFalse(best.weekdays)
        assertTrue(best.before)
    }

    @Test fun remind_me_lands_on_the_next_weekday_half_an_hour_before_the_cut() {
        val time = QueueTime(9 * 60 + 30, before = true, weekdays = true)
        // Friday 10:00: Monday 9:00.
        assertEquals(at(12, 9), QueueMemories.remindAt(time, at(9, 10), zone))
        // Tuesday 8:00: the same day.
        assertEquals(at(6, 9), QueueMemories.remindAt(time, at(6, 8), zone))
        assertEquals(at(10, 14), QueueMemories.remindAt(QueueTime(14 * 60, before = false, weekdays = false), at(9, 15), zone))
    }
}
