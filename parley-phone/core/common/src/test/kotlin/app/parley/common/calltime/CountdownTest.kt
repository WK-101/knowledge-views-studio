package app.parley.common.calltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownTest {
    private val min = 60_000L
    private val limited = CallTimePlan(limitMs = 10 * min, warnBeforeMs = min)

    @Test fun budget_starts_at_connect_time() {
        // Seen 2 minutes after the call connected: 8 minutes left, not 10.
        val cd = Countdown.start(nowElapsed = 1_000_000, connectedForMs = 2 * min, plan = limited)
        assertEquals(8 * min, cd.remainingMs(1_000_000))
    }

    @Test fun warns_then_ends() {
        var cd = Countdown.start(0, 0, limited)
        assertTrue(cd.step(8 * min).second.isEmpty())
        val (afterWarn, events) = cd.step(9 * min)
        assertEquals(listOf(CountdownEvent.WARN), events)
        cd = afterWarn
        assertTrue(cd.step(9 * min + 30_000).second.isEmpty()) // warned once only
        assertEquals(listOf(CountdownEvent.END), cd.step(10 * min).second)
    }

    @Test fun extension_moves_end_and_warns_again() {
        var cd = Countdown.start(0, 0, limited).step(9 * min).first
        cd = cd.extend(5 * min)
        assertEquals(15 * min, cd.endAt)
        assertTrue(cd.step(10 * min).second.isEmpty())
        assertEquals(listOf(CountdownEvent.WARN), cd.step(14 * min).second)
    }

    @Test fun dont_end_and_end_in_one_minute() {
        val cd = Countdown.start(0, 0, limited).keepGoing()
        assertNull(cd.endAt)
        assertTrue(cd.step(60 * min).second.isEmpty())
        // "End in 1 min" works on a call without any limit.
        val free = Countdown.start(0, 0, CallTimePlan()).endIn(now = 3 * min, ms = min)
        assertEquals(4 * min, free.endAt)
        assertEquals(listOf(CountdownEvent.END), free.step(4 * min).second)
    }

    @Test fun supervised_calls_cannot_be_extended() {
        val cd = Countdown.start(0, 0, limited.copy(canExtend = false))
        assertEquals(cd, cd.extend(5 * min))
        assertEquals(cd, cd.keepGoing())
        assertEquals(5 * min, cd.endIn(4 * min, min).endAt) // shortening is always allowed
    }

    @Test fun overdue_limit_still_gets_grace() {
        // The process started 20 minutes into a call limited to 10.
        val cd = Countdown.start(nowElapsed = 50 * min, connectedForMs = 20 * min, plan = limited)
        assertEquals(50 * min + Countdown.LATE_GRACE_MS, cd.endAt)
        assertEquals(listOf(CountdownEvent.WARN), cd.step(50 * min).second)
    }

    @Test fun reminders_never_burst_after_sleep() {
        val cd = Countdown.start(0, 0, CallTimePlan(reminderEveryMs = 5 * min))
        assertEquals(5 * min, cd.nextDueAt())
        // The phone slept through three reminders: only one plays.
        val (next, events) = cd.step(16 * min)
        assertEquals(listOf(CountdownEvent.REMINDER), events)
        assertEquals(20 * min, next.nextDueAt())
        // A call first seen late doesn't replay past reminders.
        assertTrue(Countdown.start(12 * min, 12 * min, CallTimePlan(reminderEveryMs = 5 * min)).step(12 * min).second.isEmpty())
    }

    @Test fun quota_reached_only_warns() {
        val cd = Countdown.start(0, 0, CallTimePlan(quotaLeftMs = 3 * min))
        val (next, events) = cd.step(3 * min)
        assertEquals(listOf(CountdownEvent.QUOTA_USED), events)
        assertFalse(next.hasEnd)
        assertTrue(next.step(10 * min).second.isEmpty())
    }

    @Test fun limit_ends_only_its_own_call_with_ringing_and_held_calls() {
        val book = CallTimeBook()
        book.track("limited", Countdown.start(0, 0, limited))
        // A held call and a waiting call have no countdown of their own, or a longer one.
        book.track("held", Countdown.start(0, 0, CallTimePlan(reminderEveryMs = 30 * min)))
        val actions = book.step(10 * min)
        assertEquals(listOf("limited" to CountdownEvent.END), actions.filter { it.second == CountdownEvent.END })
        assertTrue(actions.none { it.first == "ringing" })
        assertNull(book["limited"])
        assertTrue(book["held"] != null)
        assertTrue(book.step(11 * min).none { it.second == CountdownEvent.END })
    }

    @Test fun chronometer_countdown_does_not_repost_every_second() {
        var cd = Countdown.start(0, 0, limited)
        val connectWall = 1_700_000_000_000L
        val signatures = (0 until 600).map { s ->
            val now = s * 1000L
            CallChronometer.display(connectWall, cd, now, connectWall + now).signature
        }.toSet()
        assertEquals(1, signatures.size)
        val first = CallChronometer.display(connectWall, cd, 0, connectWall)
        assertTrue(first.countDown)
        assertEquals(connectWall + 10 * min, first.whenMillis)
        cd = cd.extend(2 * min)
        val after = CallChronometer.display(connectWall, cd, 1000, connectWall + 1000)
        assertTrue(after.signature != first.signature)
        assertEquals(connectWall + 12 * min, after.whenMillis)
        // Without an end the notification counts up from the connect time.
        val up = CallChronometer.display(connectWall, null, 5000, connectWall + 5000)
        assertFalse(up.countDown)
        assertEquals(connectWall, up.whenMillis)
    }

    @Test fun wake_lock_is_bounded() {
        assertEquals(11 * min, Countdown.wakeLockTimeoutMs(0, 10 * min))
        assertEquals(60_000L, Countdown.wakeLockTimeoutMs(10 * min, 5 * min))
        assertEquals(4 * 60 * min, Countdown.wakeLockTimeoutMs(0, 24 * 60 * min))
    }
}
