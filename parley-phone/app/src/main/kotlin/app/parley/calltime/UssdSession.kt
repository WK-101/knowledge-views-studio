package app.parley.calltime

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import app.parley.common.SimAccount
import app.parley.common.calltime.Ussd
import app.parley.common.calltime.UssdEntry
import app.parley.data.DataContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UssdState {
    val code: String

    data class ChooseSim(override val code: String, val sims: List<SimAccount>) : UssdState
    data class Sending(override val code: String, val simLabel: String?) : UssdState
    data class Reply(override val code: String, val text: String, val ok: Boolean, val simLabel: String?, val simId: String?) : UssdState
}

/**
 * USSD codes typed on the keypad (A13): sent with [TelephonyManager.sendUssdRequest] on the chosen SIM, the
 * carrier's reply shown in a dialog and kept in a local history (nothing leaves the phone).
 */
// Telephony calls here are covered by the default-dialer role (CALL_PHONE) and each one handles SecurityException.
@SuppressLint("MissingPermission")
class UssdSession(private val c: DataContainer, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UssdState?>(null)
    val state: StateFlow<UssdState?> = _state.asStateFlow()
    private var timeout: Job? = null

    /** Starts with the given SIM, the default one, or asks when there are two SIMs and no default. */
    fun start(code: String, sims: List<SimAccount>, simId: String?) {
        scope.launch {
            val chosen = simId ?: c.sims.defaultOutgoing()
            if (chosen == null && sims.size >= 2) _state.value = UssdState.ChooseSim(code, sims) else send(code, chosen, sims)
        }
    }

    fun send(code: String, simId: String?, sims: List<SimAccount>) {
        val simLabel = if (sims.size >= 2) sims.firstOrNull { it.id == simId }?.label else null
        _state.value = UssdState.Sending(code, simLabel)
        val base = c.appContext.getSystemService(TelephonyManager::class.java)
        val tm = c.sims.handle(simId)?.let { h -> runCatching { base?.createForPhoneAccountHandle(h) }.getOrNull() } ?: base
        if (tm == null) {
            finish(code, "This phone can't send USSD codes.", false, simLabel, simId)
            return
        }
        timeout?.cancel()
        timeout = scope.launch {
            delay(TIMEOUT_MS)
            if (_state.value is UssdState.Sending && _state.value?.code == code) finish(code, "No reply from your carrier.", false, simLabel, simId)
        }
        try {
            tm.sendUssdRequest(
                code,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                        finish(code, Ussd.tidy(response).ifEmpty { "(Empty reply)" }, true, simLabel, simId)
                    }

                    override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                        val why = when (failureCode) {
                            TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "USSD isn't available right now (no network, or the SIM is busy)."
                            else -> "Your carrier didn't accept this code."
                        }
                        finish(code, why, false, simLabel, simId)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: SecurityException) {
            finish(code, "Phone permission missing.", false, simLabel, simId)
        } catch (e: Exception) {
            finish(code, e.message ?: "Couldn't send the code.", false, simLabel, simId)
        }
    }

    private fun finish(code: String, text: String, ok: Boolean, simLabel: String?, simId: String?) {
        timeout?.cancel()
        // A late reply after the dialog was closed is still saved to the history, but doesn't pop up again.
        if (ok) c.calling.addUssd(UssdEntry(code, text, System.currentTimeMillis(), true, simLabel))
        val cur = _state.value
        if (cur != null && cur.code == code) _state.value = UssdState.Reply(code, text, ok, simLabel, simId)
    }

    fun dismiss() {
        timeout?.cancel()
        _state.value = null
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
