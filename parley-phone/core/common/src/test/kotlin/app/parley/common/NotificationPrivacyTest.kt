package app.parley.common

import app.parley.common.calls.LockScreenCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPrivacyTest {
    /** A French and a Spanish mobile that share their last 9 digits. */
    private val fr = "+33612345678"

    @Test fun missed_call_name_respects_discreet_mode() {
        assertEquals("Anna", NotificationPrivacy.missedCallName("Anna", "Secret", hideVault = true, number = fr))
        assertEquals("Secret", NotificationPrivacy.missedCallName(null, "Secret", hideVault = false, number = fr))
        assertEquals(fr, NotificationPrivacy.missedCallName(null, "Secret", hideVault = true, number = fr))
        assertNull(NotificationPrivacy.missedCallName(null, null, hideVault = false, number = ""))
    }

    @Test fun private_label_never_shown() {
        assertNull(NotificationPrivacy.shownLabel("Private"))
        assertNull(NotificationPrivacy.shownLabel("private"))
        assertNull(NotificationPrivacy.shownLabel(" "))
        assertEquals("Mobile", NotificationPrivacy.shownLabel("Mobile"))
    }

    private fun screened(
        saved: String? = null,
        vault: String? = null,
        hideVault: Boolean = false,
        network: String? = null,
        number: String? = fr,
        lockScreen: LockScreenCaller = LockScreenCaller.NAME,
        locked: Boolean = false,
    ) = NotificationPrivacy.screenedCallName(saved, vault, hideVault, network, number, lockScreen, locked)

    @Test fun a_screened_call_names_a_private_contact_only_while_they_show() {
        assertEquals("Dr Rahman", screened(vault = "Dr Rahman"))
        // Discreet mode, and a duress unlock (which forces it on): the number, as for anyone unsaved.
        assertEquals(fr, screened(vault = "Dr Rahman", hideVault = true))
        assertEquals(screened(hideVault = true), screened(vault = "Dr Rahman", hideVault = true))
        // A contact or an archived contact still shows by name.
        assertEquals("Anna", screened(saved = "Anna", vault = "Dr Rahman", hideVault = true))
        // A hidden number and nobody known: nothing to name.
        assertNull(screened(vault = "Dr Rahman", hideVault = true, number = null))
    }

    @Test fun a_caller_whose_privacy_is_unknown_reads_like_a_stranger() {
        // The private lookup failed: no private name, and no network name either (the caller passes none then).
        assertEquals(fr, screened(vault = null, network = null))
        assertEquals(screened(), screened(vault = null))
    }

    @Test fun the_network_name_comes_after_saved_names() {
        assertEquals("Ravi Kumar", screened(network = "Ravi Kumar"))
        assertEquals("Anna", screened(saved = "Anna", network = "Ravi Kumar"))
    }

    @Test fun the_lock_screen_choice_shortens_a_saved_name_while_locked() {
        assertEquals("Dr Rahman", screened(vault = "Dr Rahman", lockScreen = LockScreenCaller.NAME, locked = true))
        assertEquals("DR", screened(vault = "Dr Rahman", lockScreen = LockScreenCaller.INITIALS, locked = true))
        assertNull(screened(vault = "Dr Rahman", lockScreen = LockScreenCaller.NONE, locked = true))
        // Unlocked, the name shows whatever the choice.
        assertEquals("Dr Rahman", screened(vault = "Dr Rahman", lockScreen = LockScreenCaller.NONE, locked = false))
        // An unsaved number keeps its number under Initials, and shows nothing under Nothing.
        assertEquals(fr, screened(lockScreen = LockScreenCaller.INITIALS, locked = true))
        assertNull(screened(lockScreen = LockScreenCaller.NONE, locked = true))
        // A hidden private contact looks the same as that unsaved number on every lock screen.
        LockScreenCaller.entries.forEach { mode ->
            assertEquals(screened(lockScreen = mode, locked = true), screened(vault = "Dr Rahman", hideVault = true, lockScreen = mode, locked = true))
        }
    }
}
