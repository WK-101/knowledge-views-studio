package app.parley.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.parley.common.RegionPick
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object PhoneEnv {
    /**
     * Country used to interpret national numbers: SIM first, then network, then the system locales (not Parley's
     * per-app language, which may carry no country), then the default locale.
     */
    fun countryIso(context: Context): String {
        // Two TelephonyManager calls and the locale lists: cached briefly, since callers evaluate it in loops and
        // per row. A SIM swap or roaming change is picked up within [ISO_TTL_MS].
        val now = SystemClock.elapsedRealtime()
        isoCache?.let { (at, iso) -> if (now - at in 0 until ISO_TTL_MS) return iso }
        return readCountryIso(context).also { isoCache = now to it }
    }

    @Volatile
    private var isoCache: Pair<Long, String>? = null
    private const val ISO_TTL_MS = 15_000L

    private fun readCountryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val system = runCatching {
            val list = Resources.getSystem().configuration.locales
            (0 until list.size()).map { list[it].country } + LocaleList.getAdjustedDefault().let { l -> (0 until l.size()).map { l[it].country } }
        }.getOrDefault(emptyList())
        return RegionPick.pick(
            runCatching { tm?.simCountryIso }.getOrNull(), runCatching { tm?.networkCountryIso }.getOrNull(), system, Locale.getDefault().country,
        )
    }

    /**
     * The country of the SIM that handled a call ([accountId] is its PhoneAccountHandle id, as stored in the
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
        misses[accountId]?.let { at -> if (SystemClock.elapsedRealtime() - at < MISS_TTL_MS) return null }
        val found = try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val subs = sm?.activeSubscriptionInfoList.orEmpty()
            val subId = if (Build.VERSION.SDK_INT >= 30) {
                val tm = context.getSystemService(TelephonyManager::class.java)
                context.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts
                    ?.firstOrNull { it.id == accountId }?.let { h -> runCatching { tm?.getSubscriptionId(h) }.getOrNull() }
            } else {
                null
            }

            @Suppress("DEPRECATION") // The ICCID is deprecated for apps but still read where Android gives it.
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
        if (found != null) simCountries[accountId] = found else misses[accountId] = SystemClock.elapsedRealtime()
        return found
    }
}
