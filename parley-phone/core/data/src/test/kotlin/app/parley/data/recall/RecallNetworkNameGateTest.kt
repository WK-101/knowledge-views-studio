package app.parley.data.recall

import app.parley.common.PhoneIdentity
import app.parley.common.calls.NetworkNameSeen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Recall asks the network's names only while "Remember names from the network" is on, and never for a private number. */
class RecallNetworkNameGateTest {
    private val privateLines = PhoneIdentity.LineSet(listOf("+44 20 7946 0001"), "GB")
    private val isPrivate: (String) -> Boolean = { it in privateLines }

    /** A store reader that would name every number, private ones included (the gate must still keep those out). */
    private val read: (String) -> List<NetworkNameSeen> = { n -> listOf(NetworkNameSeen("Name of $n", 1, 2)) }

    @Test fun off_or_unknown_names_nothing() {
        listOf(false, null).forEach { on ->
            val lookup = RecallSources.networkNameLookup(on, isPrivate, read) { null }
            assertNull(lookup("07700 900123", null))
        }
    }

    @Test fun on_names_saved_and_unsaved_numbers_alike() {
        val lookup = RecallSources.networkNameLookup(true, isPrivate, read) { null }
        assertEquals("Name of 07700 900123", lookup("07700 900123", null))
        assertEquals("Name of 07700 900124", lookup("07700 900124", "sim-2"))
    }

    @Test fun never_a_private_number_and_nothing_when_they_could_not_be_read() {
        assertNull(RecallSources.networkNameLookup(true, isPrivate, read) { null }("+442079460001", null))
        assertNull(RecallSources.networkNameLookup(true, null, read) { null }("07700 900123", null))
        assertNull(RecallSources.networkNameLookup(true, isPrivate, null) { null }("07700 900123", null))
    }
}
