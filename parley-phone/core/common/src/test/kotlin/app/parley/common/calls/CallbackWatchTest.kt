package app.parley.common.calls

import app.parley.common.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CallbackWatchTest {
    private val london: ZoneId = ZoneId.of("Europe/London")

    private fun t(d: Int, h: Int, mi: Int = 0, month: Int = 9): Long = ZonedDateTime.of(2026, month, d, h, mi, 0, 0, london).toInstant().toEpochMilli()

    // 28 September 2026 is a Monday.

    @Test fun by_days_follow_the_week() {
        assertEquals(
            listOf(
                CallbackBy.TODAY to t(28, 20), CallbackBy.TOMORROW to t(29, 20),
                CallbackBy.FRIDAY to t(2, 20, month = 10), CallbackBy.IN_A_WEEK to t(5, 20, month = 10),
            ),
            CallbackWatch.choices(t(28, 10), london),
        )
        // Thursday evening: no "today" left, and Friday is tomorrow.
        assertEquals(listOf(CallbackBy.TOMORROW, CallbackBy.IN_A_WEEK), CallbackWatch.choices(t(24, 19), london).map { it.first })
    }

    private val hospital = "+441632960001"

    @Test fun the_window_lets_the_line_ring_and_any_caller_when_chosen() {
        val now = t(28, 10)
        val only = CallbackWatch.window(hospital, "k", now, t(29, 20), anyCaller = false, label = "St Mary's", privateName = false)
        assertEquals(only, ExpectedCalls.covering(listOf(only), hospital, "GB", now + 1))
        assertNull(ExpectedCalls.covering(listOf(only), "+447700900999", "GB", now + 1))
        val any = only.copy(anyCaller = true)
        assertEquals(any, ExpectedCalls.covering(listOf(any), "+447700900999", "GB", now + 1))
        // Not after the date.
        assertNull(ExpectedCalls.covering(listOf(any), hospital, "GB", t(29, 21)))
    }

    @Test fun a_callback_needs_no_yes_and_stays_when_the_hint_is_turned_off() {
        val now = t(28, 10)
        val callback = CallbackWatch.window(hospital, "k", now, t(29, 20), anyCaller = false, label = null, privateName = true)
        assertFalse("no name to hide", callback.privateName)
        val hint = ExpectedWindow(now, t(29, 20), ExpectedSource.TO_CALL, "other", number = "+447700900123")
        assertEquals(listOf(callback), ExpectedCalls.inForce(listOf(callback, hint), emptyMap(), now))
        assertEquals(listOf(callback, hint), ExpectedCalls.inForce(listOf(callback, hint), mapOf(ExpectedSource.TO_CALL to true), now))
        assertEquals(listOf(callback), ExpectedCalls.without(listOf(callback, hint), ExpectedSource.TO_CALL))
        assertEquals(listOf(callback), ExpectedCalls.callbacksAt(listOf(callback, hint), now + 1))
    }

    @Test fun an_older_version_reads_a_callback_as_a_plain_to_call_window() {
        val now = t(28, 10)
        val w = CallbackWatch.window(hospital, "k", now, t(29, 20), anyCaller = true, label = "St Mary's", privateName = false)
        val doc = FamilySafetyState.encode(FamilySafetyState(windows = listOf(w)))
        assertTrue(doc.contains("\"callback\":true"))
        assertEquals(w, FamilySafetyState.decode(doc).windows.single())
        // A window written before callbacks existed reads with both off.
        val old = FamilySafetyState.decode(doc.replace(",\"callback\":true", "").replace(",\"anyCaller\":true", "")).windows.single()
        assertFalse(old.callback || old.anyCaller)
    }

    @Test fun a_callback_item_is_due_by_the_date_and_settled_by_their_call() {
        val now = t(28, 10)
        var s = ToCall.remind(ToCallState(), "k", hospital, t(29, 20), now, ToCallSource.CALLBACK)
        val e = ToCall.entries(s, emptyList(), now).single()
        assertEquals(ToCallKind.CALLBACK, e.kind)
        assertFalse(e.isDue(now))
        assertTrue(e.isDue(t(29, 20)))
        s = ToCall.settle(s, listOf(SettlingCall("k", t(29, 11))), t(29, 12))
        assertTrue(s.items.isEmpty())
        // An older version reads the source as a plain reminder.
        val older = ToCall.decode(ToCall.encode(ToCall.remind(ToCallState(), "k", hospital, 1, 0, ToCallSource.CALLBACK)).replace("CALLBACK", "FUTURE"))
        assertEquals(ToCallSource.REMINDER, older.items.single().source)
    }

    @Test fun a_follow_up_still_never_turns_a_reminder_into_one() {
        var s = ToCall.remind(ToCallState(), "k", hospital, 5, 0)
        s = ToCall.remind(s, "k", hospital, 9, 1, ToCallSource.FOLLOW_UP)
        assertEquals(ToCallSource.REMINDER, s.items.single().source)
    }

    @Test fun calls_in_a_callback_window_do_not_disarm_never_calls_you() {
        val out = NeverCallsYou.PastCall(CallType.OUTGOING, 100)
        val back = NeverCallsYou.PastCall(CallType.INCOMING, 500)
        val later = NeverCallsYou.PastCall(CallType.INCOMING, 2_000)
        val kept = NeverCallsYou.withoutExpected(listOf(out, back, later), listOf(400L..1_000L))
        assertEquals(listOf(out, later), kept)
        // Only their callback inside the window is left out: so the line stays armed for calls outside it.
        assertTrue(NeverCallsYou.onlyYouCalled(NeverCallsYou.withoutExpected(listOf(out, back), listOf(400L..1_000L)), keptSince = 0))
        assertEquals(listOf(out, back), NeverCallsYou.withoutExpected(listOf(out, back), emptyList()))
    }
}
