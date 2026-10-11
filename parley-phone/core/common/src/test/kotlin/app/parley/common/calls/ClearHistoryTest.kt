package app.parley.common.calls

import app.parley.common.CallType
import app.parley.common.testing.testCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearHistoryTest {
    private fun call(id: Long, number: String, day: Long, type: CallType = CallType.INCOMING, hidden: Boolean = false) =
        testCall(id, number, null, type, day * 86_400_000L + id, 0, presentationHidden = hidden)

    @Test fun clear_history_scopes() {
        val list = listOf(
            call(1, "111", 1), call(2, "999", 1, CallType.MISSED), call(3, "", 1, hidden = true),
            call(-4, "555", 1), call(5, "111", 1, CallType.REJECTED),
        )
        val known = { n: String -> n == "111" }
        assertEquals(listOf(1L, 2L, 3L, 5L), ClearHistory.select(list, ClearScope.ALL, known).map { it.id })
        // Private numbers count as unknown; private-contact calls (negative ids) are never touched.
        assertEquals(listOf(2L, 3L), ClearHistory.select(list, ClearScope.UNKNOWN_NUMBERS, known).map { it.id })
        assertEquals(listOf(2L, 5L), ClearHistory.select(list, ClearScope.MISSED, known).map { it.id })
        assertEquals(listOf(5L), ClearHistory.select(list, ClearScope.SHOWN, known, setOf(5L, -4L)).map { it.id })
    }

    @Test fun clear_history_never_takes_a_private_contacts_call_not_yet_moved_to_the_vault() {
        val list = listOf(call(1, "111", 1), call(2, "777", 1, CallType.MISSED), call(3, "999", 1), call(4, "", 1, CallType.MISSED, hidden = true))
        val known = { n: String -> n == "111" }
        val private = { n: String -> n == "777" }
        assertEquals(listOf(1L, 3L, 4L), ClearHistory.select(list, ClearScope.ALL, known, isPrivate = private).map { it.id })
        assertEquals(listOf(4L), ClearHistory.select(list, ClearScope.MISSED, known, isPrivate = private).map { it.id })
        assertEquals(listOf(3L, 4L), ClearHistory.select(list, ClearScope.UNKNOWN_NUMBERS, known, isPrivate = private).map { it.id })
        assertEquals(listOf(1L), ClearHistory.select(list, ClearScope.SHOWN, known, setOf(1L, 2L), isPrivate = private).map { it.id })
    }

    @Test fun clear_unknown_numbers_needs_the_contacts() {
        val list = listOf(call(1, "111", 1), call(2, "999", 1))
        // Contacts not loaded or not permitted: every number would look unknown, so nothing is picked.
        assertTrue(ClearHistory.select(list, ClearScope.UNKNOWN_NUMBERS, { false }, contactsReady = false).isEmpty())
        assertFalse(ClearHistory.available(ClearScope.UNKNOWN_NUMBERS, contactsReady = false))
        // The other scopes don't depend on them.
        assertTrue(ClearHistory.available(ClearScope.ALL, contactsReady = false))
        assertEquals(listOf(1L, 2L), ClearHistory.select(list, ClearScope.ALL, { false }, contactsReady = false).map { it.id })
    }
}
