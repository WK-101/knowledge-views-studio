package app.parley.common.security

import app.parley.common.calls.LockScreenCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recents thumbnails and FLAG_SECURE (docs/audit/SECURITY_PRESSURE_7_0.md, recents finding). */
class WindowShieldTest {
    private val shown = PrivacyView(
        discreet = false, duress = DuressState(hiding = false, vaultLocked = false), lockScreen = LockScreenCaller.NAME, appLocked = false,
    )

    @Test fun private_contacts_on_screen_keep_parley_out_of_recents_without_the_app_lock() {
        assertTrue(WindowShield.privateExposed(shown, hasPrivate = true, detailsOpen = false))
        assertTrue(WindowShield.privateExposed(shown, hasPrivate = false, detailsOpen = true))
        val w = WindowShield.window(34, appLock = false, locked = false, secureScreen = false, privateExposed = true, leaving = false)
        assertFalse(w.recentsScreenshot)
        assertFalse("the screen itself stays usable (screenshots by the user)", w.secure)
        // Before Android 13 only FLAG_SECURE blanks the picture, set as Parley leaves.
        assertTrue(WindowShield.window(31, false, false, false, privateExposed = true, leaving = true).secure)
        assertFalse(WindowShield.window(31, false, false, false, privateExposed = true, leaving = false).secure)
    }

    @Test fun hidden_or_absent_private_contacts_change_nothing() {
        assertFalse(WindowShield.privateExposed(shown.copy(discreet = true), hasPrivate = true, detailsOpen = true))
        assertFalse(WindowShield.privateExposed(shown.copy(duress = DuressState(hiding = true, vaultLocked = true)), true, true))
        assertFalse(WindowShield.privateExposed(PrivacyView.CLOSED, true, true))
        assertFalse(WindowShield.privateExposed(shown, hasPrivate = false, detailsOpen = false))
        assertEquals(WindowShield.Window(recentsScreenshot = true, secure = false), WindowShield.window(34, false, false, false, false, leaving = true))
    }

    @Test fun the_app_lock_and_hide_screen_content_behave_as_before() {
        val cases = listOf(29, 33, 36).flatMap { sdk -> (0 until 8).map { bits -> sdk to bits } }
        for ((sdk, bits) in cases) {
            val (locked, leaving, secure) = Triple(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val w = WindowShield.window(sdk, appLock = true, locked = locked, secureScreen = secure, privateExposed = false, leaving = leaving)
            assertFalse(w.recentsScreenshot)
            assertEquals(secure || locked || (leaving && sdk < 33), w.secure)
        }
        assertTrue(WindowShield.window(34, false, false, secureScreen = true, privateExposed = false, leaving = false).secure)
    }

    @Test fun the_call_screen_is_blank_in_recents_only_with_a_private_contact_on_the_line() {
        for (sdk in listOf(29, 33, 36)) {
            for (leaving in listOf(false, true)) {
                // A normal call: as before, the thumbnail allowed and the window secure only with "Hide screen content".
                val normal = WindowShield.callWindow(sdk, loaded = true, secureScreen = false, privateOnCall = false, leaving = leaving)
                assertEquals(WindowShield.Window(true, false), normal)
                assertTrue(WindowShield.callWindow(sdk, loaded = true, secureScreen = true, privateOnCall = false, leaving = leaving).secure)
                // A private contact on the line: no thumbnail (Android 13+), FLAG_SECURE as it leaves before that.
                val w = WindowShield.callWindow(sdk, loaded = true, secureScreen = false, privateOnCall = true, leaving = leaving)
                assertFalse(w.recentsScreenshot)
                assertEquals(leaving && sdk < WindowShield.RECENTS_SWITCH_SDK, w.secure)
            }
            // Settings not read yet: secure, as before.
            assertTrue(WindowShield.callWindow(sdk, loaded = false, secureScreen = false, privateOnCall = false, leaving = false).secure)
        }
    }
}
