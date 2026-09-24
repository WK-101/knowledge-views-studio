package app.parley.data.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.parley.common.history.HistoryFilter
import app.parley.common.history.PlanConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.historyStore: DataStore<Preferences> by preferencesDataStore(name = "history")

data class HistorySettings(
    /** Mirror every call into Parley's encrypted archive (on by default). */
    val archiveEnabled: Boolean = true,
    val savedFilters: List<HistoryFilter> = emptyList(),
    /** UTF-8 byte-order mark in CSV exports (helps Excel). */
    val csvBom: Boolean = true,
    val plans: List<PlanConfig> = emptyList(),
    /** "simId|cycleStart" of plans already warned about. */
    val warnedCycles: Set<String> = emptySet(),
    val lastFullSync: Long = 0,
)

/** Call-history settings in their own DataStore file (kept apart from the main settings). */
class HistoryPrefs(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.historyStore
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    val state: StateFlow<HistorySettings> = store.data.map { it.toSettings().also { _loaded.value = true } }
        .stateIn(scope, SharingStarted.Eagerly, HistorySettings())

    suspend fun current(): HistorySettings = if (_loaded.value) state.value else store.data.first().toSettings()

    private fun Preferences.toSettings() = HistorySettings(
        archiveEnabled = this[K.archive] ?: true,
        savedFilters = HistoryFilter.decodeList(this[K.filters]),
        csvBom = this[K.bom] ?: true,
        plans = PlanConfig.decodeList(this[K.plans]),
        warnedCycles = this[K.warned].orEmpty(),
        lastFullSync = this[K.lastFull] ?: 0,
    )

    suspend fun setArchiveEnabled(v: Boolean) = store.edit { it[K.archive] = v }
    suspend fun setSavedFilters(list: List<HistoryFilter>) = store.edit { it[K.filters] = HistoryFilter.encodeList(list) }
    suspend fun setCsvBom(v: Boolean) = store.edit { it[K.bom] = v }
    suspend fun setLastFullSync(t: Long) = store.edit { it[K.lastFull] = t }

    suspend fun setPlan(p: PlanConfig) = store.edit { prefs ->
        val list = PlanConfig.decodeList(prefs[K.plans]).filter { it.simId != p.simId } + p
        prefs[K.plans] = PlanConfig.encodeList(list)
    }

    suspend fun removePlan(simId: String) = store.edit { prefs ->
        prefs[K.plans] = PlanConfig.encodeList(PlanConfig.decodeList(prefs[K.plans]).filter { it.simId != simId })
    }

    /** Remembers a warning; keeps only the last few cycles. */
    suspend fun markWarned(key: String) = store.edit { prefs ->
        prefs[K.warned] = (prefs[K.warned].orEmpty().toList().takeLast(11) + key).toSet()
    }

    private object K {
        val archive = booleanPreferencesKey("archive_enabled")
        val filters = stringPreferencesKey("saved_filters")
        val bom = booleanPreferencesKey("csv_bom")
        val plans = stringPreferencesKey("plans")
        val warned = stringSetPreferencesKey("plan_warned")
        val lastFull = longPreferencesKey("last_full_sync")
    }
}
