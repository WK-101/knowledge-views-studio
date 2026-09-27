package app.parley.common

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
}
