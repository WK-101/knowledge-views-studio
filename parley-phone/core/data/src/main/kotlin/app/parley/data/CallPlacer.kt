package app.parley.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import app.parley.common.PhoneIdentity
import app.parley.common.calls.DialCodes
import app.parley.common.calls.HideNumber
import app.parley.common.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PlaceResult {
    data object Placed : PlaceResult
    data object Handled : PlaceResult
    data class Failed(val reason: String) : PlaceResult
}

/**
 * Places calls through Telecom. Never uses our own ACTION_CALL handling so emergency numbers are
 * always routed by the platform.
 */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
class CallPlacer(private val context: Context, private val sims: SimRepository, private val prefs: PrefsRepository) {
    private val telecom = context.getSystemService(TelecomManager::class.java)

    /** The SIM a label asks for, used when the number has no remembered SIM. Blocking: only called on [Dispatchers.IO]. */
    @Volatile
    var fallbackSim: (suspend (String) -> String?)? = null

    /** The SIM for [number] without a choice of its own: the remembered one, else a label's. Off the main thread. */
    suspend fun resolveSim(number: String): String? = withContext(Dispatchers.IO) {
        prefs.simFor(number) ?: catching { fallbackSim?.invoke(number) }.getOrNull()
    }

    /**
     * Places a call to [rawNumber] on [accountId]. Without one, the remembered or label SIM is looked up (off the main
     * thread) unless [simResolved] says the caller already did that and found none. [subject] goes with the call
     * as `EXTRA_CALL_SUBJECT`; only offered when the SIM's account has `CAPABILITY_CALL_SUBJECT`. [clir] hides or shows
     * the caller ID for this one call ([HideNumber]): the right code for the chosen SIM's country is put in front of
     * the number as it is dialled, never stored. A service/USSD code is dialled as it is whatever [clir] asks.
     */
    suspend fun call(
        rawNumber: String, accountId: String? = null, simResolved: Boolean = false, subject: String? = null,
        clir: HideNumber.Clir = HideNumber.Clir.PLAIN,
    ): PlaceResult {
        val number = rawNumber.trim()
        if (number.isEmpty()) return PlaceResult.Failed("Empty number")
        if (handleSecretCode(number)) return PlaceResult.Handled
        val extras = Bundle()
        val chosen = accountId ?: if (simResolved) null else resolveSim(number)
        sims.handle(chosen)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        subject?.takeIf { it.isNotBlank() }?.let { extras.putString(TelecomManager.EXTRA_CALL_SUBJECT, it) }
        val dialled = withCallerId(number, chosen, clir)
        return try {
            telecom.placeCall(Uri.fromParts("tel", dialled, null), extras)
            PlaceResult.Placed
        } catch (e: SecurityException) {
            PlaceResult.Failed("Phone permission missing")
        } catch (e: Exception) {
            PlaceResult.Failed(e.message ?: "Could not place call")
        }
    }

    /**
     * [number] with the hide/show code for the chosen SIM's country in front, or [number] as it is ([HideNumber]). A
     * redial ("Call again showing my number") can arrive with the previous call's code still on the handle where the
     * platform kept it; [HideNumber.forOutgoing] takes it off first so the code we add is the one that goes out.
     */
    private fun withCallerId(number: String, accountId: String?, clir: HideNumber.Clir): String {
        if (clir == HideNumber.Clir.PLAIN) return number
        val country = catching { PhoneEnv.countryIso(context, accountId) }.getOrNull()
        return HideNumber.forOutgoing(number, country, clir, serviceCode = PhoneIdentity::isServiceCode)
    }

    fun callVoicemail(accountId: String? = null): PlaceResult = try {
        val extras = Bundle()
        sims.handle(accountId)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        telecom.placeCall(Uri.fromParts("voicemail", "", null), extras)
        PlaceResult.Placed
    } catch (e: Exception) {
        PlaceResult.Failed(e.message ?: "Voicemail unavailable")
    }

    /** *#*#1234#*#* style codes are broadcast to the owning app (allowed for the default dialer). */
    private fun handleSecretCode(number: String): Boolean {
        val code = DialCodes.secretCode(number) ?: return false
        return try {
            context.getSystemService(TelephonyManager::class.java).sendDialerSpecialCode(code)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isServiceCode(number: String) = PhoneIdentity.isServiceCode(number)
}
