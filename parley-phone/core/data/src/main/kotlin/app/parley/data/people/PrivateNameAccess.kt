package app.parley.data.people

import java.util.Locale
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import app.parley.common.people.LookupApproval
import app.parley.common.people.LookupOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LookupLogEntry(val packageName: String, val time: Long, val outcome: LookupOutcome, val viaDirectory: Boolean = false)

data class PrivateNameState(
    val enabled: Boolean = false,
    val approvals: Map<String, LookupApproval> = emptyMap(),
    val log: List<LookupLogEntry> = emptyList(),
    /** I7: the contacts Directory that approved phone apps can ask (off by default). */
    val directory: Boolean = false,
)

/**
 * Decisions and access log for "Let apps show private names" (the protected lookup provider). Synchronous,
 * SharedPreferences-backed: the provider answers on a binder thread. The log never stores the number asked for.
 *
 * An approval belongs to an app, not to a package name: the SHA-256 of the app's signing certificate is stored
 * with it and checked on every use, so another app installed under the same name (on this phone, or after a
 * restore on a new one) has to be approved again.
 */
class PrivateNameAccess(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val prefs = context.applicationContext.getSharedPreferences("private_names", Context.MODE_PRIVATE)
    private val recent = HashMap<String, ArrayDeque<Long>>()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PrivateNameState> = _state

    @Synchronized
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        _state.value = read()
    }

    /** I7: turns the Directory on or off (the caller also enables or disables the provider component). */
    @Synchronized
    fun setDirectoryEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_DIRECTORY, on).apply()
        _state.value = read()
    }

    /**
     * The decision for [pkg]. "Allowed" only counts while the app is signed by the certificate it was allowed with;
     * otherwise it's as if the app never asked.
     */
    @Synchronized
    fun approval(pkg: String): LookupApproval? {
        val a = _state.value.approvals[pkg] ?: return null
        if (a != LookupApproval.ALLOWED) return a
        val cert = certs()[pkg] ?: return null
        return if (signedWith(pkg, cert)) a else null
    }

    @Synchronized
    fun setApproval(pkg: String, a: LookupApproval?) {
        // Allowing needs the app's certificate: an app that can't be looked up now is asked again on its next query.
        val cert = if (a == LookupApproval.ALLOWED) currentCert(pkg) else null
        store(pkg, if (a == LookupApproval.ALLOWED && cert == null) null else a, cert)
    }

    private fun store(pkg: String, a: LookupApproval?, cert: String?) {
        val m = _state.value.approvals.toMutableMap()
        if (a == null) m.remove(pkg) else m[pkg] = a
        val c = certs().toMutableMap()
        if (cert == null) c.remove(pkg) else c[pkg] = cert
        prefs.edit()
            .putString(K_APPROVALS, JSONObject(m.mapValues { it.value.name }).toString())
            .putString(K_CERTS, JSONObject(c.toMap()).toString())
            .apply()
        _state.value = read()
    }

    private fun certs(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(K_CERTS, "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** Hex SHA-256 of the app's current signing certificate, or null when it isn't installed. */
    private fun currentCert(pkg: String): String? = runCatching {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
        val signers = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        val cert = signers?.lastOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }.getOrNull()

    /** Whether [pkg] is (still) signed with [certHex], including after a key rotation Android vouches for. */
    private fun signedWith(pkg: String, certHex: String): Boolean = runCatching {
        val bytes = certHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        pm.hasSigningCertificate(pkg, bytes, PackageManager.CERT_INPUT_SHA256)
    }.getOrDefault(false)

    /** Query times of [pkg] in the last hour (in memory; restarts with the process, which only loosens the limit). */
    @Synchronized
    fun recentQueries(pkg: String, now: Long): List<Long> {
        val q = recent.getOrPut(pkg) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 3_600_000L) q.removeFirst()
        return q.toList()
    }

    @Synchronized
    fun log(pkg: String, outcome: LookupOutcome, now: Long = System.currentTimeMillis(), viaDirectory: Boolean = false) {
        recent.getOrPut(pkg) { ArrayDeque() }.addLast(now)
        val list = (_state.value.log + LookupLogEntry(pkg, now, outcome, viaDirectory)).takeLast(MAX_LOG)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("p", it.packageName).put("t", it.time).put("o", it.outcome.name).apply { if (it.viaDirectory) put("d", true) }) }
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
                LookupOutcome.entries.firstOrNull { it.name == o.optString("o") }?.let { LookupLogEntry(o.getString("p"), o.getLong("t"), it, o.optBoolean("d")) }
            }
        }.getOrDefault(emptyList())
        return PrivateNameState(prefs.getBoolean(K_ENABLED, false), approvals, log, prefs.getBoolean(K_DIRECTORY, false))
    }

    /** Approvals for the backup, each with the certificate it was given to (the log is not backed up). */
    fun exportApprovals(): String {
        val certs = certs()
        val out = JSONObject()
        _state.value.approvals.filterValues { it != LookupApproval.PENDING }.forEach { (pkg, a) ->
            out.put(pkg, JSONObject().put("approval", a.name).apply { certs[pkg]?.let { put("cert", it) } })
        }
        return out.toString()
    }

    /**
     * Restores approvals. "Don't allow" always comes back; "Allow" only when the app installed here is signed with
     * the certificate it was allowed with. Older backups carry no certificate, so their "Allow"s must be given again.
     */
    @Synchronized
    fun importApprovals(json: String) {
        runCatching {
            val o = JSONObject(json)
            for (pkg in o.keys()) {
                val entry = o.optJSONObject(pkg)
                val a = LookupApproval.entries.firstOrNull { it.name == (entry?.optString("approval") ?: o.optString(pkg)) } ?: continue
                when (a) {
                    LookupApproval.DENIED -> store(pkg, a, null)
                    LookupApproval.ALLOWED -> {
                        val cert = entry?.optString("cert")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: continue
                        if (signedWith(pkg, cert)) store(pkg, a, cert)
                    }
                    LookupApproval.PENDING -> Unit
                }
            }
        }
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_APPROVALS = "approvals"
        const val K_LOG = "log"
        const val K_CERTS = "approval_certs"
        const val K_DIRECTORY = "directory"
        const val MAX_LOG = 200
    }
}
