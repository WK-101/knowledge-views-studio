package app.parley.telecom

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A second call while one is up: hold & answer, end & answer, hold and resume, and the hang-up shortcut. */
internal class CallWaitingTest : CallPathTest() {
    private val hold = Call.Details.CAPABILITY_HOLD

    @Test fun holdAndAnswerHoldsTheCallInFrontThenAnswers() {
        active("t1", hold)
        val waiting = ringing("t2")
        CallManager.holdAndAnswer(idOf(waiting))
        assertEquals(listOf("holdCall(t1)", "answerCall(t2, 0)"), telecom.sent)
    }

    @Test fun holdAndAnswerNeverAsksToHoldACallThatCantBeHeld() {
        active("t1")
        val waiting = ringing("t2")
        CallManager.holdAndAnswer(idOf(waiting))
        assertEquals(listOf("answerCall(t2, 0)"), telecom.sent)
    }

    @Test fun endAndAnswerAnswersOnceTheFirstCallHasGone() {
        val first = active("t1", hold)
        val waiting = ringing("t2")
        CallManager.endAndAnswer(idOf(waiting))
        assertEquals(listOf("disconnectCall(t1)"), telecom.sent)
        FakeTelecom.advance(500)
        assertFalse("not answered while the first call is still up", sent("answerCall"))
        telecom.update("t1") { it.copy(state = Call.STATE_DISCONNECTED) }
        CallManager.remove(first)
        FakeTelecom.advance(200)
        assertEquals("answerCall(t2, 0)", telecom.sent.last())
        assertTrue(CallManager.wasAnsweredByUser(idOf(waiting)))
    }

    @Test fun endAndAnswerAnswersAfterThreeSecondsAtMost() {
        active("t1")
        val waiting = ringing("t2")
        CallManager.endAndAnswer(idOf(waiting))
        FakeTelecom.advance(2_900)
        assertFalse(sent("answerCall"))
        FakeTelecom.advance(300)
        assertEquals("answerCall(t2, 0)", telecom.sent.last())
    }

    @Test fun theHeldCallResumesWhenTheCallInFrontEnds() {
        active("t1", hold)
        held("t2", hold)
        end("t1")
        FakeTelecom.advance(700)
        assertEquals(listOf("unholdCall(t2)"), telecom.sent)
    }

    @Test fun theHeldCallStaysHeldWhileAnotherCallRings() {
        active("t1", hold)
        held("t2", hold)
        ringing("t3")
        end("t1")
        FakeTelecom.advance(700)
        assertFalse(sent("unholdCall"))
    }

    @Test fun holdToggles() {
        val a = active("t1", hold)
        assertTrue(ui(a).canHold)
        CallManager.toggleHold(idOf(a))
        assertEquals("holdCall(t1)", telecom.sent.last())
        val h = telecom.update("t1") { it.copy(state = Call.STATE_HOLDING) }
        assertEquals(CallState.HOLDING, ui(h).state)
        assertTrue("the held time is kept for the screen", ui(h).heldSinceElapsed > 0)
        CallManager.toggleHold(idOf(h))
        assertEquals("unholdCall(t1)", telecom.sent.last())
    }

    @Test fun theHangUpShortcutEndsTheActiveCallFirst() {
        held("t1")
        dialling("t2")
        active("t3")
        assertTrue(CallManager.hangupForeground())
        assertEquals(listOf("disconnectCall(t3)"), telecom.sent)
    }

    @Test fun theHangUpShortcutThenEndsADiallingCallThenAHeldOne() {
        held("t1")
        dialling("t2")
        CallManager.hangupForeground()
        assertEquals(listOf("disconnectCall(t2)"), telecom.sent)
        end("t2")
        CallManager.hangupForeground()
        assertEquals("disconnectCall(t1)", telecom.sent.last())
    }

    @Test fun theHangUpShortcutLeavesARingingCallAlone() {
        ringing()
        assertFalse(CallManager.hangupForeground())
        assertTrue(telecom.sent.isEmpty())
    }
}
