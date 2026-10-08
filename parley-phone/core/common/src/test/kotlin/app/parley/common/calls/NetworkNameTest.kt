package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // Private contacts that couldn't be checked count as private.
        assertEquals(NetworkName.Keep.FORGET, NetworkName.keep(saved = false, private = null))
    }

    @Test fun a_name_shows_only_for_a_number_known_not_to_be_private() {
        assertTrue(NetworkName.mayShow(saved = false, private = false))
        assertFalse(NetworkName.mayShow(saved = true, private = false))
        assertFalse(NetworkName.mayShow(saved = false, private = true))
        // A private lookup that failed: no name (fail closed).
        assertFalse(NetworkName.mayShow(saved = false, private = null))
    }

    @Test fun invisible_and_direction_characters_are_dropped() {
        // Right-to-left override, isolates, zero-width space and joiner, a byte-order mark.
        assertEquals("Ravi Kumar", NetworkName.clean("Ravi\u200B Ku\u200Dmar\uFEFF", allowed))
        assertEquals("SBI Bank", NetworkName.clean("\u202ESBI\u202C Bank\u2066\u2069", allowed))
        assertEquals("Ravi Kumar", NetworkName.clean("Ravi\tKumar\u0007", allowed))
        assertEquals("Ravi Kumar", NetworkName.clean("Ravi\u00A0\u2028Kumar", allowed))
        assertNull(NetworkName.clean("\u202E\u200B", allowed))
        // Names in other scripts are left as they are.
        assertEquals("राम शर्मा", NetworkName.clean("राम शर्मा", allowed))
    }

    @Test fun a_us_city_and_state_is_not_a_name() {
        listOf("NEW YORK NY", "CHICAGO IL", "ST. LOUIS MO", "SAN JOSE   CA", "SPAM?", "Spam!").forEach { assertNull(it, NetworkName.clean(it, allowed)) }
        // A name in capitals that doesn't end in a state, or one written in mixed case, stays.
        assertEquals("RAVI KUMAR", NetworkName.clean("RAVI KUMAR", allowed))
        assertEquals("SHARMA TRADERS", NetworkName.clean("SHARMA TRADERS", allowed))
        assertEquals("Ana Ng", NetworkName.clean("Ana Ng", allowed))
        assertEquals("Paul Ca", NetworkName.clean("Paul Ca", allowed))
        assertEquals("CA", NetworkName.clean("CA", allowed))
    }

    @Test fun a_line_is_read_with_the_sims_country() {
        // A national number on a French SIM is the same line as the number in full.
        assertEquals("+33612345678", NetworkName.line("06 12 34 56 78", "FR"))
        assertEquals(NetworkName.line("+33 6 12 34 56 78", "FR"), NetworkName.line("0612345678", "FR"))
        // Without the SIM's country, the number stays as it is (read later with the phone's).
        assertEquals("0612345678", NetworkName.line("0612345678", null))
        assertEquals("0612345678", NetworkName.line("0612345678", ""))
    }
}
