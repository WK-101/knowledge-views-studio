package app.parley.telecom

import app.parley.common.Verification
import app.parley.common.calls.LockScreenCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Caller on the lock screen" takes out everything that says who is calling, and keeps what the actions need. */
class LockScreenCallerUiTest {
    private fun call(name: String? = "Ada Lovelace", emergency: Boolean = false) = CallUi(
        id = "1", state = CallState.RINGING, number = "+442079460000", hidden = false, name = name, label = "Mobile",
        photoUri = "content://photo/1", backgroundUri = "file://bg", contactId = 7L, incoming = true, connectTimeMillis = 0,
        isConference = false, children = emptyList(), canHold = false, canMerge = false, canSwap = false, canMute = true,
        canSeparate = false, canDisconnectChild = false, canRespondViaText = true, accountLabel = "Work", verification = Verification.NOT_VERIFIED,
        disconnectReason = null, postDialWait = null, silenced = false, isEmergency = emergency, note = "Ask about the invoice",
        lastCall = "Last call 3 days ago", subtitle = "Engineer · Acme", pronouns = "she/her", subject = "Dinner?",
    )

    @Test fun name_leaves_the_call_as_it_was() {
        val c = call()
        assertSame(c, c.forLockScreen(LockScreenCaller.NAME, "Incoming call"))
    }

    @Test fun initials_hide_everything_else_about_the_caller() {
        val c = call().forLockScreen(LockScreenCaller.INITIALS, "Incoming call")
        assertEquals("AL", c.title)
        assertTrue(c.lockMasked)
        // Masked once only: the notification's public version masks a call that may already be masked.
        assertEquals("AL", c.forLockScreen(LockScreenCaller.INITIALS, "Incoming call").title)
        listOf(c.label, c.photoUri, c.backgroundUri, c.note, c.lastCall, c.subtitle, c.pronouns, c.subject).forEach { assertNull(it) }
        // Reply and block still need the number; it just isn't shown.
        assertEquals("+442079460000", c.number)
    }

    @Test fun incoming_call_says_nothing_about_who_it_is() {
        assertEquals("Incoming call", call().forLockScreen(LockScreenCaller.NONE, "Incoming call").title)
        val unknown = call(name = null).forLockScreen(LockScreenCaller.NONE, "Incoming call")
        assertEquals("Incoming call", unknown.title)
        assertTrue(unknown.lockMasked)
    }

    @Test fun an_unknown_number_keeps_its_number_under_initials() {
        val c = call(name = null)
        assertSame(c, c.forLockScreen(LockScreenCaller.INITIALS, "Incoming call"))
        assertFalse(c.lockMasked)
    }

    @Test fun emergency_calls_are_never_masked() {
        val c = call(emergency = true)
        assertSame(c, c.forLockScreen(LockScreenCaller.NONE, "Incoming call"))
    }
}
