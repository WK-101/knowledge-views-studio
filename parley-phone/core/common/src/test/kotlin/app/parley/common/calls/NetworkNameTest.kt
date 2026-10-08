package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkNameTest {
    private val allowed = NetworkName.PRESENTATION_ALLOWED

    @Test fun keeps_a_real_name_tidied() {
        assertEquals("Ravi Kumar", NetworkName.clean("  Ravi   Kumar ", allowed))
        assertEquals("SHARMA TRADERS", NetworkName.clean("SHARMA TRADERS.", allowed))
        assertEquals("Dr. Anil Rao", NetworkName.clean("Dr. Anil Rao", allowed))
        val long = NetworkName.clean("A".repeat(80), allowed)!!
        assertEquals(NetworkName.MAX_LENGTH, long.length)
    }

    @Test fun only_an_allowed_presentation() {
        // PRESENTATION_RESTRICTED (2), UNKNOWN (3), PAYPHONE (4) and anything else.
        listOf(2, 3, 4, 0, -1).forEach { assertNull(NetworkName.clean("Ravi Kumar", it)) }
    }

    @Test fun placeholders_blanks_and_the_number_itself_are_not_names() {
        listOf(
            null, "", "   ", "Unknown", "UNKNOWN CALLER", "unknown name", "WIRELESS CALLER", "Wireless caller.", "Private", "Private Number",
            "RESTRICTED", "Anonymous", "Unavailable", "Out of area", "No caller ID", "Cell Phone", "Toll free", "Scam Likely", "Spam risk",
            "+91 98123 00002", "9812300002", "98123-00002", "Call 9812300002", "***", "--",
        ).forEach { assertNull("$it", NetworkName.clean(it, allowed)) }
    }

    @Test fun the_same_name_moves_up_and_keeps_its_first_time() {
        var h = NetworkName.record(emptyList(), "Ravi Kumar", at = 100, accountId = "sim1", region = "IN")
        h = NetworkName.record(h, "RAVI KUMAR", at = 300, accountId = null, region = "IN")
        val only = h.single()
        assertEquals(100, only.firstSeen)
        assertEquals(300, only.lastSeen)
        // The SIM of an earlier call stays when a later one didn't say.
        assertEquals("sim1", only.accountId)
        assertEquals("RAVI KUMAR", only.name)
        assertNull(NetworkName.before(h))
    }

    @Test fun a_changed_name_keeps_a_small_history() {
        var h = NetworkName.record(emptyList(), "Ravi Kumar", 100, "sim1", "IN")
        h = NetworkName.record(h, "Kumar Electricals", 200, "sim2", "IN")
        assertEquals("Kumar Electricals", NetworkName.latest(h)!!.name)
        assertEquals("Ravi Kumar", NetworkName.before(h)!!.name)
        h = NetworkName.record(h, "K Electricals", 300, "sim2", "IN")
        h = NetworkName.record(h, "Kumar & Sons", 400, "sim2", "IN")
        assertEquals(NetworkName.HISTORY, h.size)
        assertEquals(listOf("Kumar & Sons", "K Electricals", "Kumar Electricals"), h.map { it.name })
        // An older call recorded late doesn't push the latest name back.
        h = NetworkName.record(h, "Ravi Kumar", 50, null, "IN")
        assertEquals("Kumar & Sons", NetworkName.latest(h)!!.name)
    }

    @Test fun stored_form_reads_back() {
        val seen = NetworkNameSeen("Ravi Kumar", 1, 2, "sim1", "IN")
        assertEquals(seen, NetworkName.decode(NetworkName.encode(seen)))
        assertNull(NetworkName.decode("not json"))
    }

    @Test fun a_saved_name_wins_then_the_network_then_the_number() {
        assertEquals(NetworkName.Shown("Ana", NetworkName.Source.SAVED), NetworkName.shown("Ana", "Ravi Kumar", "+91981"))
        assertEquals(NetworkName.Shown("Ravi Kumar", NetworkName.Source.NETWORK), NetworkName.shown(null, "Ravi Kumar", "+91981"))
        assertEquals(NetworkName.Shown("Ravi Kumar", NetworkName.Source.NETWORK), NetworkName.shown(" ", "Ravi Kumar", "+91981"))
        assertEquals(NetworkName.Shown("+91981", NetworkName.Source.NUMBER), NetworkName.shown(null, null, "+91981"))
        assertNull(NetworkName.shown(null, null, ""))
    }

    @Test fun notifications_name_only_where_the_lock_screen_shows_names() {
        assertEquals("Ravi Kumar", NetworkName.inNotification("Ravi Kumar", LockScreenCaller.NAME))
        assertEquals("Ravi Kumar", NetworkName.inNotification("Ravi Kumar", LockScreenCaller.NAME_AND_NOTES))
        assertNull(NetworkName.inNotification("Ravi Kumar", LockScreenCaller.INITIALS))
        assertNull(NetworkName.inNotification("Ravi Kumar", LockScreenCaller.NONE))
        assertNull(NetworkName.inNotification(null, LockScreenCaller.NAME))
    }

    @Test fun only_numbers_nobody_saved_keep_a_name() {
        assertEquals(NetworkName.Keep.RECORD, NetworkName.keep(saved = false, private = false))
        assertEquals(NetworkName.Keep.SKIP, NetworkName.keep(saved = true, private = false))
        // A private contact's number: nothing is written, and a name kept before it became private goes.
        assertEquals(NetworkName.Keep.FORGET, NetworkName.keep(saved = false, private = true))
        assertEquals(NetworkName.Keep.FORGET, NetworkName.keep(saved = true, private = true))
    }
}
