package app.parley.common.history

import app.parley.common.PhoneIdentity
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names Parley writes into Android's call log, and the ones it takes off (OG-1, docs/audit/SECURITY_PRESSURE_7_0.md). */
class CallLogNamesTest {
    /** Runs [block], which never really suspends here (core:common has no coroutine library). */
    private fun now(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { r -> failure = r.exceptionOrNull() })
        failure?.let { throw it }
    }

    @Test fun a_name_goes_back_only_for_a_number_a_visible_contact_owns() = now {
        val visible = setOf("+447700900001")
        val names = CallLogNames { it in visible }
        assertEquals("Bob", names.keep("+447700900001", "Bob"))
        // An archived or private person's number: the call goes back without the name.
        assertNull(names.keep("+447700900002", "Ana (archived)"))
        assertNull(names.keep("+447700900001", ""))
        assertNull(names.keep(null, "Withheld"))
    }

    @Test fun a_lookup_that_fails_keeps_the_name_out_and_each_number_is_looked_up_once() = now {
        var lookups = 0
        val names = CallLogNames { n -> lookups++; if (n.endsWith("9")) error("provider unavailable") else true }
        assertNull(names.keep("+447700900009", "Ana"))
        repeat(5) { assertEquals("Bob", names.keep("+447700900001", "Bob")) }
        assertEquals(2, lookups)
    }

    @Test fun only_named_calls_with_hidden_numbers_are_cleared_whatever_their_format() {
        val rows = listOf(
            Triple(1L, "07700 900002", true), // national format of the archived number
            Triple(2L, "+44 7700 900002", false), // already nameless
            Triple(3L, "+447700900001", true), // someone else
            Triple(4L, null, true),
            Triple(5L, "+447700900003", true), // a private contact's
        )
        assertEquals(listOf(1L, 5L), CallLogNames.rowsToClear(rows, listOf("+447700900002", "07700900003"), "GB"))
        assertTrue(CallLogNames.rowsToClear(rows, emptyList(), "GB").isEmpty())
    }

    @Test fun private_lines_are_cleared_under_every_key_a_store_may_hold() {
        val keys = PhoneIdentity.lookupKeysIn(listOf("07700 900123", " "), listOf("GB", null, "GB"))
        assertTrue(PhoneIdentity.key("07700 900123", "GB") in keys)
        assertTrue(PhoneIdentity.legacyKey("07700 900123") in keys)
        assertTrue(keys.none { it.isBlank() })
    }
}
