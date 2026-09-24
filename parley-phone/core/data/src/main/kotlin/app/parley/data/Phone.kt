package app.parley.data

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object PhoneEnv {
    /** Country used to interpret national numbers: SIM first, then network, then locale. */
    fun countryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val sim = tm?.simCountryIso?.takeIf { it.length == 2 }
        val net = tm?.networkCountryIso?.takeIf { it.length == 2 }
        return (sim ?: net ?: Locale.getDefault().country).uppercase(Locale.ROOT)
    }

    /**
     * F7/F19: the country of the SIM that handled a call ([accountId] is its PhoneAccountHandle id, as stored in the
     * call log), falling back to [countryIso] when it is unknown (one SIM, a SIP account, no permission).
     */
    fun countryIso(context: Context, accountId: String?): String = simCountry(context, accountId) ?: countryIso(context)

    private val simCountries = ConcurrentHashMap<String, String>()
    private val misses = ConcurrentHashMap<String, Long>()
    private const val MISS_TTL_MS = 60_000L

    /** The SIM's country for [accountId], or null. Cached per account for the life of the process. */
    @SuppressLint("MissingPermission")
    fun simCountry(context: Context, accountId: String?): String? {
        if (accountId.isNullOrBlank()) return null
        simCountries[accountId]?.let { return it }
        misses[accountId]?.let { at -> if (android.os.SystemClock.elapsedRealtime() - at < MISS_TTL_MS) return null }
        val found = try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val subs = sm?.activeSubscriptionInfoList.orEmpty()
            val subId = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val tm = context.getSystemService(TelephonyManager::class.java)
                context.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts
                    ?.firstOrNull { it.id == accountId }?.let { h -> runCatching { tm?.getSubscriptionId(h) }.getOrNull() }
            } else {
                null
            }
            @Suppress("DEPRECATION")
            val info = subs.firstOrNull { subId != null && it.subscriptionId == subId }
                // Android 10 and some OEMs: the handle id is the ICCID or the subscription id itself.
                ?: subs.firstOrNull { it.iccId == accountId || it.subscriptionId.toString() == accountId }
            info?.countryIso?.takeIf { it.length == 2 }?.uppercase(Locale.ROOT)
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        // Unknown accounts aren't cached as "none" for long: a SIM may be read once permissions arrive.
        if (found != null) simCountries[accountId] = found else misses[accountId] = android.os.SystemClock.elapsedRealtime()
        return found
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
