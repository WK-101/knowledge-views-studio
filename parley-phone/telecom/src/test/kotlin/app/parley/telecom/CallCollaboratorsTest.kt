package app.parley.telecom

import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.Verification
import app.parley.common.calls.EmergencyPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The call path's collaborators, driven through a [CallSession] (no Telecom `Call` needed): screening's verdicts,
 * timeout and notice hold, the allowance check and the limit gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallCollaboratorsTest {
    private val scope = TestScope(StandardTestDispatcher())

    private class Hooks(
        var outcome: ScreenOutcome? = ScreenOutcome(Decision.Allow),
        var delayMs: Long = 0,
        var active: Boolean = true,
        var simRules: Boolean = false,
        var overQuota: Boolean = false,
    ) : ScreeningHooks, CallPolicyHooks {
        var screened = 0
        override fun screeningActive() = active
        override suspend fun screenCall(number: String?, hidden: Boolean, verification: Verification, accountId: String?, callerName: String?): ScreenOutcome {
            screened++
            delay(delayMs)
            return outcome ?: error("screening failed")
        }
        override fun simRulesActive() = simRules
        override suspend fun preferredAccountId(number: String): String? = null
        override suspend fun silenceOverQuota(number: String, accountId: String?) = overQuota
    }

    private class Host(var present: Boolean = true) : ScreeningCoordinator.Host {
        val events = ArrayList<String>()
        override fun stillPresent(session: CallSession) = present
        override fun rejectUnwanted(session: CallSession) {
            events += "reject"
        }
        override fun silence(session: CallSession) {
            session.silenced = true
            events += "silence"
        }
        override fun ringLoud(session: CallSession) {
            events += "loud"
        }
        override fun playTone(session: CallSession) {
            events += "tone"
        }
        override fun changed() {
            events += "changed"
        }
    }

    private val none = ScreeningCoordinator.Earlier(null, null)

    private fun start(hooks: Hooks, host: Host, session: CallSession = CallSession("c1"), earlier: ScreeningCoordinator.Earlier = none): CallSession {
        ScreeningCoordinator(scope) { hooks }.start(session, "+15551234567", hidden = false, Verification.NOT_VERIFIED, null, earlier, host)
        return session
    }

    @Test fun aBlockVerdictRejectsOrSilencesAndIsRemembered() {
        val host = Host()
        val s = start(Hooks(ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE), verdict = "Rule")), host)
        assertTrue(s.screening)
        scope.advanceUntilIdle()
        assertFalse(s.screening)
        assertEquals("Rule", s.outcome?.verdict)
        assertTrue("reject" in host.events)

        val quiet = Host()
        val q = start(Hooks(ScreenOutcome(Decision.Block(BlockAction.SILENCE, BlockReason.RULE))), quiet, CallSession("c2"))
        scope.advanceUntilIdle()
        assertTrue(q.silenced)
        assertTrue("silence" in quiet.events)
    }

    @Test fun anAllowedCallCanRingLoudOrWithItsOwnTone() {
        val host = Host()
        start(Hooks(ScreenOutcome(Decision.Allow, ringLoud = true, ringtone = "content://tone")), host)
        scope.advanceUntilIdle()
        assertEquals(listOf("loud", "tone", "changed"), host.events)
    }

    @Test fun slowOrFailingScreeningLetsTheCallRing() {
        val host = Host()
        val s = start(Hooks(ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE)), delayMs = 10_000), host)
        scope.advanceTimeBy(ScreeningCoordinator.SCREEN_TIMEOUT_MS + 1)
        scope.runCurrent()
        assertFalse(s.screening)
        assertNull(s.outcome)
        assertFalse("reject" in host.events)

        val failing = Host()
        val f = start(Hooks(outcome = null), failing, CallSession("c2"))
        scope.advanceUntilIdle()
        assertFalse(f.screening)
        assertFalse("reject" in failing.events)
    }

    @Test fun theNotificationWaitsBrieflyForTheVerdict() {
        val host = Host()
        val s = start(Hooks(delayMs = 1_000), host)
        assertTrue(s.noticeHeld)
        scope.advanceTimeBy(ScreeningCoordinator.NOTICE_HOLD_MS + 1)
        scope.runCurrent()
        assertFalse("after the hold a quiet notification shows while screening goes on", s.noticeHeld)
        assertTrue(s.screening)
    }

    @Test fun theScreeningServicesAnswerIsReusedUnlessTheSimMatters() {
        val hooks = Hooks()
        val block = ScreenOutcome(Decision.Block(BlockAction.REJECT, BlockReason.RULE))
        val c = ScreeningCoordinator(scope) { hooks }
        assertEquals(block, c.reusable(ScreeningCoordinator.Earlier(block, "sim1")))
        val allow = ScreenOutcome(Decision.Allow)
        assertEquals(allow, c.reusable(ScreeningCoordinator.Earlier(allow, null)))
        assertEquals(allow, c.reusable(ScreeningCoordinator.Earlier(allow, "sim1")))
        hooks.simRules = true
        assertNull("an allow decided without the SIM is checked again", c.reusable(ScreeningCoordinator.Earlier(allow, "sim1")))
        assertNull(c.reusable(ScreeningCoordinator.Earlier(ScreenOutcome(Decision.Allow, deferredToSim = true), "sim1").also { hooks.simRules = false }))

        hooks.active = false
        assertFalse(c.applies(none, hidden = false))
        assertTrue("hidden callers are always screened", c.applies(none, hidden = true))

        val host = Host()
        start(hooks, host, earlier = ScreeningCoordinator.Earlier(block, null))
        scope.advanceUntilIdle()
        assertEquals("the earlier answer is used without asking again", 0, hooks.screened)
        assertTrue("reject" in host.events)
    }

    private val ordinary = EmergencyPolicy.Facts(emergencyNumber = false, emergencyCallProperty = false, inWindow = false, userListed = false)
    private val emergency = ordinary.copy(emergencyNumber = true)

    @Test fun anAllowanceUsedUpSilencesARingingCallButNeverAnEmergencyOne() {
        val hooks = Hooks(overQuota = true)
        val gate = CallLimitsGate(scope) { hooks }
        val s = CallSession("c1")
        var silencedNow = 0
        gate.checkAllowance(s, "+15551234567", null, ordinary, stillRinging = { true }) { silencedNow++ }
        scope.advanceUntilIdle()
        assertTrue(s.silenced && s.quotaSilenced)
        assertEquals(1, silencedNow)

        val e = CallSession("c2")
        gate.checkAllowance(e, "112", null, emergency, stillRinging = { true }) { silencedNow++ }
        scope.advanceUntilIdle()
        assertFalse(e.silenced)

        val answered = CallSession("c3")
        gate.checkAllowance(answered, "+15551234567", null, ordinary, stillRinging = { false }) { silencedNow++ }
        scope.advanceUntilIdle()
        assertFalse(answered.silenced)
        assertEquals(1, silencedNow)
    }

    @Test fun aLimitEndsOnlyALiveNonEmergencyCall() {
        val gate = CallLimitsGate(scope) { Hooks() }
        assertTrue(gate.mayEnd(CallState.ACTIVE, ordinary))
        assertTrue(gate.mayEnd(CallState.HOLDING, ordinary))
        assertFalse(gate.mayEnd(CallState.RINGING, ordinary))
        assertFalse(gate.mayEnd(CallState.DISCONNECTING, ordinary))
        assertFalse(gate.mayEnd(CallState.ACTIVE, emergency))
        assertFalse(gate.mayEnd(CallState.ACTIVE, ordinary.copy(inWindow = true)))
    }

    @Test fun theRingerLetsGoOfABoostOnceItsCallStopsRinging() {
        val ringer = CallRinger(scope) {}
        ringer.boost(RuntimeEnvironment.getApplication(), "c1")
        ringer.follow(null) { true }
        assertEquals("c1", ringer.boostedFor)
        ringer.follow(null) { false }
        assertNull(ringer.boostedFor)
    }
}
