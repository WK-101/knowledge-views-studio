package app.parley.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import app.parley.common.BlockAction
import app.parley.common.Decision
import app.parley.common.Verification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screens calls before they ring (only active when the user grants the call-screening role).
 * Must respond within 5 seconds; we answer well within that and allow the call on timeout.
 */
class ParleyCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        val number = details.handle?.schemeSpecificPart
        val verification = if (Build.VERSION.SDK_INT < 30) Verification.NOT_VERIFIED else when (details.callerNumberVerificationStatus) {
            Connection.VERIFICATION_STATUS_PASSED -> Verification.PASSED
            Connection.VERIFICATION_STATUS_FAILED -> Verification.FAILED
            else -> Verification.NOT_VERIFIED
        }
        scope.launch {
            val decision = withTimeoutOrNull(3000) { TelecomGraph.dependencies.screen(number, number.isNullOrBlank(), verification) }
            val response = CallResponse.Builder()
            if (decision is Decision.Block) {
                when (decision.action) {
                    BlockAction.REJECT -> response.setDisallowCall(true).setRejectCall(true).setSkipNotification(true)
                    BlockAction.SILENCE -> if (Build.VERSION.SDK_INT >= 29) response.setSilenceCall(true)
                }
            }
            respondToCall(details, response.build())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
