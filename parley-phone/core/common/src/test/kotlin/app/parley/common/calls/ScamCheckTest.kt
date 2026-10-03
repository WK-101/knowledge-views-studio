package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamCheckTest {
    @Test fun offered_for_live_calls_from_numbers_that_are_not_saved() {
        assertTrue(ScamCheck.offered(live = true, savedCaller = false, lookedUp = true, hidden = false, emergency = false, conference = false))
        // A hidden number can't be looked up, and isn't saved.
        assertTrue(ScamCheck.offered(live = true, savedCaller = false, lookedUp = false, hidden = true, emergency = false, conference = false))
        // Not before the lookup said who it is, never for a contact.
        assertFalse(ScamCheck.offered(live = true, savedCaller = false, lookedUp = false, hidden = false, emergency = false, conference = false))
        assertFalse(ScamCheck.offered(live = true, savedCaller = true, lookedUp = true, hidden = false, emergency = false, conference = false))
        assertFalse(ScamCheck.offered(live = true, savedCaller = false, lookedUp = true, hidden = false, emergency = true, conference = false))
        assertFalse(ScamCheck.offered(live = true, savedCaller = false, lookedUp = true, hidden = false, emergency = false, conference = true))
        assertFalse(ScamCheck.offered(live = false, savedCaller = false, lookedUp = true, hidden = false, emergency = false, conference = false))
    }

    @Test fun the_signs_cover_the_usual_asks() {
        assertEquals(6, ScamCheck.SIGNS.size)
        assertEquals(ScamCheck.Sign.PRESSURE, ScamCheck.SIGNS.first())
        assertTrue(ScamCheck.safeWordReminder(safeWordSet = true, emergency = false))
        assertFalse(ScamCheck.safeWordReminder(safeWordSet = false, emergency = false))
        assertFalse(ScamCheck.safeWordReminder(safeWordSet = true, emergency = true))
    }

    @Test fun name_reply_only_for_unknown_numbers_and_when_not_blank() {
        val text = "Please text me your name."
        assertEquals(text, NameReply.offered(" $text ", savedCaller = false, hasNumber = true, emergency = false))
        assertNull(NameReply.offered(text, savedCaller = true, hasNumber = true, emergency = false))
        assertNull(NameReply.offered(text, savedCaller = false, hasNumber = false, emergency = false))
        assertNull(NameReply.offered(text, savedCaller = false, hasNumber = true, emergency = true))
        assertNull(NameReply.offered("  ", savedCaller = false, hasNumber = true, emergency = false))
    }

    @Test fun name_reply_comes_first_and_once() {
        val quick = listOf("I'll call you back.", "Please text me your name.")
        assertEquals(listOf("Please text me your name.", "I'll call you back."), NameReply.replies(quick, "Please text me your name."))
        assertEquals(quick, NameReply.replies(quick, null))
    }
}
