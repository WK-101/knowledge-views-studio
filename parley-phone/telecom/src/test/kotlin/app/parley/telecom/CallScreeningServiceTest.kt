package app.parley.telecom

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.Decision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCallScreeningService

/**
 * [ParleyCallScreeningService]: every call gets exactly one answer, an emergency call is never screened, and any
 * doubt lets the call ring.
 */
@RunWith(RobolectricTestRunner::class)
internal class CallScreeningServiceTest {
    private val telecom = FakeTelecom()
    private val deps = FakeDependencies()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: ParleyCallScreeningService

    @Before fun setUp() {
        TelecomGraph.install(deps)
        service = Robolectric.setupService(ParleyCallScreeningService::class.java)
    }

    @After fun tearDown() {
        ScreeningGuard.forgetDecisions()
    }

    private fun screen(spec: FakeTelecom.Spec = FakeTelecom.Spec("t1", Call.STATE_RINGING)): CallScreeningService.CallResponse {
        service.onScreenCall(telecom.call(spec).details)
        FakeTelecom.idle()
        val shadow = Shadow.extract<ShadowCallScreeningService>(service)
        return shadow.lastRespondToCallInput.orElseThrow { AssertionError("no answer") }.callResponse
    }

    private fun CallScreeningService.CallResponse.allows() = !disallowCall && !rejectCall && !silenceCall

    @Test fun aBlockRuleRejectsWithoutANotification() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        val r = screen()
        assertTrue(r.disallowCall && r.rejectCall && r.skipNotification)
        assertFalse(r.silenceCall)
    }

    @Test fun aSilenceRuleLetsItRingQuietly() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.SILENCE, BlockReason.RULE))
        val r = screen()
        assertTrue(r.silenceCall)
        assertFalse(r.disallowCall)
    }

    @Test fun anAllowedCallRings() {
        deps.screen = ScreenOutcome(Decision.Allow)
        assertTrue(screen().allows())
    }

    @Test fun anEmergencyNumberIsLetThroughWithoutAsking() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        assertTrue(screen(FakeTelecom.Spec("t1", Call.STATE_RINGING, "911")).allows())
        assertEquals(0, deps.screened)
    }

    @Test fun aCallInEmergencyCallbackModeIsLetThrough() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        val r = screen(FakeTelecom.Spec("t1", Call.STATE_RINGING, properties = Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE))
        assertTrue(r.allows())
        assertEquals(0, deps.screened)
    }

    @Test fun duringTheEmergencyWindowEveryCallIsLetThrough() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        ScreeningGuard.noteEmergencyCall(context)
        val r = screen(FakeTelecom.Spec("t1", Call.STATE_RINGING, presentation = TelecomManager.PRESENTATION_RESTRICTED))
        assertTrue(r.allows())
        assertEquals(0, deps.screened)
    }

    @Test fun outgoingCallsAreLetThroughWithoutAsking() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        assertTrue(screen(FakeTelecom.Spec("t1", Call.STATE_DIALING, incoming = false)).allows())
        assertEquals(0, deps.screened)
    }

    @Test fun anAllowThatDependsOnTheSimIsLeftToTheCallScreen() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE), deferredToSim = true)
        assertTrue(screen().allows())
        assertTrue(ScreeningGuard.recallOutcome("+15551234567")!!.deferredToSim)
    }

    @Test fun sendToVoicemailIsRejectedWhenParleyOnlyScreens() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.SEND_TO_VOICEMAIL))
        val r = screen()
        assertTrue(r.disallowCall && r.rejectCall)
    }

    @Test fun sendToVoicemailIsSilencedForParleysOwnCallScreenToDecline() {
        shadowOf(context.getSystemService(TelecomManager::class.java)).setDefaultDialerPackage(context.packageName)
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.SEND_TO_VOICEMAIL))
        val r = screen()
        assertTrue(r.silenceCall)
        assertFalse(r.disallowCall)
    }

    @Test fun aFailureLetsTheCallRing() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        deps.screenFails = true
        assertTrue(screen().allows())
    }

    @Test fun aSlowAnswerLetsTheCallRingWithinTheLimit() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        deps.screenDelayMs = 10_000
        service.onScreenCall(telecom.call(FakeTelecom.Spec("t1", Call.STATE_RINGING)).details)
        FakeTelecom.advance(3_100)
        val r = Shadow.extract<ShadowCallScreeningService>(service).lastRespondToCallInput.get().callResponse
        assertTrue(r.allows())
        assertTrue(ScreeningGuard.recall("+15551234567") is Decision.Allow)
    }

    @Test fun theVerdictIsKeptForTheCallScreen() {
        deps.screen = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        screen()
        assertTrue(ScreeningGuard.recall("+15551234567") is Decision.Block)
        // The same line written another way is the same caller.
        assertTrue(ScreeningGuard.recall("+1 555 123 4567") is Decision.Block)
    }
}
