package app.parley.telecom

import android.os.Trace
import app.parley.common.BlockAction
import app.parley.common.Decision
import app.parley.common.Verification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screens an incoming call from the in-call service: before any UI, bounded by a hard timeout so ringing is never
 * held up, and failing open (any failure lets the call ring). It records the outcome in the call's [CallSession]
 * and tells [Host] what to do with the call.
 */
internal class ScreeningCoordinator(private val scope: CoroutineScope, private val hooks: () -> ScreeningHooks) {
    /** What the call path does with a verdict. */
    interface Host {
        fun stillPresent(session: CallSession): Boolean
        fun rejectUnwanted(session: CallSession)
        fun silence(session: CallSession)
        fun ringLoud(session: CallSession)
        fun playTone(session: CallSession)
        fun changed()
    }

    /** What the screening service decided before this call reached the in-call service. */
    class Earlier(val outcome: ScreenOutcome?, val accountId: String?)

    /**
     * The screening service's earlier answer to reuse, or null when it must be screened again: it decided without
     * knowing the SIM, so an "allow" is re-checked when per-SIM rules exist (always when it only let the call through
     * because a SIM-limited allow rule might apply).
     */
    fun reusable(e: Earlier): ScreenOutcome? = e.outcome?.takeIf {
        !(it.decision == Decision.Allow && e.accountId != null && (it.deferredToSim || runCatching { hooks().simRulesActive() }.getOrDefault(true)))
    }

    /** Whether an incoming call goes through screening at all (never an emergency call; decided by the caller). */
    fun applies(e: Earlier, hidden: Boolean): Boolean =
        reusable(e) != null || e.outcome != null || hidden || runCatching { hooks().screeningActive() }.getOrDefault(true)

    fun start(session: CallSession, number: String?, hidden: Boolean, verification: Verification, callerName: String?, e: Earlier, host: Host) {
        // No notification of any kind until the verdict (bounded by the timeout): a call that is then blocked must
        // never have shown a name or an Answer button.
        session.screening = true
        val earlier = reusable(e)
        scope.launch {
            Trace.beginAsyncSection(TRACE_SCREEN, session.id.hashCode())
            val outcome = earlier ?: withTimeoutOrNull(SCREEN_TIMEOUT_MS) {
                runCatching { hooks().screenCall(number, hidden, verification, e.accountId, callerName) }.getOrNull()
            } ?: e.outcome
            Trace.endAsyncSection(TRACE_SCREEN, session.id.hashCode())
            val decision = outcome?.decision
            outcome?.let { session.outcome = it }
            session.screening = false
            if (decision is Decision.Block && host.stillPresent(session)) {
                when (decision.action) {
                    BlockAction.REJECT -> host.rejectUnwanted(session)
                    BlockAction.SILENCE -> host.silence(session)
                }
            } else {
                if (outcome?.ringLoud == true) host.ringLoud(session)
                // The caller lookup may have finished first and held the custom tone back until screening allowed it.
                if (session.unknownCaller || outcome?.ringtone != null) host.playTone(session)
            }
            host.changed()
        }
    }

    companion object {
        const val SCREEN_TIMEOUT_MS = 1500L
        private const val TRACE_SCREEN = "Parley.screenCall"
    }
}
