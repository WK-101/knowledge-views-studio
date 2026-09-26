package app.parley.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import app.parley.common.PhoneNumbers
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

    /** X3: the SIM a label asks for, used when the number has no remembered SIM. Blocking: only called on [Dispatchers.IO]. */
    @Volatile
    var fallbackSim: ((String) -> String?)? = null

    /** The SIM for [number] without a choice of its own: the remembered one, else a label's (X3). Off the main thread. */
    suspend fun resolveSim(number: String): String? = withContext(Dispatchers.IO) {
        prefs.simFor(number) ?: runCatching { fallbackSim?.invoke(number) }.getOrNull()
    }

    /**
     * Places a call to [rawNumber] on [accountId]. Without one, the remembered or label SIM is looked up (off the main
     * thread) unless [simResolved] says the caller already did that and found none.
     */
    suspend fun call(rawNumber: String, accountId: String? = null, simResolved: Boolean = false): PlaceResult {
        val number = rawNumber.trim()
        if (number.isEmpty()) return PlaceResult.Failed("Empty number")
        if (handleSecretCode(number)) return PlaceResult.Handled
        val extras = Bundle()
        val chosen = accountId ?: if (simResolved) null else resolveSim(number)
        sims.handle(chosen)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        return try {
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            PlaceResult.Placed
        } catch (e: SecurityException) {
            PlaceResult.Failed("Phone permission missing")
        } catch (e: Exception) {
            PlaceResult.Failed(e.message ?: "Could not place call")
        }
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
        val code = app.parley.common.calls.DialCodes.secretCode(number) ?: return false
        return try {
            context.getSystemService(TelephonyManager::class.java).sendDialerSpecialCode(code)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isServiceCode(number: String) = PhoneNumbers.isServiceCode(number)
}
