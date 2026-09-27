package app.parley.telecom

import android.os.Build
import android.os.Trace
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import app.parley.common.BlockAction
import app.parley.common.Decision
import app.parley.common.Verification
import app.parley.common.calls.EmergencyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screens calls before they ring (only active when the user grants the call-screening role).
 * Must respond within 5 seconds; we answer well within that and allow the call on timeout or on any error:
 * every call gets exactly one response.
 */
class ParleyCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onScreenCall(details: Call.Details) {
        // onScreenCall → respondToCall, for Perfetto / systrace (ended in [respond]).
        Trace.beginAsyncSection(TRACE_RESPOND, System.identityHashCode(details))
        val incoming = runCatching { details.callDirection == Call.Details.DIRECTION_INCOMING }.getOrDefault(true)
        val number = runCatching { details.handle?.schemeSpecificPart }.getOrNull()
        // Any doubt about an emergency lets the call through.
        val emergency = EmergencyPolicy.Facts(
            emergencyNumber = !number.isNullOrBlank() && runCatching { TelecomGraph.dependencies.isEmergencyNumber(number) }.getOrDefault(false),
            emergencyCallProperty = runCatching {
                details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) || details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
            }.getOrDefault(false),
            inWindow = runCatching { ScreeningGuard.inEmergencyWindow(this) }.getOrDefault(true),
        )
        if (!incoming || EmergencyPolicy.bypasses(EmergencyPolicy.Safeguard.SCREENING, emergency)) {
            allow(details)
            return
        }
        val verification = runCatching {
            if (Build.VERSION.SDK_INT < 30) Verification.NOT_VERIFIED else when (details.callerNumberVerificationStatus) {
                Connection.VERIFICATION_STATUS_PASSED -> Verification.PASSED
                Connection.VERIFICATION_STATUS_FAILED -> Verification.FAILED
                else -> Verification.NOT_VERIFIED
            }
        }.getOrDefault(Verification.NOT_VERIFIED)
        scope.launch {
            val response = runCatching {
                // No SIM here: Android never gives the screening service the phone account. A decision that depends on
                // a SIM-limited allow rule comes back as an allow marked "deferred", and CallManager screens again.
                val callerName = details.callerDisplayName?.takeIf { it.isNotBlank() }
                val outcome = withTimeoutOrNull(3000) {
                    runCatching { TelecomGraph.dependencies.screenCall(number, number.isNullOrBlank(), verification, null, callerName) }.getOrNull()
                }
                ScreeningGuard.remember(number, outcome ?: ScreenOutcome(Decision.Allow))
                val decision = outcome?.decision
                val b = CallResponse.Builder()
                if (decision is Decision.Block && outcome?.deferredToSim != true) {
                    when (decision.action) {
                        BlockAction.REJECT -> b.setDisallowCall(true).setRejectCall(true).setSkipNotification(true)
                        BlockAction.SILENCE -> if (Build.VERSION.SDK_INT >= 29) b.setSilenceCall(true)
                    }
                }
                b.build()
            }.getOrElse { CallResponse.Builder().build() }
            respond(details, response)
        }
    }

    private fun allow(details: Call.Details) = respond(details, CallResponse.Builder().build())

    private fun respond(details: Call.Details, response: CallResponse) {
        runCatching { respondToCall(details, response) }
        Trace.endAsyncSection(TRACE_RESPOND, System.identityHashCode(details))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TRACE_RESPOND = "Parley.screenToRespond"
    }
}
