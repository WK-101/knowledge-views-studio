package app.parley.telecom

import app.parley.common.Verification
import app.parley.common.calls.LockScreenCaller
import app.parley.common.people.FreshNumber
import app.parley.common.people.NumberOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fresh-number line: one safety line at a time ("never calls you" first), kept on the lock screen, never for an emergency call. */
class FreshNumberNoticeTest {
    private val fresh = FreshNumber(NumberOrigin.PASTED, since = 1_000, older = "+447700900001")

    private fun call(emergency: Boolean = false) = CallUi(
        id = "1", state = CallState.RINGING, number = "+447700900999", hidden = false, name = "Mum", label = "Mobile",
        photoUri = null, backgroundUri = null, contactId = 7L, incoming = true, connectTimeMillis = 0,
        isConference = false, children = emptyList(), canHold = false, canMerge = false, canSwap = false, canMute = true,
        canSeparate = false, canDisconnectChild = false, canRespondViaText = true, accountLabel = null, verification = Verification.NOT_VERIFIED,
        disconnectReason = null, postDialWait = null, silenced = false, isEmergency = emergency, savedCaller = true, freshNumber = fresh,
    )

    @Test fun shows_for_a_live_saved_call() {
        assertTrue(call().freshNumberNotice)
        assertFalse(call(emergency = true).freshNumberNotice)
        assertFalse(call().copy(state = CallState.DISCONNECTED).freshNumberNotice)
        assertFalse(call().copy(freshNumber = null).freshNumberNotice)
    }

    @Test fun never_calls_you_speaks_first() {
        val both = call().copy(neverCallsYou = true)
        assertTrue(both.neverCallsYouNotice)
        assertFalse(both.freshNumberNotice)
    }

    @Test fun it_names_nobody_so_it_stays_on_the_lock_screen() {
        val masked = call().forLockScreen(LockScreenCaller.NONE, "Incoming call")
        assertTrue(masked.freshNumberNotice)
        assertEquals(fresh, masked.freshNumber)
    }
}
