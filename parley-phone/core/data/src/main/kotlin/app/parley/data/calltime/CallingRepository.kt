package app.parley.data.calltime

import android.content.Context
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.CallingJson
import app.parley.common.calltime.Ussd
import app.parley.common.calltime.UssdEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Call-time settings (reminders, limits, allowances, supervision, haptics) and the USSD reply history.
 *
 * A small JSON document in its own preferences file, read once and kept in memory: the call path reads it
 * without waiting on disk, and nothing here is shared with the main settings store.
 */
class CallingRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(CallingJson.decode(prefs.getString(KEY_CONFIG, null)))
    val config: StateFlow<CallingConfig> = _config.asStateFlow()

    private val _ussd = MutableStateFlow(CallingJson.decodeUssd(prefs.getString(KEY_USSD, null)))
    val ussdHistory: StateFlow<List<UssdEntry>> = _ussd.asStateFlow()

    @Synchronized
    fun update(transform: (CallingConfig) -> CallingConfig) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit().putString(KEY_CONFIG, CallingJson.encode(next)).apply()
    }

    @Synchronized
    fun addUssd(entry: UssdEntry) {
        val next = Ussd.append(_ussd.value, entry)
        _ussd.value = next
        prefs.edit().putString(KEY_USSD, CallingJson.encodeUssd(next)).apply()
    }

    @Synchronized
    fun clearUssd() {
        _ussd.value = emptyList()
        prefs.edit().remove(KEY_USSD).apply()
    }

    private companion object {
        const val FILE = "parley_calling"
        const val KEY_CONFIG = "config_v1"
        const val KEY_USSD = "ussd_history_v1"
    }
}
