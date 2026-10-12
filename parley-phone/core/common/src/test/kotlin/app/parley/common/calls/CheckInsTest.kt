package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInsTest {
    private val sam = Helper("Sam", "+447700900001")
    private val ana = Helper("Ana", "+447700900002", private = true)

    @Test fun only_an_answer_says_fine() {
        assertFalse(CheckIns.asksForHelp(true))
        // Declined or rung out.
        assertTrue(CheckIns.asksForHelp(false))
        // Never rang: a real call was up, or the alarm came too late.
        assertTrue(CheckIns.asksForHelp(null))
    }

    @Test fun a_check_in_never_rings_now() {
        assertFalse(RescueWhen.NOW in CheckIns.whenChoices)
        assertEquals(RescueWhen.IN_15, CheckIns.whenFor(RescueWhen.NOW))
        assertEquals(RescueWhen.AT_TIME, CheckIns.whenFor(RescueWhen.AT_TIME))
    }

    @Test fun the_chosen_helper_else_the_first() {
        assertEquals(ana, CheckIns.helperFor(listOf(sam, ana), ana.number))
        assertEquals(sam, CheckIns.helperFor(listOf(sam, ana), "+15550000000"))
        assertEquals(sam, CheckIns.helperFor(listOf(sam, ana), null))
        assertNull(CheckIns.helperFor(emptyList(), sam.number))
    }

    @Test fun a_private_helper_is_named_by_number_while_hidden() {
        assertEquals("Ana", CheckIns.shownName(ana, hidePrivate = false))
        assertEquals(ana.number, CheckIns.shownName(ana, hidePrivate = true))
        assertEquals("Sam", CheckIns.shownName(sam, hidePrivate = true))
    }

    @Test fun a_check_in_needs_a_helper_and_a_rescue_call_needs_who_calls() {
        assertFalse(CheckIns.ready(checkIn = true, name = "Mum", number = null, helper = null))
        assertTrue(CheckIns.ready(checkIn = true, name = "", number = null, helper = sam.number))
        assertFalse(CheckIns.ready(checkIn = false, name = " ", number = null, helper = sam.number))
        assertTrue(CheckIns.ready(checkIn = false, name = "Mum", number = null, helper = null))
    }
}
