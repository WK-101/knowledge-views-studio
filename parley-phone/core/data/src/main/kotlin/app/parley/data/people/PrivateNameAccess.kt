package app.parley.data.people

import java.util.Locale
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import app.parley.common.people.LookupApproval
import app.parley.common.people.LookupOutcome
import app.parley.common.people.LookupPolicy
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
    /** I7: approvals for the Directory, kept apart from the lookup provider's (allowing one never allows the other). */
    val directoryApprovals: Map<String, LookupApproval> = emptyMap(),
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
    fun approval(pkg: String, directory: Boolean = false): LookupApproval? {
        val a = approvalsOf(directory)[pkg] ?: return null
        if (a != LookupApproval.ALLOWED) return a
        val cert = certs(directory)[pkg] ?: return null
        return if (signedWith(pkg, cert)) a else null
    }

    /** [directory]: the Directory's approval (I7), separate from the lookup provider's. */
    @Synchronized
    fun setApproval(pkg: String, a: LookupApproval?, directory: Boolean = false) {
        // Allowing needs the app's certificate: an app that can't be looked up now is asked again on its next query.
        val cert = if (a == LookupApproval.ALLOWED) currentCert(pkg) else null
        store(pkg, if (a == LookupApproval.ALLOWED && cert == null) null else a, cert, directory)
    }

    /**
     * Whether to show [pkg] an approval prompt now; records the prompt when it returns true. At most one prompt per
     * app and scope a day ([LookupPolicy.shouldAsk]), so an app that keeps querying can't flood the user.
     */
    @Synchronized
    fun takePrompt(pkg: String, directory: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val key = (if (directory) "d:" else "p:") + pkg
        val asked = runCatching { JSONObject(prefs.getString(K_ASKED, "{}")!!) }.getOrDefault(JSONObject())
        if (!LookupPolicy.shouldAsk(approval(pkg, directory), asked.optLong(key, 0L), now)) return false
        asked.put(key, now)
        prefs.edit().putString(K_ASKED, asked.toString()).apply()
        return true
    }

    private fun approvalsOf(directory: Boolean) = if (directory) _state.value.directoryApprovals else _state.value.approvals

    private fun store(pkg: String, a: LookupApproval?, cert: String?, directory: Boolean = false) {
        val m = approvalsOf(directory).toMutableMap()
        if (a == null) m.remove(pkg) else m[pkg] = a
        val c = certs(directory).toMutableMap()
        if (cert == null) c.remove(pkg) else c[pkg] = cert
        prefs.edit()
            .putString(if (directory) K_DIR_APPROVALS else K_APPROVALS, JSONObject(m.mapValues { it.value.name }).toString())
            .putString(if (directory) K_DIR_CERTS else K_CERTS, JSONObject(c.toMap()).toString())
            .apply()
        _state.value = read()
    }

    private fun certs(directory: Boolean = false): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(if (directory) K_DIR_CERTS else K_CERTS, "{}")!!)
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
        fun approvals(key: String) = runCatching {
            val o = JSONObject(prefs.getString(key, "{}")!!)
            o.keys().asSequence().mapNotNull { k -> LookupApproval.entries.firstOrNull { it.name == o.getString(k) }?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
        val approvals = approvals(K_APPROVALS)
        val log = runCatching {
            val a = JSONArray(prefs.getString(K_LOG, "[]")!!)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                LookupOutcome.entries.firstOrNull { it.name == o.optString("o") }?.let { LookupLogEntry(o.getString("p"), o.getLong("t"), it, o.optBoolean("d")) }
            }
        }.getOrDefault(emptyList())
        return PrivateNameState(prefs.getBoolean(K_ENABLED, false), approvals, log, prefs.getBoolean(K_DIRECTORY, false), approvals(K_DIR_APPROVALS))
    }

    /** Approvals for the backup, each with the certificate it was given to (the log is not backed up). */
    fun exportApprovals(): String {
        val out = JSONObject()
        for (directory in listOf(false, true)) {
            val certs = certs(directory)
            approvalsOf(directory).filterValues { it != LookupApproval.PENDING }.forEach { (pkg, a) ->
                // Directory approvals carry a prefix no package name can have; older versions skip them (not installed).
                out.put((if (directory) DIR_PREFIX else "") + pkg, JSONObject().put("approval", a.name).apply { certs[pkg]?.let { put("cert", it) } })
            }
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
            for (key in o.keys()) {
                val entry = o.optJSONObject(key)
                val a = LookupApproval.entries.firstOrNull { it.name == (entry?.optString("approval") ?: o.optString(key)) } ?: continue
                val directory = key.startsWith(DIR_PREFIX)
                val pkg = key.removePrefix(DIR_PREFIX)
                when (a) {
                    LookupApproval.DENIED -> store(pkg, a, null, directory)
                    LookupApproval.ALLOWED -> {
                        val cert = entry?.optString("cert")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: continue
                        if (signedWith(pkg, cert)) store(pkg, a, cert, directory)
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
        const val K_DIR_APPROVALS = "directory_approvals"
        const val K_DIR_CERTS = "directory_approval_certs"
        const val K_ASKED = "asked_at"
        const val DIR_PREFIX = "directory:"
        const val MAX_LOG = 200
    }
}
