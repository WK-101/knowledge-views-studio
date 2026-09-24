package app.parley.data

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.parley.common.SimAccount

// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
class SimRepository(private val context: Context) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val handles = HashMap<String, PhoneAccountHandle>()

    /** Call-capable accounts (SIMs, plus SIP accounts if configured). */
    fun accounts(): List<SimAccount> = try {
        val subs = subscriptionSlots()
        telecom.callCapablePhoneAccounts.mapNotNull { h ->
            val acc = telecom.getPhoneAccount(h) ?: return@mapNotNull null
            handles[h.id] = h
            val subId = subIdFor(h)
            val slot = subs[subId] ?: -1
            SimAccount(
                id = h.id,
                label = acc.label?.toString()?.ifBlank { null } ?: "SIM ${slot + 1}",
                subtitle = acc.shortDescription?.toString(),
                color = acc.highlightColor,
                slotIndex = slot,
            )
        }.sortedBy { if (it.slotIndex < 0) 99 else it.slotIndex }
    } catch (_: SecurityException) {
        emptyList()
    }

    fun handle(id: String?): PhoneAccountHandle? {
        if (id == null) return null
        if (handles.isEmpty()) accounts()
        return handles[id]
    }

    fun defaultOutgoing(): String? = try {
        telecom.getDefaultOutgoingPhoneAccount("tel")?.id
    } catch (_: SecurityException) {
        null
    }

    /** The user's own numbers (needs READ_PHONE_NUMBERS, optional). */
    fun ownNumbers(): List<String> = try {
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val subs = sm.activeSubscriptionInfoList.orEmpty()
        subs.mapNotNull { info ->
            if (android.os.Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId).ifBlank { null }
            else @Suppress("DEPRECATION") info.number?.ifBlank { null }
        }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun subscriptionSlots(): Map<Int, Int> = try {
        context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            .associate { it.subscriptionId to it.simSlotIndex }
    } catch (_: SecurityException) {
        emptyMap()
    }

    private fun subIdFor(h: PhoneAccountHandle): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(TelephonyManager::class.java).getSubscriptionId(h)
        } else {
            h.id.toIntOrNull() ?: -1
        }
    } catch (_: Exception) {
        -1
    }
}
