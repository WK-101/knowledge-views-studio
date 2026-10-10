package app.parley.common.security

import app.parley.common.AppSettings
import app.parley.common.calls.LockScreenCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one privacy rule: what each switch hides, together, and that it fails closed. */
class PrivacyViewTest {
    private val open = DuressState(LockPhase.OPEN)
    private val hiding = DuressState(LockPhase.DURESS, hiding = true)

    @Test fun private_contacts_show_only_without_discreet_mode_or_a_duress_hiding() {
        assertTrue(PrivacyView.of(AppSettings(), open, appLocked = false).privateShown)
        assertTrue(PrivacyView.of(AppSettings(hideVault = true), open, appLocked = false).privateHidden)
        // A duress hiding hides them whatever the switch says, even if the settings weren't made "effective".
        val duress = PrivacyView.of(AppSettings(hideVault = false), hiding, appLocked = false)
        assertTrue(duress.privateHidden)
        assertTrue(duress.discreet)
    }

    @Test fun notes_and_safe_words_hide_only_under_duress() {
        val discreet = PrivacyView.of(AppSettings(hideVault = true), open, appLocked = false)
        assertTrue(discreet.notesShown && discreet.circleNotesShown && discreet.safeWordsShown)
        val duress = PrivacyView.of(AppSettings(), hiding, appLocked = false)
        assertFalse(duress.notesShown || duress.circleNotesShown || duress.safeWordsShown)
        assertTrue(duress.hides(Concealed.SHARED_WITH))
        assertTrue(discreet.hides(Concealed.PRIVATE_CALL_HISTORY))
        assertFalse(discreet.hides(Concealed.SHARED_WITH))
    }

    @Test fun the_app_lock_counts_only_when_it_is_on() {
        assertFalse(PrivacyView.of(AppSettings(appLock = false), open, appLocked = true).appLocked)
        assertTrue(PrivacyView.of(AppSettings(appLock = true), open, appLocked = true).appLocked)
    }

    @Test fun the_lock_screen_rule_shortens_names_only_while_locked() {
        val initials = PrivacyView.of(AppSettings(lockScreenCaller = LockScreenCaller.INITIALS), open, appLocked = false)
        assertEquals("AL", initials.lockScreenName("Ada Lovelace", locked = true))
        assertEquals("Ada Lovelace", initials.lockScreenName("Ada Lovelace", locked = false))
    }

    @Test fun closed_hides_everything_and_says_nothing_on_the_lock_screen() {
        val c = PrivacyView.CLOSED
        assertTrue(c.privateHidden && c.hiding && c.appLocked)
        assertFalse(c.notesShown || c.safeWordsShown)
        assertTrue(Concealed.entries.all { c.hides(it) })
        assertNull(c.lockScreenName("Ada Lovelace", locked = true))
        // With only the duress state at hand: the duress-only items follow it, private contacts stay hidden.
        val noDuress = PrivacyView.duressOnly(open)
        assertTrue(noDuress.notesShown)
        assertTrue(noDuress.privateHidden)
        assertFalse(PrivacyView.duressOnly(hiding).notesShown)
    }
}
