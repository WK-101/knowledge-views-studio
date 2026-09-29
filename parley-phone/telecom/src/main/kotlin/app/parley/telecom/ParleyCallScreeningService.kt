package app.parley.telecom

import android.os.Build
import android.os.Trace
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.TelecomManager
import app.parley.common.BlockAction
import app.parley.common.BlockReason
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
                    when {
                        // A private contact's "Send to voicemail" is a plain decline, not a block. Any disallowed call is
                        // written to the call log by Telecom as blocked (by this service), and setSkipCallLog only drops
                        // it from the log. So when Parley is the phone app the call is let through silenced, and
                        // CallManager declines it at once from the remembered verdict: logged as declined, like the
                        // in-call path. With only the screening role nobody else would decline it: rejected here.
                        decision.reason == BlockReason.SEND_TO_VOICEMAIL && isPhoneApp() -> b.setSilenceCall(true)
                        decision.action == BlockAction.REJECT -> b.setDisallowCall(true).setRejectCall(true).setSkipNotification(true)
                        decision.action == BlockAction.SILENCE -> b.setSilenceCall(true)
                    }
                }
                b.build()
            }.getOrElse { CallResponse.Builder().build() }
            respond(details, response)
        }
    }

    private fun allow(details: Call.Details) = respond(details, CallResponse.Builder().build())

    /** Parley is the default phone app, so its in-call service gets every call this service lets through. */
    private fun isPhoneApp(): Boolean =
        runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName }.getOrDefault(false)

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
