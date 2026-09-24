package app.parley.data.people

import android.content.Context
import app.parley.common.people.LookupApproval
import app.parley.common.people.LookupOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LookupLogEntry(val packageName: String, val time: Long, val outcome: LookupOutcome)

data class PrivateNameState(
    val enabled: Boolean = false,
    val approvals: Map<String, LookupApproval> = emptyMap(),
    val log: List<LookupLogEntry> = emptyList(),
)

/**
 * Decisions and access log for "Let apps show private names" (the protected lookup provider). Synchronous,
 * SharedPreferences-backed: the provider answers on a binder thread. The log never stores the number asked for.
 */
class PrivateNameAccess(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("private_names", Context.MODE_PRIVATE)
    private val recent = HashMap<String, ArrayDeque<Long>>()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PrivateNameState> = _state

    @Synchronized
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        _state.value = read()
    }

    @Synchronized
    fun approval(pkg: String): LookupApproval? = _state.value.approvals[pkg]

    @Synchronized
    fun setApproval(pkg: String, a: LookupApproval?) {
        val m = _state.value.approvals.toMutableMap()
        if (a == null) m.remove(pkg) else m[pkg] = a
        prefs.edit().putString(K_APPROVALS, JSONObject(m.mapValues { it.value.name }).toString()).apply()
        _state.value = read()
    }

    /** Query times of [pkg] in the last hour (in memory; restarts with the process, which only loosens the limit). */
    @Synchronized
    fun recentQueries(pkg: String, now: Long): List<Long> {
        val q = recent.getOrPut(pkg) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 3_600_000L) q.removeFirst()
        return q.toList()
    }

    @Synchronized
    fun log(pkg: String, outcome: LookupOutcome, now: Long = System.currentTimeMillis()) {
        recent.getOrPut(pkg) { ArrayDeque() }.addLast(now)
        val list = (_state.value.log + LookupLogEntry(pkg, now, outcome)).takeLast(MAX_LOG)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("p", it.packageName).put("t", it.time).put("o", it.outcome.name)) }
        prefs.edit().putString(K_LOG, arr.toString()).apply()
        _state.value = _state.value.copy(log = list)
    }

    @Synchronized
    fun clearLog() {
        prefs.edit().remove(K_LOG).apply()
        _state.value = read()
    }

    private fun read(): PrivateNameState {
        val approvals = runCatching {
            val o = JSONObject(prefs.getString(K_APPROVALS, "{}")!!)
            o.keys().asSequence().mapNotNull { k -> LookupApproval.entries.firstOrNull { it.name == o.getString(k) }?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
        val log = runCatching {
            val a = JSONArray(prefs.getString(K_LOG, "[]")!!)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                LookupOutcome.entries.firstOrNull { it.name == o.optString("o") }?.let { LookupLogEntry(o.getString("p"), o.getLong("t"), it) }
            }
        }.getOrDefault(emptyList())
        return PrivateNameState(prefs.getBoolean(K_ENABLED, false), approvals, log)
    }

    /** Approvals for the backup (the log is not backed up). */
    fun exportApprovals(): String = JSONObject(_state.value.approvals.filterValues { it != LookupApproval.PENDING }.mapValues { it.value.name }).toString()

    fun importApprovals(json: String) {
        runCatching {
            val o = JSONObject(json)
            o.keys().forEach { k -> LookupApproval.entries.firstOrNull { it.name == o.getString(k) }?.let { setApproval(k, it) } }
        }
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_APPROVALS = "approvals"
        const val K_LOG = "log"
        const val MAX_LOG = 200
    }
}
