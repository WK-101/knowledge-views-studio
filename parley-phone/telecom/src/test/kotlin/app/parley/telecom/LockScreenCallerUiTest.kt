package app.parley.telecom

import app.parley.common.Verification
import app.parley.common.calls.LockScreenCaller
import app.parley.common.calltime.Countdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Caller on the lock screen" takes out everything that says who is calling, and keeps what the actions need. */
class LockScreenCallerUiTest {
    private fun call(
        name: String? = "Ada Lovelace",
        emergency: Boolean = false,
        saved: Boolean = name != null,
        number: String = "+442079460000",
        children: List<CallUi> = emptyList(),
    ) = CallUi(
        id = number, state = CallState.RINGING, number = number, hidden = false, name = name, label = "Mobile",
        photoUri = "content://photo/1", backgroundUri = "file://bg", contactId = 7L, incoming = true, connectTimeMillis = 0,
        isConference = children.isNotEmpty(), children = children, canHold = false, canMerge = false, canSwap = false, canMute = true,
        canSeparate = false, canDisconnectChild = false, canRespondViaText = true, accountLabel = "Work", verification = Verification.NOT_VERIFIED,
        disconnectReason = null, postDialWait = null, silenced = false, isEmergency = emergency, note = "Ask about the invoice",
        lastCall = "Last call 3 days ago", subtitle = "Engineer · Acme", pronouns = "she/her", subject = "Dinner?",
        savedCaller = saved, rangThrough = "Rang through: in Family", verdict = "Allowed by 'Plumber'", silenceReason = "Drive profile on",
    )

    @Test fun name_and_notes_leaves_the_call_as_it_was() {
        val c = call()
        assertSame(c, c.forLockScreen(LockScreenCaller.NAME_AND_NOTES, "Incoming call"))
    }

    @Test fun name_keeps_the_name_but_holds_the_notes_back() {
        val c = call().copy(context = "Who is this: the plumber").forLockScreen(LockScreenCaller.NAME, "Incoming call")
        assertEquals("Ada Lovelace", c.title)
        assertFalse(c.lockMasked)
        assertNull(c.note)
        assertNull(c.context)
        assertNull(c.lastCall)
        // The rest that names them stays, as it did.
        assertEquals("Mobile", c.label)
        // A call with nothing to hold back is returned as it is.
        val plain = call().copy(note = null, lastCall = null)
        assertSame(plain, plain.forLockScreen(LockScreenCaller.NAME, "Incoming call"))
    }

    @Test fun initials_hide_everything_else_about_the_caller() {
        val c = call().forLockScreen(LockScreenCaller.INITIALS, "Incoming call")
        assertEquals("AL", c.title)
        assertTrue(c.lockMasked)
        // Masked once only: the notification's public version masks a call that may already be masked.
        assertEquals("AL", c.forLockScreen(LockScreenCaller.INITIALS, "Incoming call").title)
        listOf(c.label, c.photoUri, c.backgroundUri, c.note, c.lastCall, c.subtitle, c.pronouns, c.subject, c.rangThrough, c.verdict, c.silenceReason)
            .forEach { assertNull(it) }
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

    @Test fun a_conferences_people_are_masked_too() {
        val conf = call().copy(isConference = true, children = listOf(call(name = "Grace Hopper"), call(name = null)))
        val c = conf.forLockScreen(LockScreenCaller.INITIALS, "Incoming call")
        assertEquals(listOf("GH", "+442079460000"), c.children.map { it.title })
        assertNull(c.children.first().photoUri)
    }

    @Test fun a_name_the_network_sent_for_an_unknown_number_is_not_a_saved_name() {
        // Caller ID from the network (CNAP): not a contact, so Initials keeps the number to decide by.
        val c = call(name = "J SMITH", saved = false)
        assertSame(c, c.forLockScreen(LockScreenCaller.INITIALS, "Incoming call"))
        assertEquals("Incoming call", c.forLockScreen(LockScreenCaller.NONE, "Incoming call").title)
    }

    @Test fun a_screening_warning_stays_on_a_masked_call() {
        val c = call().copy(verdict = "Likely spam · FTC list", verdictWarn = true).forLockScreen(LockScreenCaller.NONE, "Incoming call")
        assertEquals("Likely spam · FTC list", c.verdict)
    }

    @Test fun conference_participants_are_masked_when_the_conference_has_no_name() {
        val kids = listOf(call(name = "Ada Lovelace", number = "+441"), call(name = "Grace Hopper", number = "+442"), call(name = null, number = "+443"))
        val conference = call(name = null, saved = false, number = "", children = kids)
        val shown = conference.forLockScreen(LockScreenCaller.INITIALS, "Ongoing call")
        assertEquals(listOf("AL", "GH", "+443"), shown.children.map { it.title })
        assertEquals(listOf(true, true, false), shown.children.map { it.lockMasked })
        shown.children.take(2).forEach { assertNull(it.photoUri) }
        val none = conference.forLockScreen(LockScreenCaller.NONE, "Ongoing call")
        assertTrue(none.children.all { it.lockMasked && it.title == "Ongoing call" })
    }

    @Test fun a_limit_named_after_the_caller_is_not_shown_on_a_masked_call() {
        val timing = CallTiming(Countdown(startElapsed = 0, limitMs = 600_000), "Limit for Ana", quotaUsed = false)
        assertEquals("Limit for Ana", timing.shownFor(call()).source)
        assertNull(timing.shownFor(call().forLockScreen(LockScreenCaller.INITIALS, "Incoming call")).source)
    }
}
