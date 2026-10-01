package app.parley.data.calls

import android.content.Context
import android.util.Base64
import app.parley.common.calls.CallQualityCodec
import app.parley.common.calls.CallQualityFacts
import app.parley.data.history.CallHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quality facts per call (SIM, Wi-Fi calling, HD voice, why it ended, how long, the caller's subject), for the number
 * history now and a call quality diary later. Kept like [RingFactsStore]: rows are keyed by the call-history archive's
 * keyed fingerprint of the line (never the number), the facts are sealed with the archive key, the newest [MAX_ROWS] of
 * the last [KEEP_DAYS] days are kept, and deleting or purging calls forgets theirs. Nothing leaves the phone.
 */
class CallQualityStore(context: Context, private val history: () -> CallHistory) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private data class Row(val key: String, val facts: CallQualityFacts)

    private var rows: List<Row>? = null

    /** Bumped on every write, so screens re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun key(number: String?): String? =
        if (number.isNullOrBlank()) HIDDEN else runCatching { MAC + history().lineMac(number) }.getOrNull()

    @Synchronized
    private fun rows(): List<Row> = rows ?: decode(prefs.getString(KEY_ROWS, null)).also { rows = it }

    @Synchronized
    fun add(number: String?, facts: CallQualityFacts, now: Long = System.currentTimeMillis()) {
        // Without the key nothing is stored (never in plain text).
        val k = key(number) ?: return
        val next = (listOf(Row(k, facts)) + rows().filterNot { it.key == k && it.facts.startedAt == facts.startedAt })
            .filter { now - it.facts.startedAt < KEEP_DAYS * 86_400_000L }
            .take(MAX_ROWS)
        store(next)
    }

    /** Facts for [number], newest first. */
    fun forNumber(number: String?): List<CallQualityFacts> {
        val k = key(number) ?: return emptyList()
        return rows().filter { it.key == k }.map { it.facts }.sortedByDescending { it.startedAt }
    }

    /** Forgets [number]'s facts: those of the calls at [dates] (call-log dates), or all when [dates] is null. */
    @Synchronized
    fun forget(number: String, dates: List<Long>? = null) {
        val k = key(number) ?: return
        val mine = rows().filter { it.key == k }
        if (mine.isEmpty()) return
        val drop = if (dates == null) mine.map { it.facts }.toSet()
        else dates.mapNotNull { d -> CallQualityCodec.near(mine.map { it.facts }, d) }.toSet()
        if (drop.isEmpty()) return
        store(rows().filterNot { it.key == k && it.facts in drop })
    }

    @Synchronized
    fun clear() {
        rows = emptyList()
        prefs.edit().remove(KEY_ROWS).apply()
        _version.value++
    }

    private fun store(next: List<Row>): Boolean {
        val text = runCatching { encode(next) }.getOrNull() ?: return false
        rows = next
        prefs.edit().putString(KEY_ROWS, text).apply()
        _version.value++
        return true
    }

    // One line per row: "<key>\t<sealed facts JSON, Base64>", so each row opens on its own.
    private fun encode(rows: List<Row>): String {
        val h = history()
        return rows.joinToString("\n") { r ->
            r.key + "\t" + Base64.encodeToString(h.sealAux(CallQualityCodec.encode(listOf(r.facts)).toByteArray()), Base64.NO_WRAP)
        }
    }

    private fun decode(text: String?): List<Row> {
        if (text.isNullOrEmpty()) return emptyList()
        val h = runCatching { history() }.getOrNull() ?: return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val json = runCatching { String(h.openAux(Base64.decode(line.substring(tab + 1), Base64.NO_WRAP))) }.getOrNull() ?: return@mapNotNull null
            CallQualityCodec.decode(json).firstOrNull()?.let { Row(line.substring(0, tab), it) }
        }.toList()
    }

    private companion object {
        const val FILE = "parley_call_quality"
        const val KEY_ROWS = "rows_v1"
        const val HIDDEN = "hidden"
        const val MAC = "m:"
        const val MAX_ROWS = 400
        const val KEEP_DAYS = 60L
    }
}
