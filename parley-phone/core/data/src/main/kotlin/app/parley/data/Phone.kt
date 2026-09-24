package app.parley.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

object PhoneEnv {
    /** Country used to interpret national numbers: SIM first, then network, then locale. */
    fun countryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val sim = tm?.simCountryIso?.takeIf { it.length == 2 }
        val net = tm?.networkCountryIso?.takeIf { it.length == 2 }
        return (sim ?: net ?: Locale.getDefault().country).uppercase(Locale.ROOT)
    }

    fun isEmergency(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        return try {
            context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == true
        } catch (_: Exception) {
            false
        }
    }
}
