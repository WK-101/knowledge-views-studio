package app.parley.telecom.ui

import app.parley.common.Verification
import app.parley.common.calls.CallerLine
import app.parley.common.calls.LockScreenCaller
import app.parley.common.circle.Occasion
import app.parley.common.people.FreshNumber
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.CallerMemory
import app.parley.telecom.forLockScreen
import app.parley.common.people.NumberOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The incoming caller header keeps three lines while ringing (safety, who, agenda); the birthday line follows the lock screen's rules. */
class CallerLinesTest {
    private fun call(state: CallState = CallState.RINGING) = CallUi(
        id = "c1", state = state, number = "+442079460000", hidden = false, name = "Ada Lovelace", label = "Mobile",
        photoUri = null, contactId = 7L, incoming = true, connectTimeMillis = 0, isConference = false, children = emptyList(),
        canHold = false, canMerge = false, canSwap = false, canMute = true, canSeparate = false, canDisconnectChild = false,
        canRespondViaText = true, accountLabel = null, verification = Verification.NOT_VERIFIED, disconnectReason = null,
        postDialWait = null, silenced = false, isEmergency = false, savedCaller = true,
        nativeName = "Ада", subtitle = "Engineer · Acme", pronouns = "she/her", note = "Ask about the invoice",
        promisedAt = 1_000L, occasion = Occasion.BIRTHDAY,
    )

    @Test fun ringing_shows_the_first_three_lines_in_order() {
        val shown = callerLinesFor(call(), ended = false, locked = false)
        assertEquals(setOf(CallerLine.NATIVE_NAME, CallerLine.PROMISED, CallerLine.OCCASION), shown)
    }

    @Test fun a_safety_line_comes_first_and_pushes_the_last_who_line_out() {
        val fresh = call().copy(freshNumber = FreshNumber(NumberOrigin.PASTED, since = 1_000, older = null))
        val shown = callerLinesFor(fresh, ended = false, locked = false)
        assertEquals(setOf(CallerLine.FRESH_NUMBER, CallerLine.NATIVE_NAME, CallerLine.PROMISED), shown)
    }

    @Test fun lines_hidden_on_the_lock_screen_make_room_for_the_next() {
        // "You said you'd call" waits for the unlock, so the next line takes its place.
        val shown = callerLinesFor(call(), ended = false, locked = true)
        assertEquals(setOf(CallerLine.NATIVE_NAME, CallerLine.OCCASION, CallerLine.SUBTITLE), shown)
    }

    @Test fun once_answered_every_line_may_show() {
        assertEquals(CallerLine.entries.toSet(), callerLinesFor(call(CallState.ACTIVE), ended = false, locked = false))
    }

    @Test fun open_promises_no_longer_make_a_caller_card_on_their_own() {
        val c = call().copy(note = null, memory = CallerMemory(lastNote = null, promises = listOf("pay Ana back")))
        assertTrue(CallerLine.CALLER_CARD !in ringingLines(c, locked = false))
    }

    @Test fun the_birthday_line_shows_on_the_lock_screen_only_where_notes_may() {
        assertEquals(Occasion.BIRTHDAY, call().forLockScreen(LockScreenCaller.NAME_AND_NOTES, "Incoming call").occasion)
        assertNull(call().forLockScreen(LockScreenCaller.NAME, "Incoming call").occasion)
        assertNull(call().forLockScreen(LockScreenCaller.INITIALS, "Incoming call").occasion)
    }
}
