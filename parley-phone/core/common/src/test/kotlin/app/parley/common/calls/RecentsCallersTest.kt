package app.parley.common.calls

import app.parley.common.CallType
import app.parley.common.calls.RecentsCallers.Who
import app.parley.common.testing.testCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsCallersTest {
    private fun call(n: String, type: CallType, date: Long, hidden: Boolean = false, id: Long = date) =
        testCall(id, n, null, type, date, 0, presentationHidden = hidden)

    @Test fun unknown_and_contacts_split_every_call() {
        assertTrue(RecentsCallers.matches(Who.CONTACTS, isContact = true, hidden = false))
        assertFalse(RecentsCallers.matches(Who.CONTACTS, isContact = false, hidden = false))
        assertTrue(RecentsCallers.matches(Who.UNKNOWN, isContact = false, hidden = false))
        // A hidden number is unknown, whoever it may be.
        assertTrue(RecentsCallers.matches(Who.UNKNOWN, isContact = false, hidden = true))
        assertFalse(RecentsCallers.matches(Who.CONTACTS, isContact = true, hidden = true))
        for (c in listOf(true, false)) for (h in listOf(true, false)) {
            assertTrue(RecentsCallers.matches(Who.UNKNOWN, c, h) != RecentsCallers.matches(Who.CONTACTS, c, h))
        }
    }

    @Test fun unknown_callers_today_count_numbers_once_and_hidden_calls_each() {
        val today = 1_000_000L
        val contacts = setOf("+1555")
        val calls = listOf(
            call("+1999", CallType.MISSED, today + 10),
            call("+1999", CallType.INCOMING, today + 20),
            call("+1888", CallType.BLOCKED, today + 30),
            call("", CallType.MISSED, today + 40, hidden = true),
            call("", CallType.MISSED, today + 50, hidden = true),
            // A contact, an outgoing call and yesterday's call don't count.
            call("+1555", CallType.MISSED, today + 60),
            call("+1777", CallType.OUTGOING, today + 70),
            call("+1666", CallType.MISSED, today - 1),
        )
        val n = RecentsCallers.unknownCallersSince(calls, today, { it }) { it.number in contacts }
        assertEquals(4, n)
        assertEquals(0, RecentsCallers.unknownCallersSince(emptyList(), today, { it }) { false })
    }

    @Test fun the_last_chip_comes_back_unless_it_is_a_passing_look() {
        val transient = setOf("BLOCKED", "VOICEMAIL")
        assertEquals("UNKNOWN", RecentsCallers.restored(true, "UNKNOWN", "ALL", transient))
        assertEquals("ALL", RecentsCallers.restored(false, "UNKNOWN", "ALL", transient))
        assertEquals("ALL", RecentsCallers.restored(true, "BLOCKED", "ALL", transient))
        assertEquals("ALL", RecentsCallers.restored(true, null, "ALL", transient))
    }
}
