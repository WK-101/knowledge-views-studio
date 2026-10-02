package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReasonTest {
    private fun facts(simId: String? = null, sims: Map<String, Boolean> = mapOf("a" to true), canText: Boolean = true, emergency: Boolean = false) =
        ReasonFacts(emergency, simId, sims, canText)

    @Test fun offered_for_numbers_not_codes() {
        assertTrue(CallReason.offered("+44 20 7946 0000"))
        assertFalse(CallReason.offered("*100#"))
        assertFalse(CallReason.offered("#31#"))
        assertFalse(CallReason.offered(" "))
        assertFalse(CallReason.offered(null))
    }

    @Test fun the_subject_goes_with_the_call_when_the_sim_carries_it() {
        assertEquals(listOf(ReasonWay.SUBJECT, ReasonWay.TEXT_FIRST), CallReason.ways(facts(simId = "a")))
        assertEquals(listOf(ReasonWay.SUBJECT), CallReason.ways(facts(simId = "a", canText = false)))
    }

    @Test fun otherwise_text_first() {
        assertEquals(listOf(ReasonWay.TEXT_FIRST), CallReason.ways(facts(simId = "a", sims = mapOf("a" to false))))
        assertEquals(emptyList<ReasonWay>(), CallReason.ways(facts(sims = emptyMap(), canText = false)))
    }

    @Test fun a_sim_still_to_be_chosen_needs_every_sim_to_carry_it() {
        assertTrue(CallReason.subjectSupported(null, mapOf("a" to true, "b" to true)))
        assertFalse(CallReason.subjectSupported(null, mapOf("a" to true, "b" to false)))
        assertFalse(CallReason.subjectSupported(null, emptyMap()))
        // A chosen SIM that isn't listed (gone meanwhile): judged like an unknown one.
        assertTrue(CallReason.subjectSupported("x", mapOf("a" to true)))
        assertTrue(CallReason.subjectSupported("b", mapOf("a" to false, "b" to true)))
    }

    @Test fun never_for_an_emergency_call() {
        assertEquals(emptyList<ReasonWay>(), CallReason.ways(facts(simId = "a", emergency = true)))
    }

    @Test fun the_reason_is_cleaned_and_cut_to_the_network_limit() {
        assertEquals("Your parcel", CallReason.clean("  Your\nparcel‮ "))
        assertEquals("Your", CallReason.clean("Your parcel", maxLength = 5))
        assertEquals("Your parcel", CallReason.clean("Your parcel", maxLength = 0))
        assertNull(CallReason.clean("   "))
    }
}
