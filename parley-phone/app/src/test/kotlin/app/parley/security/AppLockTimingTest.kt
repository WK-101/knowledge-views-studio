package app.parley.security

import android.os.Looper
import app.parley.common.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * When Parley's own lock engages: at once with "lock immediately", after the chosen delay otherwise, never with the lock
 * off; every lock forgets what was opened for the session, and the screen going off forgets it without locking.
 */
@RunWith(RobolectricTestRunner::class)
class AppLockTimingTest {
    private var forgotten = 0
    private var engaged = 0
    private val lockOn = AppSettings(appLock = true, lockAfterMinutes = 5)
    private val immediate = AppSettings(appLock = true, lockAfterMinutes = 0)

    @Before fun setUp() {
        AppLock.onLock = { forgotten++ }
        AppLock.onEngaged = { engaged++ }
        AppLock.unlocked()
        AppLock.onStart(lockOn)
        forgotten = 0
        engaged = 0
    }

    @After fun tearDown() {
        AppLock.onLock = null
        AppLock.onEngaged = null
        AppLock.onStart(AppSettings(appLock = false))
    }

    private fun away(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    @Test fun aShortTripAwayStaysUnlocked() {
        assertFalse(AppLock.locked.value)
        AppLock.onStop(lockOn)
        away(Duration.ofMinutes(2))
        AppLock.onStart(lockOn)
        assertFalse(AppLock.locked.value)
        assertEquals(0, forgotten)
    }

    @Test fun pastTheDelayItLocksOnReturn() {
        AppLock.onStop(lockOn)
        away(Duration.ofMinutes(6))
        // Outside Parley (the widgets) it already counts as locked; the lock itself engages at the next start.
        assertTrue(AppLock.lockedFor(lockOn))
        assertFalse(AppLock.locked.value)
        AppLock.onStart(lockOn)
        assertTrue(AppLock.locked.value)
        assertEquals(1, forgotten)
        assertEquals(1, engaged)
    }

    @Test fun lockImmediatelyLocksOnLeaving() {
        AppLock.onStop(immediate)
        assertTrue(AppLock.locked.value)
        assertEquals(1, engaged)
    }

    @Test fun theScreenGoingOffForgetsTheSessionButDoesntLock() {
        AppLock.onStop(lockOn)
        AppLock.onScreenOff()
        assertEquals(1, forgotten)
        assertEquals(0, engaged)
        assertFalse(AppLock.locked.value)
    }

    @Test fun lockNowWaitsForATapAndTheNextStartAsksAgain() {
        AppLock.lockNowByUser()
        assertTrue(AppLock.locked.value)
        assertFalse(AppLock.promptOnShow)
        AppLock.onStart(lockOn)
        assertTrue(AppLock.promptOnShow)
        assertTrue(AppLock.locked.value)
    }

    @Test fun withTheLockOffNothingLocks() {
        val off = AppSettings(appLock = false)
        AppLock.onStart(off)
        AppLock.onStop(off)
        away(Duration.ofHours(1))
        AppLock.onStart(off)
        assertFalse(AppLock.locked.value)
        assertFalse(AppLock.lockedFor(off))
        AppLock.onScreenOff()
        assertEquals(0, forgotten)
    }
}
