package app.parley.telecom

import android.content.ComponentName
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screening's verdicts as the in-call path carries them out. */
internal class CallManagerScreeningTest : CallPathTest() {
    @Test fun aBlockedCallIsDeclinedAsUnwanted() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        ringing()
        assertEquals(listOf("rejectCallWithReason(t1, ${Call.REJECT_REASON_UNWANTED})"), telecom.sent)
    }

    @Test fun sendToVoicemailIsAPlainDecline() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.SEND_TO_VOICEMAIL))
        ringing()
        assertEquals(listOf("rejectCall(t1, false, null)"), telecom.sent)
    }

    @Test fun aSilencedCallRingsQuietlyAndIsNotDeclined() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.SILENCE, BlockReason.RULE), verdict = "Silenced by rule")
        val c = ringing()
        assertTrue(ui(c).silenced)
        assertEquals("Silenced by rule", ui(c).verdict)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun outgoingCallsAreNeverScreened() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        dialling("t1")
        assertEquals(0, deps.screened)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun theCallIsHeldBackUntilScreeningAnswers() {
        deps.screen = ScreenOutcome(Decision.Allow)
        deps.screenDelayMs = 500
        val c = ringing()
        assertTrue(CallManager.isScreening(idOf(c)))
        FakeTelecom.advance(600)
        assertFalse(CallManager.isScreening(idOf(c)))
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun failingScreeningLetsTheCallRing() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        deps.screenFails = true
        val c = ringing()
        FakeTelecom.idle()
        assertFalse(CallManager.isScreening(idOf(c)))
        assertTrue(telecom.sent.isEmpty())
        assertFalse(ui(c).silenced)
    }

    @Test fun theScreeningServicesVerdictIsUsedWithoutAskingAgain() {
        deps.screen = ScreenOutcome(Decision.Allow)
        ScreeningGuard.remember("+15551234567", ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE)))
        ringing()
        assertEquals(0, deps.screened)
        assertEquals(listOf("rejectCallWithReason(t1, ${Call.REJECT_REASON_UNWANTED})"), telecom.sent)
    }

    @Test fun anAllowDeferredToTheSimIsScreenedAgainWithIt() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        ScreeningGuard.remember("+15551234567", ScreenOutcome(Decision.Allow, deferredToSim = true))
        add(FakeTelecom.Spec("t1", Call.STATE_RINGING, account = PhoneAccountHandle(ComponentName(context, "Sim"), "sim1")))
        assertEquals(1, deps.screened)
        assertTrue(sent("rejectCallWithReason"))
    }

    @Test fun aWarningShowsOnTheCallerCard() {
        deps.screen = ScreenOutcome(Decision.Allow, verdict = "Likely spam", warn = true)
        val c = ringing()
        assertEquals("Likely spam", ui(c).verdict)
        assertTrue(ui(c).verdictWarn)
    }
}
