package app.parley.telecom

import android.telecom.Call
import app.parley.common.calls.KeyPressTracker
import app.parley.common.calls.MenuStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keys pressed during a call: their tones, roll-over, post-dial waits and menu memory. */
internal class DtmfTest : CallPathTest() {
    @Test fun aKeyPressPlaysAShortTone() {
        val c = active("t1")
        CallManager.playDtmf(idOf(c), '5')
        assertEquals(listOf("playDtmfTone(t1, 5)"), telecom.sent)
        FakeTelecom.advance(KeyPressTracker.MIN_TONE_MS)
        assertEquals(listOf("playDtmfTone(t1, 5)", "stopDtmfTone(t1)"), telecom.sent)
    }

    @Test fun aHeldKeyKeepsItsToneUntilReleased() {
        val c = active("t1")
        val token = CallManager.startDtmf(idOf(c), '0')!!
        FakeTelecom.advance(2_000)
        assertEquals(listOf("playDtmfTone(t1, 0)"), telecom.sent)
        CallManager.stopDtmf(idOf(c), token)
        FakeTelecom.idle()
        assertEquals("stopDtmfTone(t1)", telecom.sent.last())
    }

    @Test fun aSecondKeyStopsTheFirstToneAndOutlivesItsRelease() {
        val c = active("t1")
        val first = CallManager.startDtmf(idOf(c), '1')!!
        CallManager.startDtmf(idOf(c), '2')
        assertEquals(listOf("playDtmfTone(t1, 1)", "stopDtmfTone(t1)", "playDtmfTone(t1, 2)"), telecom.sent)
        // Releasing the first key must not cut the second key's tone.
        CallManager.stopDtmf(idOf(c), first)
        FakeTelecom.idle()
        assertEquals(3, telecom.sent.size)
    }

    @Test fun keysForAGoneCallDoNothing() {
        assertNull(CallManager.startDtmf("nope", '1'))
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun aPostDialWaitCanBeContinued() {
        val c = active("t1")
        CallManager.postDialContinue(idOf(c), true)
        assertEquals(listOf("postDialContinue(t1, true)"), telecom.sent)
        assertNull(ui(c).postDialWait)
    }

    @Test fun digitsSentToAPhoneMenuAreRememberedWhenTheCallEnds() {
        val c = active("t1", number = "+18005550100")
        CallManager.playDtmf(idOf(c), '2')
        CallManager.playDtmf(idOf(c), '1')
        end("t1")
        assertEquals(listOf('2', '1'), deps.menuKeys.single().map { it.tone })
    }

    @Test fun digitsInAnIncomingCallAreNotRemembered() {
        val c = add(FakeTelecom.Spec("t1", Call.STATE_ACTIVE, "+18005550100", incoming = true, connectTimeMillis = System.currentTimeMillis()))
        CallManager.playDtmf(idOf(c), '2')
        end("t1")
        assertTrue(deps.menuKeys.isEmpty())
    }

    @Test fun digitsInAnEmergencyCallAreNeverRemembered() {
        val c = active("t1", number = "112")
        CallManager.playDtmf(idOf(c), '2')
        end("t1")
        assertTrue(deps.menuKeys.isEmpty())
    }

    @Test fun aRememberedMenuIsReplayedButNeverInAnEmergencyCall() {
        val e = active("t1", number = "112")
        CallManager.replayMenu(idOf(e), listOf(MenuStep('1', 0)))
        assertNull(CallManager.menuReplay.value)
        end("t1")
        val c = active("t2", number = "+18005550100")
        CallManager.replayMenu(idOf(c), listOf(MenuStep('3', 0)))
        FakeTelecom.advance(2_000)
        assertTrue(sent("playDtmfTone(t2, 3)"))
    }

    @Test fun aReplayStopsWhenTheCallIsHeld() {
        val c = active("t1", number = "+18005550100")
        CallManager.replayMenu(idOf(c), listOf(MenuStep('1', 5_000), MenuStep('2', 5_000)))
        telecom.update("t1") { it.copy(state = Call.STATE_HOLDING) }
        FakeTelecom.advance(20_000)
        assertNull(CallManager.menuReplay.value)
        assertTrue(telecom.sent.none { it.startsWith("playDtmfTone") })
    }
}
