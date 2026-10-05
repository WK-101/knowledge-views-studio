package app.parley.telecom.ui

import app.parley.common.Verification
import app.parley.common.circle.Agenda
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.CallerAgenda
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which agenda items the call screen keeps for a call, and when "Did you cover these?" is asked. */
class CallAgendasTest {
    private fun call(id: String, connected: Boolean = true, emergency: Boolean = false, hidden: Boolean = false) = CallUi(
        id = id, state = CallState.DISCONNECTED, number = "+44 20 7946 0000", hidden = hidden, name = "Ada", label = null,
        photoUri = null, contactId = 7L, incoming = true, connectTimeMillis = if (connected) 1_000L else 0L,
        isConference = false, children = emptyList(), canHold = false, canMerge = false, canSwap = false, canMute = false,
        canSeparate = false, canDisconnectChild = false, canRespondViaText = false, accountLabel = null, verification = Verification.NOT_VERIFIED,
        disconnectReason = null, postDialWait = null, silenced = false, isEmergency = emergency,
    )

    @After fun tearDown() = CallAgendas.forgetForTest()

    private fun shownWith(id: String, vararg items: String): CallAgendas.State {
        CallAgendas.loaded(id, CallerAgenda(items.toList()))
        return CallAgendas.calls.getValue(id).also { it.shown = Agenda.Shown.ITEMS }
    }

    @Test fun after_a_connected_call_it_asks_about_what_wasnt_ticked() {
        val s = shownWith("a", "Photos", "Loan")
        s.ticked = setOf("Photos")
        assertEquals(listOf("Loan"), CallAgendas.toAsk(call("a")))
        assertTrue(CallAgendas.asksAfter(call("a")))
        s.answered = true
        assertFalse(CallAgendas.asksAfter(call("a")))
    }

    @Test fun nothing_is_asked_when_it_never_connected_or_couldnt_show() {
        shownWith("b", "Photos")
        assertFalse(CallAgendas.asksAfter(call("b", connected = false)))
        CallAgendas.calls.getValue("b").shown = Agenda.Shown.COUNT
        assertFalse(CallAgendas.asksAfter(call("b")))
        shownWith("c", "Photos")
        assertFalse(CallAgendas.asksAfter(call("c", emergency = true)))
        assertFalse(CallAgendas.asksAfter(call("unknown")))
    }

    @Test fun everything_ticked_asks_nothing() {
        val s = shownWith("d", "Photos")
        s.ticked = setOf("Photos")
        assertFalse(CallAgendas.asksAfter(call("d")))
    }

    @Test fun reading_again_keeps_what_was_ticked_and_shown() {
        val s = shownWith("e", "Photos", "Loan")
        s.ticked = setOf("Photos")
        // Read again after an unlock: "Photos" is no longer open, a new item was added meanwhile.
        CallAgendas.loaded("e", CallerAgenda(listOf("Loan", "Trip")))
        val again = CallAgendas.calls.getValue("e")
        assertEquals(listOf("Photos", "Loan", "Trip"), again.items)
        assertEquals(setOf("Photos"), again.ticked)
        // Locked again: nothing readable now; what was there stays for the end of the call, hidden meanwhile.
        again.shown = Agenda.Shown.ITEMS
        CallAgendas.loaded("e", null)
        assertEquals(Agenda.Shown.NOTHING, CallAgendas.calls.getValue("e").shown)
    }

    @Test fun only_the_last_few_calls_are_kept() {
        (1..10).forEach { shownWith("c$it", "x") }
        assertEquals(setOf("c7", "c8", "c9", "c10"), CallAgendas.calls.keys)
    }
}
