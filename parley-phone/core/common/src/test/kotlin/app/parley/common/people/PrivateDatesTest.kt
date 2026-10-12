package app.parley.common.people

import app.parley.common.AppSettings
import app.parley.common.security.DuressState
import app.parley.common.security.PrivacyView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private contacts' dates show while private contacts may show, and never otherwise. */
class PrivateDatesTest {
    private val open = PrivacyView.of(AppSettings(hideVault = false), DuressState(), appLocked = false)

    @Test fun shown_while_private_contacts_show() {
        assertTrue(PrivateDates.mayShow(open, lockedByPerson = false))
    }

    @Test fun gone_when_hidden_locked_under_duress_or_on_the_lock_screen() {
        assertFalse("Hide private contacts", PrivateDates.mayShow(PrivacyView.of(AppSettings(hideVault = true), DuressState(), false), false))
        assertFalse("Lock private contacts", PrivateDates.mayShow(open, lockedByPerson = true))
        val duress = PrivacyView.of(AppSettings(hideVault = false), DuressState(hiding = true, vaultLocked = true), false)
        assertFalse("duress", PrivateDates.mayShow(duress, false))
        assertFalse("lock screen", PrivateDates.mayShow(open, lockedByPerson = false, onLockScreen = true))
        assertFalse("unknown", PrivateDates.mayShow(PrivacyView.CLOSED, lockedByPerson = false))
    }
}
