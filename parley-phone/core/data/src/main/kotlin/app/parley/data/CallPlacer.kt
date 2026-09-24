package app.parley.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import app.parley.common.PhoneNumbers

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

    suspend fun call(rawNumber: String, accountId: String? = null): PlaceResult {
        val number = rawNumber.trim()
        if (number.isEmpty()) return PlaceResult.Failed("Empty number")
        if (handleSecretCode(number)) return PlaceResult.Handled
        val extras = Bundle()
        val chosen = accountId ?: prefs.simFor(number)
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
        val m = Regex("^\\*#\\*#([0-9]+)#\\*#\\*$").find(number) ?: return false
        return try {
            context.getSystemService(TelephonyManager::class.java).sendDialerSpecialCode(m.groupValues[1])
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isServiceCode(number: String) = PhoneNumbers.isServiceCode(number)
}
