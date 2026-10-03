package app.parley.security

import app.parley.data.security.AppPinStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as "Parley is locked" outside its screens, and when a confirmation asks for the Parley PIN. */
class ParleyLockStateTest {
    @Test fun parley_counts_as_locked_once_away_past_its_timeout() {
        // Started at 1 s, stopped at 10 s, lock after 1 minute.
        assertFalse(AppLock.awayPastTimeout(startedAt = 1_000, stoppedAt = 10_000, now = 30_000, lockAfterMinutes = 1))
        assertTrue(AppLock.awayPastTimeout(startedAt = 1_000, stoppedAt = 10_000, now = 70_000, lockAfterMinutes = 1))
        // Started again after the stop: in front, not away.
        assertFalse(AppLock.awayPastTimeout(startedAt = 20_000, stoppedAt = 10_000, now = 999_000, lockAfterMinutes = 1))
        // Never stopped yet.
        assertFalse(AppLock.awayPastTimeout(startedAt = 1_000, stoppedAt = 0, now = 999_000, lockAfterMinutes = 1))
        // "Lock immediately".
        assertTrue(AppLock.awayPastTimeout(startedAt = 1_000, stoppedAt = 10_000, now = 10_000, lockAfterMinutes = 0))
    }

    @Test fun a_parley_pin_is_asked_for_instead_of_the_phones_unlock() {
        val pin = AppPinStore.Summary(pinSet = true)
        val none = AppPinStore.Summary()
        assertTrue(PinConfirm.asksPin(shown = pin, real = pin))
        assertFalse(PinConfirm.asksPin(shown = none, real = none))
        // A duress session where someone turned the PIN "off": the screens show none, so neither does this.
        assertFalse(PinConfirm.asksPin(shown = none, real = pin))
        // Not read yet: fail closed.
        assertTrue(PinConfirm.asksPin(shown = null, real = null))
    }
}
