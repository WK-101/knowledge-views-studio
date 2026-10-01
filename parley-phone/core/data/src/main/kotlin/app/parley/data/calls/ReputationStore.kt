package app.parley.data.calls

import android.content.Context
import android.util.Base64
import app.parley.common.PhoneNumbers
import app.parley.common.spam.CallReputation
import app.parley.common.spam.Reputation
import app.parley.common.spam.ReputationIndex
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * I2 personal reputation, as the call path reads it: what [CallReputation] learned in the daily maintenance run, keyed by
 * the call-history archive's keyed fingerprints (of a line, or of a range) and sealed with the archive key, so neither
 * numbers nor scores are readable at rest. The call path only looks a number up in memory (two HMACs and two map reads);
 * nothing is worked out while a call rings.
 *
 * It is derived data: every run replaces it as a whole, and a stored index that can't be opened right now (a Keystore
 * hiccup) just means no tags until it can, never a wrong one. Nothing leaves the phone, and it isn't backed up.
 */
class ReputationStore private constructor(context: Context, private val keySource: KeySource) {
    constructor(context: Context, history: () -> CallHistory) : this(context, KeySource { HistoryKeys(history()) })

    /** With [keys] in place of the archive's (tests). */
    internal constructor(context: Context, keys: Keys) : this(context, KeySource { keys })

    private fun interface KeySource {
        fun keys(): Keys
    }

    /** The archive's keyed fingerprint and its key (tests use their own). */
    internal interface Keys {
        fun mac(value: String): String
        fun seal(plain: ByteArray): ByteArray
        fun open(blob: ByteArray): ByteArray
    }

    private class HistoryKeys(private val h: CallHistory) : Keys {
        override fun mac(value: String) = h.auxMac(value)
        override fun seal(plain: ByteArray) = h.sealAux(plain)
        override fun open(blob: ByteArray) = h.openAux(blob)
    }

    private val prefs by lazy { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    /** Fingerprint → assessment; null until read (or while the stored index can't be opened). */
    @Volatile private var entries: Map<String, Reputation>? = null

    /** Whether anything was stored, as last read; null before the first read. */
    @Volatile private var stored: Boolean? = null

    private val _version = MutableStateFlow(0)

    /** Bumped whenever the index changes, so Recents and number history re-read. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** For the call path's "is screening needed at all?" (memory only): true until the first read says otherwise. */
    val mayHaveEntries: Boolean get() = stored ?: true

    /** Reads the stored index once (off the main thread). Returns false when it can't be opened right now. */
    @Synchronized
    fun load(): Boolean {
        if (entries != null) return true
        val text = prefs.getString(KEY, null)
        if (text.isNullOrEmpty()) {
            entries = emptyMap()
            stored = false
            return true
        }
        val opened = runCatching { String(keySource.keys().open(Base64.decode(text, Base64.NO_WRAP))) }.getOrNull() ?: return false
        val map = CallReputation.decode(opened)
        entries = map
        stored = map.isNotEmpty()
        return true
    }

    /** What your calls say about [number] (read with [countryIso]), or null: never seen, never tagged, or unreadable now. */
    fun lookup(number: String?, countryIso: String?): Reputation? {
        val line = PhoneNumbers.toE164(number, countryIso) ?: return null
        if (entries == null && !load()) return null
        val map = entries ?: return null
        if (map.isEmpty()) return null
        val k = keySource.keys()
        return runCatching {
            map[k.mac(LINE + line)] ?: CallReputation.rangeOf(line)?.let { map[k.mac(RANGE + it)] }
        }.getOrNull()
    }

    /** Replaces the whole index with [index]. False when it couldn't be sealed (nothing is ever stored in plain text). */
    @Synchronized
    fun replace(index: ReputationIndex): Boolean {
        val k = runCatching { keySource.keys() }.getOrNull() ?: return false
        val map = runCatching {
            HashMap<String, Reputation>().apply {
                index.lines.forEach { (line, r) -> put(k.mac(LINE + line), r) }
                index.ranges.forEach { (range, r) -> put(k.mac(RANGE + range), r) }
            }
        }.getOrNull() ?: return false
        if (map == entries) return true
        val sealed = if (map.isEmpty()) {
            null
        } else {
            runCatching { Base64.encodeToString(k.seal(CallReputation.encode(map).toByteArray()), Base64.NO_WRAP) }.getOrNull() ?: return false
        }
        prefs.edit().apply { if (sealed == null) remove(KEY) else putString(KEY, sealed) }.apply()
        entries = map
        stored = map.isNotEmpty()
        _version.value++
        return true
    }

    /** "Learn from your calls" turned off, or data deleted: forget everything learned. */
    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY).apply()
        entries = emptyMap()
        stored = false
        _version.value++
    }

    private companion object {
        const val FILE = "parley_reputation"
        const val KEY = "index_v1"
        const val LINE = "rep:n:"
        const val RANGE = "rep:r:"
    }
}
