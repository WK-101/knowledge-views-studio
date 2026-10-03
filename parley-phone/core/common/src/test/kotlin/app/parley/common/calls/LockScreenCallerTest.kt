package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LockScreenCallerTest {
    @Test fun name_is_shown_as_before() {
        assertEquals("Ada Lovelace", LockScreenCaller.NAME.shownName("Ada Lovelace"))
        assertFalse(LockScreenCaller.NAME.masks(saved = true))
        assertFalse(LockScreenCaller.NAME.masks(saved = false))
    }

    @Test fun initials_keep_only_the_first_letters() {
        assertEquals("AL", LockScreenCaller.INITIALS.shownName("Ada Lovelace", Locale.UK))
        assertEquals("A", LockScreenCaller.INITIALS.shownName("ada", Locale.UK))
        assertEquals("AK", LockScreenCaller.INITIALS.shownName("Ada King Lovelace-Byron Kent", Locale.UK))
        assertTrue(LockScreenCaller.INITIALS.masks(saved = true))
    }

    @Test fun a_name_without_letters_falls_back_to_the_plain_line() {
        assertNull(LockScreenCaller.INITIALS.shownName("🍕 42", Locale.UK))
        assertNull(LockScreenCaller.INITIALS.shownName(null, Locale.UK))
    }

    @Test fun an_unknown_number_keeps_its_number_under_initials_but_not_under_incoming_call() {
        assertFalse(LockScreenCaller.INITIALS.masks(saved = false))
        assertTrue(LockScreenCaller.NONE.masks(saved = false))
        assertNull(LockScreenCaller.NONE.shownName("Ada Lovelace"))
    }

    @Test fun name_and_notes_shows_everything_and_name_holds_the_notes_back() {
        assertTrue(LockScreenCaller.NAME_AND_NOTES.showsNotes)
        assertFalse(LockScreenCaller.NAME.showsNotes)
        assertTrue(LockScreenCaller.NAME.showsName)
        assertTrue(LockScreenCaller.NAME_AND_NOTES.showsName)
        assertFalse(LockScreenCaller.INITIALS.showsName)
        assertFalse(LockScreenCaller.NAME_AND_NOTES.masks(saved = true))
        assertEquals("Ada Lovelace", LockScreenCaller.NAME_AND_NOTES.shownName("Ada Lovelace"))
        // The default holds the notes back.
        assertEquals(LockScreenCaller.NAME, app.parley.common.AppSettings().lockScreenCaller)
    }
}
