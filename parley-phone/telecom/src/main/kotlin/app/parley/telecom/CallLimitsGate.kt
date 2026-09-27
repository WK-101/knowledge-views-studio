package app.parley.telecom

import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.EmergencyPolicy.Safeguard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Call time on the call path: an incoming call whose allowance is used up rings silently when the user asked for
 * that, and a call whose limit ran out is ended. Emergency calls and calls in the emergency window are never
 * touched ([EmergencyPolicy]).
 */
internal class CallLimitsGate(private val scope: CoroutineScope, private val hooks: () -> CallPolicyHooks) {
    /**
     * Asks, bounded by [ScreeningCoordinator.SCREEN_TIMEOUT_MS], whether [number]'s allowance is used up; if so and
     * the call still rings, [silence] runs.
     */
    fun checkAllowance(
        session: CallSession,
        number: String,
        accountId: String?,
        emergency: EmergencyPolicy.Facts,
        stillRinging: () -> Boolean,
        silence: () -> Unit,
    ) {
        if (EmergencyPolicy.bypasses(Safeguard.CALL_TIME_ALLOWANCE, emergency)) return
        scope.launch {
            val over = withTimeoutOrNull(ScreeningCoordinator.SCREEN_TIMEOUT_MS) {
                runCatching { hooks().silenceOverQuota(number, accountId) }.getOrDefault(false)
            } == true
            if (over && stillRinging() && !session.silenced) {
                session.silenced = true
                session.quotaSilenced = true
                silence()
            }
        }
    }

    /**
     * Whether a call whose limit ran out may be ended now: never while it rings or is already ending, never an
     * emergency call, nor during the emergency window (this may be the operator calling back).
     */
    fun mayEnd(state: CallState, emergency: EmergencyPolicy.Facts): Boolean =
        state != CallState.RINGING && state != CallState.DISCONNECTED && state != CallState.DISCONNECTING &&
            !EmergencyPolicy.bypasses(Safeguard.CALL_LIMITS, emergency)
}
