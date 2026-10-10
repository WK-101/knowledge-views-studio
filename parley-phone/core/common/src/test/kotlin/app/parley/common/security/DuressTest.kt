package app.parley.common.security

import app.parley.common.AppSettings
import app.parley.common.DuressView
import app.parley.common.SettingsCatalog
import app.parley.common.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The duress session's state machine and what it hides. */
class DuressTest {
    private val start = DuressMachine.restored(hiding = false, vaultLocked = false)

    @Test fun the_duress_pin_hides_until_the_real_pin() {
        val d = DuressMachine.pinEntered(start, PinVerdict.DURESS, lockVaultOnDuress = true)
        assertTrue(d.session)
        assertTrue(d.hiding)
        assertTrue(d.vaultLocked)
        // Locking ends the session, not the hiding.
        val locked = DuressMachine.locked(d)
        assertFalse(locked.session)
        assertTrue(locked.hiding)
        // A wrong PIN changes nothing; the duress PIN again is another session.
        assertEquals(locked, DuressMachine.pinEntered(locked, PinVerdict.WRONG, true))
        assertTrue(DuressMachine.pinEntered(locked, PinVerdict.DURESS, true).session)
        // Only the real PIN brings everything back.
        val normal = DuressMachine.pinEntered(locked, PinVerdict.NORMAL, true)
        assertEquals(DuressState(LockPhase.OPEN, hiding = false, vaultLocked = false), normal)
    }

    @Test fun hiding_survives_a_restart() {
        val r = DuressMachine.restored(hiding = true, vaultLocked = true)
        assertEquals(LockPhase.LOCKED, r.phase)
        assertTrue(r.hiding)
        assertTrue(r.vaultLocked)
        assertFalse(DuressMachine.restored(hiding = false, vaultLocked = true).vaultLocked)
    }

    @Test fun the_vault_option_is_followed() {
        val d = DuressMachine.pinEntered(start, PinVerdict.DURESS, lockVaultOnDuress = false)
        assertFalse(d.vaultLocked)
        assertFalse(DuressPolicy.hidden(Concealed.PRIVATE_DETAILS, d, discreet = false))
        assertTrue(DuressPolicy.hidden(Concealed.PRIVATE_DETAILS, d.copy(vaultLocked = true), discreet = false))
    }

    @Test fun with_a_parley_pin_only_a_pin_unlocks() {
        // The same with or without a duress PIN, so the lock screen can't tell which.
        assertNull(DuressMachine.otherUnlock(start, pinRequired = true))
        val hidden = DuressMachine.locked(DuressMachine.pinEntered(start, PinVerdict.DURESS, true))
        assertNull(DuressMachine.otherUnlock(hidden, pinRequired = true))
        // Without one (removed after a normal unlock, or data wiped), another unlock opens and nothing stays hidden.
        assertEquals(DuressState(LockPhase.OPEN), DuressMachine.otherUnlock(hidden, pinRequired = false))
    }

    @Test fun hiding_covers_discreet_mode_and_more() {
        val d = DuressMachine.pinEntered(start, PinVerdict.DURESS, true)
        Concealed.entries.forEach { assertTrue(it.name, DuressPolicy.hidden(it, d, discreet = false)) }
        val open = DuressState(LockPhase.OPEN)
        // Outside duress: discreet mode hides its own things only.
        Concealed.entries.forEach { c ->
            assertEquals(c.name, c in DuressPolicy.BY_DISCREET_MODE, DuressPolicy.hidden(c, open, discreet = true))
            assertFalse(c.name, DuressPolicy.hidden(c, open, discreet = false))
        }
    }

    @Test fun effective_settings_force_discreet_mode_but_show_the_users_switches() {
        val stored = AppSettings(appLock = true, hideVault = false, privateVaultHistory = true, themeMode = ThemeMode.DARK)
        assertEquals(stored, DuressPolicy.effective(stored, hiding = false, overlay = null))
        val e = DuressPolicy.effective(stored, hiding = true, overlay = null)
        assertTrue(e.hideVault)
        assertEquals(DuressView(hideVault = false, privateVaultHistory = true), e.duress)
        assertEquals(ThemeMode.DARK, e.themeMode)
    }

    @Test fun session_changes_to_safety_switches_stay_in_memory() {
        val stored = AppSettings(appLock = true, lockAfterMinutes = 1, hideVault = true, privateVaultHistory = true)
        // Someone turns off the app lock, discreet mode and private call history, and changes the theme.
        val shown = DuressPolicy.shown(stored, null)
        val next = shown.copy(appLock = false, hideVault = false, privateVaultHistory = false, themeMode = ThemeMode.LIGHT)
        val (toStore, overlay) = DuressPolicy.split(stored, next)
        assertEquals(stored.copy(themeMode = ThemeMode.LIGHT), toStore)
        assertEquals(SafetyOverlay(appLock = false, hideVault = false, privateVaultHistory = false), overlay)
        // They see their changes; Parley still hides, and keeps private calls out of the system call log.
        val e = DuressPolicy.effective(toStore, hiding = true, overlay = overlay)
        assertFalse(e.appLock)
        assertTrue(e.hideVault)
        assertTrue(e.privateVaultHistory)
        assertEquals(DuressView(hideVault = false, privateVaultHistory = false), e.duress)
        // Switching back to the stored values leaves no overlay.
        assertNull(DuressPolicy.split(stored, DuressPolicy.shown(toStore, overlay).copy(appLock = true, hideVault = true, privateVaultHistory = true)).second)
    }

    @Test fun search_finds_the_duress_pin_whether_or_not_one_is_set() {
        // The duress PIN's row is always there (shown "Off" in a session), so search always finds it, as on a
        // phone where none was ever set; a search that came back empty in a session would give it away.
        assertTrue(SettingsCatalog.entries.any { it.key == "duress_pin" })
    }

    @Test fun pin_changes_wait_like_wrong_tries_and_only_the_real_pin_clears_them() {
        // In a duress session a "new PIN" is a way to try PINs; changes count, whatever was typed.
        var r = PinRecord("c2FsdA==", 10, 1, 1, "aGFzaA==")
        repeat(PinBackoff.FREE_TRIES) {
            assertEquals(0L, PinBackoff.changeWait(r, 1_000L))
            r = PinBackoff.afterChange(r, 1_000L)
        }
        assertEquals(PinBackoff.FIRST_WAIT_MS, PinBackoff.changeWait(r, 1_000L))
        assertEquals(0L, PinBackoff.changeWait(r, 1_000L + PinBackoff.FIRST_WAIT_MS))
        // A duress unlock doesn't reset them (it would undo the limit); the Parley PIN does.
        assertEquals(r.changes, PinBackoff.after(r, PinVerdict.DURESS, 2_000L).changes)
        assertEquals(0, PinBackoff.after(r, PinVerdict.NORMAL, 2_000L).changes)
        // A restart (elapsed time starts again) starts the wait over.
        assertEquals(PinBackoff.FIRST_WAIT_MS, PinBackoff.changeWait(PinBackoff.rebased(r, 10L), 10L))
        // The count survives in the stored record; records from before it still read.
        assertEquals(r, PinRecord.decode(r.encode()))
        val old = r.encode().split(";").take(10).joinToString(";")
        assertEquals(0, PinRecord.decode(old)!!.changes)
    }
}
