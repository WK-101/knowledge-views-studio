package app.parley.data

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import app.parley.common.calls.InternetCalls

/**
 * The packages whose phone accounts are the phone network's, for telling internet calls in the call log apart
 * ([InternetCalls.appPackage]): the usual ones plus the packages of this phone's SIM accounts, read once some are
 * found (a maker may move them to its own package).
 */
object TelephonyPackages {
    @Volatile
    private var found: Set<String>? = null

    // READ_PHONE_STATE is asked for with the phone role; without it the usual packages are used.
    @SuppressLint("MissingPermission")
    fun of(context: Context): Set<String> {
        found?.let { return it }
        val sims = try {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return InternetCalls.TELEPHONY_PACKAGES
            telecom.callCapablePhoneAccounts.mapNotNull { h ->
                h.componentName.packageName.takeIf { telecom.getPhoneAccount(h)?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true }
            }
        } catch (_: SecurityException) {
            emptyList()
        }
        val all = InternetCalls.TELEPHONY_PACKAGES + sims
        if (sims.isNotEmpty()) found = all
        return all
    }

    /** The app a call log row with [component] went through, or null for a phone call. */
    fun appOf(context: Context, component: String?): String? =
        if (component.isNullOrBlank()) null else InternetCalls.appPackage(component, of(context))
}
