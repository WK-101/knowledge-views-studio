package app.parley.data.calls

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.parley.common.SimAccount
import app.parley.common.calls.AssistedDial
import app.parley.common.calls.AssistedDialConfig
import app.parley.common.calls.DriveProfileConfig
import app.parley.data.SimRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Calls › Drive profile (I11): the cars and what happens while one is connected. A small JSON document in
 * its own preferences file, read once and kept in memory, so the call path reads it without waiting on disk. The
 * cars' Bluetooth addresses stay on this phone (not in backups: a new phone pairs again).
 */
class DriveProfileRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(DriveProfileConfig.decode(prefs.getString(KEY, null)))
    val config: StateFlow<DriveProfileConfig> = _config.asStateFlow()

    @Synchronized
    fun update(transform: (DriveProfileConfig) -> DriveProfileConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit().putString(KEY, DriveProfileConfig.encode(next)).apply()
    }

    private companion object {
        const val FILE = "parley_drive_profile"
        const val KEY = "config"
    }
}

/**
 * Settings › Calls › Abroad (L6): assisted dialling and the local-SIM hint, the trip the hint was last shown for, and
 * each SIM's home and network country as telephony reports them now (READ_PHONE_STATE, already held).
 */
// Telephony calls here are covered by READ_PHONE_STATE and the default-dialer role; each one handles SecurityException.
@SuppressLint("MissingPermission")
class RoamingRepository(context: Context, private val sims: SimRepository) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(AssistedDialConfig.decode(prefs.getString(KEY, null)))
    val config: StateFlow<AssistedDialConfig> = _config.asStateFlow()

    @Synchronized
    fun update(transform: (AssistedDialConfig) -> AssistedDialConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit().putString(KEY, AssistedDialConfig.encode(next)).apply()
    }

    /** The trip the local-SIM hint was shown for ([AssistedDial.trip]), or null. */
    var hintedTrip: String?
        get() = prefs.getString(KEY_TRIP, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_TRIP) else putString(KEY_TRIP, value) }.apply()
        }

    /** Each call-capable SIM in [accounts] with its countries now. Blocking (binder calls): call it off the main thread. */
    fun simStates(accounts: List<SimAccount>): List<AssistedDial.Sim> {
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val subs = runCatching { app.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
        return accounts.mapNotNull { a ->
            val subId = subIdFor(tm, a.id, subs) ?: if (accounts.size == 1) SubscriptionManager.getDefaultSubscriptionId() else null
            val one = subId?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }?.let { runCatching { tm.createForSubscriptionId(it) }.getOrNull() }
                ?: tm.takeIf { accounts.size == 1 }
                ?: return@mapNotNull null
            runCatching {
                AssistedDial.Sim(a.id, a.label, one.simCountryIso, one.networkCountryIso, one.isNetworkRoaming)
            }.getOrNull()
        }
    }

    private fun subIdFor(tm: TelephonyManager, accountId: String, subs: List<android.telephony.SubscriptionInfo>): Int? {
        if (Build.VERSION.SDK_INT >= 30) {
            sims.handle(accountId)?.let { h -> runCatching { tm.getSubscriptionId(h) }.getOrNull() }
                ?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }?.let { return it }
        }
        // Android 10 and some OEMs: the handle id is the ICCID or the subscription id itself.
        @Suppress("DEPRECATION")
        return subs.firstOrNull { it.iccId == accountId || it.subscriptionId.toString() == accountId }?.subscriptionId
    }

    private companion object {
        const val FILE = "parley_roaming"
        const val KEY = "config"
        const val KEY_TRIP = "hinted_trip"
    }
}
