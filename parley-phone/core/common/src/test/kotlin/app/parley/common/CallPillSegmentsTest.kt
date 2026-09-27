package app.parley.common

import app.parley.common.calls.CallPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPillSegmentsTest {
    private fun sim(id: String, label: String, slot: Int) = SimAccount(id, label, null, 0, slot)

    @Test fun one_sim_or_many_accounts_get_a_single_pill() {
        assertTrue(CallPill.segments(emptyList(), null).isEmpty())
        assertTrue(CallPill.segments(listOf(sim("a", "Vodafone", 0)), null).isEmpty())
        val four = (0..3).map { sim("s$it", "SIM ${it + 1}", it) }
        assertTrue(CallPill.segments(four, null).isEmpty())
    }

    @Test fun two_and_three_sims_get_a_segment_each_in_order() {
        val two = CallPill.segments(listOf(sim("a", "SIM 1", 0), sim("b", "Orange", 1)), preferredId = "b")
        assertEquals(listOf("a", "b"), two.map { it.simId })
        assertNull(two[0].label) // generic: the UI shows "SIM 1"
        assertNull(two[0].carrier)
        assertEquals(1, two[0].slot)
        assertEquals("Orange", two[1].label)
        assertEquals("Orange", two[1].carrier)
        assertFalse(two[0].preferred)
        assertTrue(two[1].preferred)
        val three = CallPill.segments(listOf(sim("a", "A", 0), sim("b", "B", 1), sim("c", "C", 2)), null)
        assertEquals(3, three.size)
        assertTrue(three.none { it.preferred })
    }

    @Test fun long_labels_are_shortened_for_the_segment_but_kept_whole_for_talkback() {
        val s = CallPill.segments(listOf(sim("a", "Vodafone  UK Business", 0), sim("b", "T-Mobile", 1)), null)
        assertEquals("Vodafone…", s[0].label)
        assertEquals("Vodafone UK Business", s[0].carrier)
        assertEquals("T-Mobile", s[1].label)
        val three = CallPill.segments(listOf(sim("a", "T-Mobile", 0), sim("b", "Verizon", 1), sim("c", "AT&T", 2)), null)
        assertEquals("T-Mob…", three[0].label)
        assertEquals("Veriz…", three[1].label)
        assertEquals("AT&T", three[2].label)
    }

    @Test fun same_carrier_twice_shows_the_slots() {
        val s = CallPill.segments(listOf(sim("a", "Airtel", 0), sim("b", "airtel", 1)), null)
        assertNull(s[0].label)
        assertNull(s[1].label)
        assertEquals("Airtel", s[0].carrier) // TalkBack still says the carrier
        // Two labels that only differ after the cut also fall back to the slots.
        val cut = CallPill.segments(listOf(sim("a", "Jio Prepaid One", 0), sim("b", "Jio Prepaid Two", 1)), null)
        assertNull(cut[0].label)
        assertNull(cut[1].label)
    }

    @Test fun generic_labels() {
        assertTrue(CallPill.isGeneric("SIM 1", 1))
        assertTrue(CallPill.isGeneric("sim2", 2))
        assertTrue(CallPill.isGeneric("SIM 3", null))
        assertFalse(CallPill.isGeneric("SIM 2", 1)) // someone named SIM 1 "SIM 2": that's a label
        assertFalse(CallPill.isGeneric("SIMply", 1))
    }

    @Test fun shorten_rules() {
        assertEquals("Orange", CallPill.shorten("Orange", 10))
        assertEquals("Orange", CallPill.shorten("  Orange ", 10))
        assertEquals("Work\u2026", CallPill.shorten("Work phone line", 6))
        assertTrue(CallPill.shorten("Supercalifragilistic", 10).length <= 10)
        assertEquals("Supercali…", CallPill.shorten("Supercalifragilistic", 10))
        // Never splits an emoji (a surrogate pair).
        val emoji = CallPill.shorten("Work💼phone", 6)
        assertEquals("Work…", emoji)
        assertEquals(CallPill.maxChars(2), 10)
        assertTrue(CallPill.maxChars(3) < CallPill.maxChars(2))
    }

    @Test fun folded_button_badge() {
        assertNull(CallPill.badge(""))
        assertNull(CallPill.badge("abc"))
        assertEquals("112", CallPill.badge("112"))
        assertEquals("…5678", CallPill.badge("+33 6 12 34 5678"))
        assertEquals("*#06", CallPill.badge("*#06"))
    }
}
