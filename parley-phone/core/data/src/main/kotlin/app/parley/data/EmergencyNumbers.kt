package app.parley.data

import android.content.Context
import android.telephony.TelephonyManager
import app.parley.common.calls.EmergencyPolicy

/**
 * The platform side of [EmergencyPolicy]: is this an emergency number here, with this SIM and network? Uses
 * `TelephonyManager.isEmergencyNumber` (which knows the SIM, network and country lists) and falls back to the numbers
 * every handset must accept when telephony can't answer (no telephony service, an OEM exception).
 */
object EmergencyNumbers {
    fun isEmergency(context: Context, raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        // The platform reads ASCII digits only; "١١٢" typed on a native keyboard is still 112.
        val number = EmergencyPolicy.asciiDigits(raw)
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return EmergencyPolicy.isFallbackEmergencyNumber(number)
            tm.isEmergencyNumber(number)
        } catch (_: Exception) {
            EmergencyPolicy.isFallbackEmergencyNumber(number)
        }
    }

    /** Facts for [EmergencyPolicy.bypasses] about an outgoing number known only by the platform check. */
    fun facts(context: Context, number: String?): EmergencyPolicy.Facts = EmergencyPolicy.Facts(emergencyNumber = isEmergency(context, number))
}
