package app.parley.telecom.ui

import app.parley.common.Verification
import app.parley.common.calls.LockScreenCaller
import app.parley.common.ux.CallScreenBackground
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.forLockScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Poster style shows only for a call with a picture, never in the small window, over a warning or when masked. */
class CallPosterTest {
    private fun call(picture: String? = "file://bg", warn: Boolean = false, state: CallState = CallState.RINGING) = CallUi(
        id = "1", state = state, number = "+442079460000", hidden = false, name = "Ada Lovelace", label = "Mobile",
        photoUri = "content://photo/1", backgroundUri = picture, contactId = 7L, incoming = true, connectTimeMillis = 0,
        isConference = false, children = emptyList(), canHold = false, canMerge = false, canSwap = false, canMute = true,
        canSeparate = false, canDisconnectChild = false, canRespondViaText = true, accountLabel = null, verification = Verification.NOT_VERIFIED,
        disconnectReason = null, postDialWait = null, silenced = false, isEmergency = false, savedCaller = true,
        verdict = if (warn) "Likely spam" else null, verdictWarn = warn,
    )

    @Test fun poster_for_a_caller_with_a_picture() {
        assertTrue(callBackdropPlan(call(), CallScreenBackground.POSTER).poster)
        assertTrue(callBackdropPlan(call(state = CallState.ACTIVE), CallScreenBackground.POSTER).poster)
        assertFalse(callBackdropPlan(call(picture = null), CallScreenBackground.POSTER).poster)
        assertFalse(callBackdropPlan(call(), CallScreenBackground.CALLER_COLOUR).poster)
    }

    @Test fun no_poster_in_the_small_window_or_over_a_spam_warning() {
        assertFalse(callBackdropPlan(call(), CallScreenBackground.POSTER, picture = false).poster)
        assertFalse(callBackdropPlan(call(warn = true), CallScreenBackground.POSTER).poster)
    }

    @Test fun no_poster_while_the_lock_screen_masks_the_caller() {
        val masked = call().forLockScreen(LockScreenCaller.INITIALS, "Incoming call")
        assertFalse(callBackdropPlan(masked, CallScreenBackground.POSTER).poster)
        assertFalse(callBackdropPlan(masked, CallScreenBackground.POSTER).picture)
    }
}
