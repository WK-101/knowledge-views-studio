package app.parley.data.people

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import app.parley.common.NotificationIds
import app.parley.common.people.LookupApproval
import app.parley.common.people.LookupOutcome
import app.parley.common.people.LookupPolicy
import app.parley.data.security.Privacy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class LookupLogEntry(val packageName: String, val time: Long, val outcome: LookupOutcome)

data class PrivateNameState(
    /** The contacts Directory that approved phone apps can ask (off by default). */
    val directory: Boolean = false,
    /** The phone apps allowed (or not) to read private names through the Directory. */
    val approvals: Map<String, LookupApproval> = emptyMap(),
    val log: List<LookupLogEntry> = emptyList(),
)

/**
 * Decisions and access log for "Private names in other phone apps" (the opt-in contacts Directory). Synchronous,
 * SharedPreferences-backed: the provider answers on a binder thread. The log never stores the number asked for.
 *
 * An approval belongs to an app, not to a package name: the SHA-256 of the app's signing certificate is stored
 * with it and checked on every use, so another app installed under the same name (on this phone, or after a
 * restore on a new one) has to be approved again.
 *
 * While a duress unlock hides things ([Privacy.duressOnly().hiding]) the switch and the approvals are safety switches like
 * the app lock: what the screens change is shown ([state]) but kept in memory only and dropped at the next lock
 * ([endSession]); nothing is stored, so nobody can leave an app lasting access to private names from a duress session.
 * The provider reads what is stored ([stored], [approval]), never the session's view.
 *
 * Parley once also had a lookup provider of its own for apps holding a custom permission. No app ever used it, so it
 * went; its switch, approvals and log lines are removed from this store the first time it opens.
 */
class PrivateNameAccess(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val prefs = context.applicationContext.getSharedPreferences("private_names", Context.MODE_PRIVATE)
    private val notifications = NotificationManagerCompat.from(context.applicationContext)
    private val recent = HashMap<String, ArrayDeque<Long>>()
    private val _stored = MutableStateFlow(run { forgetLookupProvider(); read() })

    /** What a duress session changed, shown instead of the stored values until [endSession] (null: nothing). */
    private var session: PrivateNameState? = null
    private val _state = MutableStateFlow(_stored.value)

    /** What the screens show: as stored, or with a duress session's changes on top. */
    val state: StateFlow<PrivateNameState> = _state

    /** As stored: what the provider answers by. */
    val stored: PrivateNameState get() = _stored.value

    private fun publish(s: PrivateNameState) {
        _stored.value = s
        _state.value = session?.copy(log = s.log) ?: s
    }

    /** True when a change must stay in memory now (see the class comment). */
    private fun hidingNow(): Boolean = Privacy.duressOnly().hiding

    /** A duress session's change: shown, never stored. */
    private fun sessionChange(f: (PrivateNameState) -> PrivateNameState) {
        val next = f(session ?: _stored.value)
        session = next
        _state.value = next.copy(log = _stored.value.log)
    }

    /** Parley locked or unlocked with a PIN: a duress session's changes are forgotten. */
    @Synchronized
    fun endSession() {
        session = null
        publish(_stored.value)
    }

    /**
     * Turns the Directory on or off. Returns whether the change was stored: only then may the caller enable or disable
     * the provider component (a duress session's change is only shown).
     */
    @Synchronized
    fun setDirectoryEnabled(on: Boolean): Boolean {
        if (hidingNow()) {
            sessionChange { it.copy(directory = on) }
            return false
        }
        prefs.edit().putBoolean(K_DIRECTORY, on).apply()
        publish(read())
        return true
    }

    /**
     * The decision for [pkg]. "Allowed" only counts while the app is signed by the certificate it was allowed with;
     * otherwise it's as if the app never asked.
     */
    @Synchronized
    fun approval(pkg: String): LookupApproval? {
        val a = _stored.value.approvals[pkg] ?: return null
        if (a != LookupApproval.ALLOWED) return a
        val cert = certs()[pkg] ?: return null
        return if (signedWith(pkg, cert)) a else null
    }

    /** The user's decision for [pkg]. While hiding it is only shown, never stored: an "Allow" given in a duress session grants nothing. */
    @Synchronized
    fun setApproval(pkg: String, a: LookupApproval?) {
        if (hidingNow()) {
            return sessionChange { st -> st.copy(approvals = st.approvals.toMutableMap().apply { if (a == null) remove(pkg) else this[pkg] = a }) }
        }
        // Allowing needs the app's certificate: an app that can't be looked up now is asked again on its next query.
        val cert = if (a == LookupApproval.ALLOWED) currentCert(pkg) else null
        store(pkg, if (a == LookupApproval.ALLOWED && cert == null) null else a, cert)
    }

    /** The provider's first query from [pkg]: waiting for the user's answer (stored also while hiding: it grants nothing). */
    @Synchronized
    fun markPending(pkg: String) = store(pkg, LookupApproval.PENDING, null)

    /** Hex SHA-256 of [pkg]'s signing certificate as Android reports it now, for the approval sheet; null when not installed. */
    fun certificateOf(pkg: String): String? = currentCert(pkg)

    /**
     * Whether to show [pkg] an approval prompt now; records the prompt when it returns true. At most one prompt per
     * app a day ([LookupPolicy.shouldAsk]), so an app that keeps querying can't flood the user.
     */
    @Synchronized
    fun takePrompt(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        val key = ASKED_PREFIX + pkg
        val asked = runCatching { JSONObject(prefs.getString(K_ASKED, "{}")!!) }.getOrDefault(JSONObject())
        if (!LookupPolicy.shouldAsk(approval(pkg), asked.optLong(key, 0L), now)) return false
        asked.put(key, now)
        prefs.edit().putString(K_ASKED, asked.toString()).apply()
        return true
    }

    private fun store(pkg: String, a: LookupApproval?, cert: String?) {
        val m = _stored.value.approvals.toMutableMap()
        if (a == null) m.remove(pkg) else m[pkg] = a
        val c = certs().toMutableMap()
        if (cert == null) c.remove(pkg) else c[pkg] = cert
        prefs.edit()
            .putString(K_DIR_APPROVALS, JSONObject(m.mapValues { it.value.name }).toString())
            .putString(K_DIR_CERTS, JSONObject(c.toMap()).toString())
            .apply()
        publish(read())
    }

    private fun certs(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString(K_DIR_CERTS, "{}")!!)
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
    fun log(pkg: String, outcome: LookupOutcome, now: Long = System.currentTimeMillis()) {
        recent.getOrPut(pkg) { ArrayDeque() }.addLast(now)
        val list = (_stored.value.log + LookupLogEntry(pkg, now, outcome)).takeLast(MAX_LOG)
        val arr = JSONArray()
        // "d": a Directory request (the lookup provider's lines had none, and are gone).
        list.forEach { arr.put(JSONObject().put("p", it.packageName).put("t", it.time).put("o", it.outcome.name).put("d", true)) }
        prefs.edit().putString(K_LOG, arr.toString()).apply()
        publish(_stored.value.copy(log = list))
    }

    @Synchronized
    fun clearLog() {
        prefs.edit().remove(K_LOG).apply()
        publish(read())
    }

    private fun read(): PrivateNameState {
        val approvals = runCatching {
            val o = JSONObject(prefs.getString(K_DIR_APPROVALS, "{}")!!)
            o.keys().asSequence().mapNotNull { k -> LookupApproval.entries.firstOrNull { it.name == o.getString(k) }?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
        val log = runCatching {
            val a = JSONArray(prefs.getString(K_LOG, "[]")!!)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                LookupOutcome.entries.firstOrNull { it.name == o.optString("o") }?.let { LookupLogEntry(o.getString("p"), o.getLong("t"), it) }
            }
        }.getOrDefault(emptyList())
        return PrivateNameState(prefs.getBoolean(K_DIRECTORY, false), approvals, log)
    }

    /**
     * Removes what the lookup provider kept: its switch, its approvals and their certificates, its prompt times and its
     * log lines, and takes down a request notification it may have left (its "Allow…" would otherwise answer for the
     * Directory, which that app never asked for). Idempotent, and a no-op once done.
     */
    private fun forgetLookupProvider() {
        val asked = runCatching { JSONObject(prefs.getString(K_ASKED, "{}")!!) }.getOrDefault(JSONObject())
        val lookupAsked = asked.keys().asSequence().filter { it.startsWith(LEGACY_ASKED_PREFIX) }.toList()
        val log = runCatching { JSONArray(prefs.getString(K_LOG, "[]")!!) }.getOrDefault(JSONArray())
        val lookupLines = (0 until log.length()).count { !log.getJSONObject(it).optBoolean("d") }
        if (LEGACY_KEYS.none { prefs.contains(it) } && lookupAsked.isEmpty() && lookupLines == 0) return
        val keptLog = JSONArray()
        for (i in 0 until log.length()) log.getJSONObject(i).takeIf { it.optBoolean("d") }?.let { keptLog.put(it) }
        lookupAsked.forEach { asked.remove(it) }
        // Every app the lookup provider knew of: approved or refused, prompted, or in its log.
        val approved = runCatching { JSONObject(prefs.getString("approvals", "{}")!!).keys().asSequence().toList() }.getOrDefault(emptyList())
        val logged = (0 until log.length()).mapNotNull { i -> log.getJSONObject(i).takeIf { !it.optBoolean("d") }?.optString("p")?.takeIf { it.isNotEmpty() } }
        (approved + lookupAsked.map { it.removePrefix(LEGACY_ASKED_PREFIX) } + logged).distinct().forEach { pkg ->
            runCatching { notifications.cancel(NotificationIds.TAG_PRIVATE_NAME, pkg.hashCode()) }
        }
        prefs.edit().apply {
            LEGACY_KEYS.forEach { remove(it) }
            putString(K_ASKED, asked.toString())
            putString(K_LOG, keptLog.toString())
        }.commit()
    }

    /** Approvals for the backup, each with the certificate it was given to (the log is not backed up). */
    fun exportApprovals(): String {
        val out = JSONObject()
        val certs = certs()
        _stored.value.approvals.filterValues { it != LookupApproval.PENDING }.forEach { (pkg, a) ->
            // The prefix older versions expect for Directory approvals (no package name can have it).
            out.put(DIR_PREFIX + pkg, JSONObject().put("approval", a.name).apply { certs[pkg]?.let { put("cert", it) } })
        }
        return out.toString()
    }

    /**
     * Restores approvals. "Don't allow" always comes back; "Allow" only when the app installed here is signed with
     * the certificate it was allowed with. Older backups carry no certificate, so their "Allow"s must be given again;
     * their approvals for the removed lookup provider (keys without the Directory prefix) are skipped.
     */
    @Synchronized
    fun importApprovals(json: String) {
        // A restore while hiding never grants (or takes back) access; see the class comment.
        if (hidingNow()) return
        runCatching {
            val o = JSONObject(json)
            for (key in o.keys()) {
                if (!key.startsWith(DIR_PREFIX)) continue
                val entry = o.optJSONObject(key)
                val a = LookupApproval.entries.firstOrNull { it.name == (entry?.optString("approval") ?: o.optString(key)) } ?: continue
                val pkg = key.removePrefix(DIR_PREFIX)
                val cert = entry?.optString("cert")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                when {
                    a == LookupApproval.DENIED -> store(pkg, a, null)
                    a == LookupApproval.ALLOWED && cert != null && signedWith(pkg, cert) -> store(pkg, a, cert)
                }
            }
        }
    }

    private companion object {
        const val K_LOG = "log"
        const val K_DIRECTORY = "directory"
        const val K_DIR_APPROVALS = "directory_approvals"
        const val K_DIR_CERTS = "directory_approval_certs"
        const val K_ASKED = "asked_at"
        const val ASKED_PREFIX = "d:"
        const val DIR_PREFIX = "directory:"
        const val MAX_LOG = 200

        /** The lookup provider's prompt times ("p:" + package). */
        const val LEGACY_ASKED_PREFIX = "p:"

        /** The lookup provider's switch, approvals and certificates. */
        val LEGACY_KEYS = listOf("enabled", "approvals", "approval_certs")
    }
}
