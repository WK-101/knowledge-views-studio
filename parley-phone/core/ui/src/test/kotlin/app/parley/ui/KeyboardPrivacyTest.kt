package app.parley.ui

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Private text never teaches the keyboard (docs/audit/SECURITY_PRESSURE_7_0.md, keyboard finding). */
class KeyboardPrivacyTest {
    @Test fun the_request_adds_no_personalized_learning_and_keeps_every_other_option() {
        val before = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        val after = KeyboardPrivacy.noLearning(before)
        assertEquals(before or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, after)
        assertEquals(after, KeyboardPrivacy.noLearning(after))
    }
}
