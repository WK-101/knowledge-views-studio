package app.parley.telecom

import app.parley.common.Decision
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.CallExtrasConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Answer automatically" on the call path: armed, counted down, and every reason it must not answer. */
internal class AutoAnswerCallTest : CallPathTest() {
    private val wait = AutoAnswer.DEFAULT_SECONDS * 1000L

    private fun chosenCaller() {
        deps.autoAnswerConfig = CallExtrasConfig(autoAnswerChosen = true)
        contact("+15551234567", chosen = true)
    }

    @Test fun aChosenCallerIsAnsweredAfterTheCountdown() {
        chosenCaller()
        val c = ringing()
        assertTrue("the screen shows the countdown", ui(c).autoAnswerAt > 0)
        FakeTelecom.advance(wait - 100)
        assertFalse(sent("answerCall"))
        FakeTelecom.advance(200)
        assertEquals(listOf("answerCall(t1, 0)"), telecom.sent)
    }

    @Test fun offByDefault() {
        contact("+15551234567", chosen = true)
        val c = ringing()
        assertEquals(0L, ui(c).autoAnswerAt)
        FakeTelecom.advance(wait * 2)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun cancelLetsItRingOn() {
        chosenCaller()
        val c = ringing()
        CallManager.cancelAutoAnswer(idOf(c))
        assertEquals(0L, ui(c).autoAnswerAt)
        FakeTelecom.advance(wait * 2)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun aSecondCallStopsIt() {
        chosenCaller()
        val c = ringing()
        dialling("t2")
        assertEquals(0L, ui(c).autoAnswerAt)
        FakeTelecom.advance(wait * 2)
        assertFalse(sent("answerCall"))
    }

    @Test fun silencingTheRingerStopsIt() {
        chosenCaller()
        ringing()
        CallManager.onSystemSilence()
        FakeTelecom.advance(wait * 2)
        assertFalse(sent("answerCall"))
    }

    @Test fun anUnknownCallerIsNeverAnsweredOnItsOwn() {
        deps.autoAnswerConfig = CallExtrasConfig(autoAnswerChosen = true, autoAnswerSimple = true)
        deps.appearance.value = deps.appearance.value.copy(simpleMode = true)
        val c = ringing()
        assertEquals(0L, ui(c).autoAnswerAt)
        FakeTelecom.advance(wait * 2)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun aCallerFlaggedAsSpamIsNeverAnsweredOnItsOwn() {
        chosenCaller()
        deps.screen = ScreenOutcome(Decision.Allow, verdict = "Likely spam", warn = true)
        val c = ringing()
        assertEquals(0L, ui(c).autoAnswerAt)
        FakeTelecom.advance(wait * 2)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun simpleModeAnswersAKnownCaller() {
        deps.autoAnswerConfig = CallExtrasConfig(autoAnswerSimple = true)
        deps.appearance.value = deps.appearance.value.copy(simpleMode = true)
        contact("+15551234567")
        ringing()
        FakeTelecom.advance(wait + 100)
        assertEquals(listOf("answerCall(t1, 0)"), telecom.sent)
    }
}
