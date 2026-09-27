package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallBookTest {
    /** The in-call service's states, as the service maps Telecom's. */
    private enum class S { NEW, RINGING, DIALING, CONNECTING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED, SELECT_ACCOUNT }

    private val front = setOf(S.NEW, S.DIALING, S.CONNECTING, S.ACTIVE)
    private val book = CallBook(S.HOLDING, front, front + setOf(S.RINGING, S.SELECT_ACCOUNT), setOf(S.DISCONNECTING, S.DISCONNECTED))

    @Test fun the_hold_timer_starts_once_and_stops_when_the_call_is_resumed() {
        book.update("a", S.ACTIVE, 100)
        assertEquals(0L, book.heldSince("a"))
        book.update("a", S.HOLDING, 200)
        book.update("a", S.HOLDING, 900)
        assertEquals(200L, book.heldSince("a"))
        book.update("a", S.ACTIVE, 1000)
        assertEquals(0L, book.heldSince("a"))
        book.update("a", S.HOLDING, 1100)
        assertEquals(1100L, book.heldSince("a"))
    }

    @Test fun an_ending_call_keeps_its_last_live_state() {
        book.update("a", S.SELECT_ACCOUNT, 0)
        book.update("a", S.DISCONNECTING, 1)
        book.update("a", S.DISCONNECTED, 2)
        assertEquals(S.SELECT_ACCOUNT, book.lastLiveState("a"))
    }

    @Test fun removing_the_call_in_front_asks_for_a_resume_check() {
        book.update("a", S.ACTIVE, 0)
        book.update("a", S.DISCONNECTED, 1)
        book.update("b", S.HOLDING, 0)
        book.update("c", S.RINGING, 0)
        assertTrue(book.remove("a"))
        assertFalse("a held call wasn't in front", book.remove("b"))
        assertFalse("a ringing call wasn't in front", book.remove("c"))
        assertFalse("unknown calls are ignored", book.remove("zzz"))
        assertNull(book.lastLiveState("a"))
        assertEquals(0L, book.heldSince("b"))
    }

    @Test fun a_dialling_call_counts_as_in_front() {
        for (s in listOf(S.NEW, S.DIALING, S.CONNECTING)) {
            book.update("x", s, 0)
            assertTrue("$s", book.remove("x"))
        }
    }

    @Test fun exactly_one_held_call_is_resumed_when_nothing_else_needs_the_line() {
        assertEquals("b", book.toResume(listOf("b" to S.HOLDING)))
        assertEquals("b", book.toResume(listOf("b" to S.HOLDING, "gone" to S.DISCONNECTED)))
        assertNull("two held calls: the user picks", book.toResume(listOf("b" to S.HOLDING, "c" to S.HOLDING)))
        for (busy in listOf(S.RINGING, S.DIALING, S.CONNECTING, S.ACTIVE, S.SELECT_ACCOUNT, S.NEW)) {
            assertNull("$busy keeps the held call waiting", book.toResume(listOf("b" to S.HOLDING, "c" to busy)))
        }
        assertNull(book.toResume(emptyList<Pair<String, S>>()))
    }

    @Test fun clear_forgets_everything() {
        book.update("a", S.HOLDING, 5)
        book.update("b", S.ACTIVE, 5)
        book.clear()
        assertEquals(0L, book.heldSince("a"))
        assertNull(book.lastLiveState("b"))
        assertFalse(book.remove("b"))
    }
}
