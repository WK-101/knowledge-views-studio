package app.parley.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/** The SIMs calls are on: their names and own numbers (on dual-SIM phones), kept while calls exist. */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
internal class CallAccounts(private val context: () -> Context?) {
    private val labels = HashMap<PhoneAccountHandle, String?>()
    private val numbers = HashMap<PhoneAccountHandle, String?>()

    /** The SIM's name, only on phones with two call-capable accounts. */
    fun label(h: PhoneAccountHandle?): String? {
        val ctx = context()
        if (h == null || ctx == null) return null
        return labels.getOrPut(h) {
            try {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                if (tm.callCapablePhoneAccounts.size < 2) null else tm.getPhoneAccount(h)?.label?.toString()
            } catch (_: SecurityException) {
                null
            }
        }
    }

    /** The SIM's own number, only on dual-SIM phones and only when Android knows it. */
    fun number(h: PhoneAccountHandle?): String? {
        val ctx = context()
        if (h == null || ctx == null || label(h) == null) return null
        return numbers.getOrPut(h) {
            try {
                val a = ctx.getSystemService(TelecomManager::class.java).getPhoneAccount(h)
                (a?.subscriptionAddress ?: a?.address)?.schemeSpecificPart?.takeIf { n -> n.count { it.isDigit() } >= 4 }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun handleFor(accountId: String): PhoneAccountHandle? = try {
        context()?.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts?.firstOrNull { it.id == accountId }
    } catch (_: Exception) {
        null
    }

    /** No call is left: a SIM swapped meanwhile is read again. */
    fun clear() {
        labels.clear()
        numbers.clear()
    }
}
