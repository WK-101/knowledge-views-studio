package app.parley.telecom

import android.telecom.Call
import android.telecom.TelecomManager
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calls.LockScreenCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emergency calls and call-backs bypass everything that could stop, limit or hide them. */
internal class EmergencyCallTest : CallPathTest() {
    private val reject = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE), verdict = "Rule")

    @Test fun anOrdinaryCallIsScreened() {
        deps.screen = reject
        ringing()
        assertEquals(1, deps.screened)
        assertEquals(listOf("rejectCallWithReason(t1, ${Call.REJECT_REASON_UNWANTED})"), telecom.sent)
    }

    @Test fun anEmergencyNumberIsNeverScreened() {
        deps.screen = reject
        val c = ringing("t1", "112")
        assertEquals(0, deps.screened)
        assertTrue(telecom.sent.isEmpty())
        assertTrue(ui(c).isEmergency)
        assertFalse(CallManager.isScreening(idOf(c)))
    }

    @Test fun aCallTheNetworkMarksAsAnEmergencyIsNeverScreened() {
        deps.screen = reject
        val c = add(FakeTelecom.Spec("t1", Call.STATE_RINGING, properties = Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE))
        assertEquals(0, deps.screened)
        assertTrue(telecom.sent.isEmpty())
        assertTrue(ui(c).isEmergency)
    }

    @Test fun afterAnEmergencyCallItsCallBackGetsThroughEvenHidden() {
        deps.screen = reject
        dialling("t1", "112")
        assertTrue(ScreeningGuard.inEmergencyWindow(context))
        end("t1")
        add(FakeTelecom.Spec("t2", Call.STATE_RINGING, presentation = TelecomManager.PRESENTATION_RESTRICTED))
        assertEquals(0, deps.screened)
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun aLimitNeverEndsAnEmergencyCall() {
        val e = active("t1", number = "112")
        CallManager.endForLimit(idOf(e))
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun aLimitEndsAnOrdinaryCallAndSaysWhy() {
        val c = active("t1")
        CallManager.endForLimit(idOf(c))
        assertEquals(listOf("disconnectCall(t1)"), telecom.sent)
        telecom.update("t1") { it.copy(state = Call.STATE_DISCONNECTING) }
        assertEquals(str(R.string.call_limit_reached), ui(c).disconnectReason)
        // Not a drop: the call-ended screen offers no "Call again" for it.
        end("t1", android.telecom.DisconnectCause.ERROR)
        assertEquals(null, ended().drop)
    }

    @Test fun aUsedUpAllowanceNeverSilencesAnEmergencyCallBack() {
        deps.overQuota = true
        val o = ringing("t1")
        assertTrue("an ordinary call rings silently", ui(o).silenced)
        val e = ringing("t2", "112")
        assertFalse(ui(e).silenced)
    }

    @Test fun anEmergencyCallIsNeverAnsweredOnItsOwn() {
        deps.autoAnswerConfig = CallExtrasConfig(autoAnswerChosen = true)
        contact("112", "Emergency services", chosen = true)
        val c = ringing("t1", "112")
        assertEquals(0L, ui(c).autoAnswerAt)
        FakeTelecom.advance(10_000)
        assertFalse(sent("answerCall"))
    }

    @Test fun anEmergencyCallShowsWhoItIsOnTheLockScreen() {
        contact("112", "Emergency services")
        val c = ringing("t1", "112")
        val u = ui(c)
        assertSame(u, u.forLockScreen(LockScreenCaller.NONE, "Incoming call"))
    }

    @Test fun anEmergencyCallHasNoHoldModeAndNoCountedUsage() {
        val e = active("t1", number = "112")
        assertFalse(ui(e).canHoldMode)
        var asked = 0
        CallManager.routeRequests = { asked++ }
        val earpiece = AudioRoute("e", RouteType.EARPIECE, "")
        CallManager.updateAudio(AudioUi(listOf(earpiece, AudioRoute("s", RouteType.SPEAKER, "")), earpiece))
        CallManager.startHoldMode(idOf(e))
        assertEquals(0L, ui(e).holdModeSince)
        assertEquals(0, asked)
        end("t1")
        assertTrue("never counted towards an allowance", deps.usage.isEmpty())
        assertTrue("no quality record of an emergency call", deps.quality.isEmpty())
    }

    @Test fun anOrdinaryConnectedCallIsCounted() {
        active("t1")
        end("t1", android.telecom.DisconnectCause.REMOTE)
        assertEquals(listOf("+15550000001"), deps.usage)
        assertEquals(1, deps.quality.size)
    }

    @Test fun anEmergencyCallIsNeverOfferedCallAgainOrCheckItsReallyThem() {
        val e = active("t1", number = "112")
        assertFalse(ui(e).canVerify)
        end("t1", android.telecom.DisconnectCause.ERROR, "LOST_SIGNAL")
        assertEquals(null, ended().drop)
        assertFalse(ended().canCallAgain)
    }
}
