package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallWaitingTest {
    private data class C(val id: String, val s: LiveCallState, val sim: Boolean = false)

    private fun slots(vararg c: C) = CallWaiting.slots(c.toList()) { it.s }

    // ---- P9 / A1: call waiting

    @Test fun a_second_ringing_call_is_call_waiting() {
        val s = slots(C("a", LiveCallState.ACTIVE), C("b", LiveCallState.RINGING))
        assertEquals("b", s.primary?.id)
        assertEquals("a", s.current?.id)
        assertTrue(s.waiting)
    }

    @Test fun a_ringing_call_with_only_a_held_call_is_call_waiting_too() {
        val s = slots(C("a", LiveCallState.HOLDING), C("b", LiveCallState.RINGING))
        assertTrue(s.waiting)
        assertEquals("a", s.current?.id)
        assertEquals(listOf("a"), s.held.map { it.id })
    }

    @Test fun a_lone_ringing_call_is_a_normal_incoming_call() {
        assertFalse(slots(C("b", LiveCallState.RINGING)).waiting)
        assertFalse(slots(C("a", LiveCallState.ACTIVE), C("c", LiveCallState.DIALING)).waiting)
        assertEquals("a", slots(C("c", LiveCallState.DIALING), C("a", LiveCallState.ACTIVE)).primary?.id)
        assertNull(slots().primary)
    }

    // ---- P1: picture-in-picture

    @Test fun pip_never_for_ringing_calls_or_the_sim_picker() {
        val pip = { l: List<C> -> CallWaiting.pipAllowed(l, { it.s }, { it.sim }) }
        assertTrue(pip(listOf(C("a", LiveCallState.ACTIVE))))
        assertTrue(pip(listOf(C("a", LiveCallState.DIALING))))
        assertFalse(pip(emptyList()))
        assertFalse(pip(listOf(C("a", LiveCallState.ACTIVE), C("b", LiveCallState.RINGING))))
        assertFalse(pip(listOf(C("a", LiveCallState.OTHER, sim = true))))
    }
}
