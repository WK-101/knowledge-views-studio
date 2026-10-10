package app.parley.data.sync.shared

import app.parley.common.PhoneIdentity
import app.parley.common.catching
import app.parley.common.storage.DurableFiles
import app.parley.common.sync.shared.FamilyHit
import app.parley.common.sync.shared.FamilyShield
import app.parley.common.sync.shared.OwnVerdict
import app.parley.common.sync.shared.ShieldOwn
import app.parley.common.sync.shared.FamilyShieldOwn
import app.parley.common.sync.shared.SharedLabelFiles
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.sync.shared.ShieldKind
import app.parley.common.sync.shared.ShieldMatch
import app.parley.common.sync.shared.ShieldMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The family spam shield on this phone (docs/SHARED_LABELS.md, "Family spam shield"): this phone's own verdicts (one
 * sealed file next to the labels' states) and, in memory, every shielded label's verdicts from its other members, which
 * calls are matched against at ring time without reading anything.
 *
 * [blockedNumbers]: the numbers this phone blocks one by one (E.164), which it shares as "blocked" while they stay
 * blocked, unless withdrawn.
 */
class FamilyShieldStore(
    private val dir: File,
    private val sealer: StateSealer,
    private val blockedNumbers: suspend () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One shielded label as calls are matched against it: its hashing key and the members' verdicts by hash. */
    private class LabelIndex(val title: String, val mode: ShieldMode, val key: ByteArray, val matches: Map<String, ShieldMatch>)

    private val file get() = File(dir, OWN_FILE)
    private val mutex = Mutex()

    /** Null until the labels' states were read ([load], or [update] from [SharedLabels]). */
    @Volatile private var index: List<LabelIndex>? = null

    /**
     * Whether [index] holds every label: false while some label's state couldn't be opened (a process just started
     * for a ringing call, a key being upgraded), so the next [load] reads them again rather than matching nothing for
     * the life of the process.
     */
    @Volatile private var complete = false

    /** Rebuilt whenever the shared labels' states change, so a call only looks up hashes in memory. */
    fun update(states: List<SharedLabelState>) = set(states, complete = true)

    private fun set(states: List<SharedLabelState>, complete: Boolean) {
        index = states.filter { it.shieldOn && it.shieldIn.isNotEmpty() && SharedLabelMembership.syncs(it.membership) }.map { s ->
            LabelIndex(s.title, s.shieldMode, FamilyShield.key(s.key, s.labelId), FamilyShield.merge(s.shieldIn, SharedLabelFiles.keyHex(s.anchor)))
        }
        this.complete = complete
    }

    /**
     * Reads the labels' states (off the call path's main thread: it unseals them): at app start, and before a call is
     * screened until every state could be read once. Afterwards [update] keeps it current, and this does nothing.
     */
    suspend fun load() {
        if (index != null && complete) return
        withContext(Dispatchers.IO) {
            if (index != null && complete) return@withContext
            val read = SharedLabelStateStore(dir, sealer).read()
            // A label read meanwhile through [update] is newer than this read: only a still incomplete index is replaced.
            if (!complete) set(read.states, read.complete)
        }
    }

    /**
     * Whether a call may match anything: memory only, nothing is read here. Until every label's state was read, the
     * answer is maybe, so the call is screened (and [load] reads them then).
     */
    fun mayMatch(): Boolean {
        val labels = index
        return labels == null || !complete || labels.isNotEmpty()
    }

    /** The strongest hit for [number] (read with [region]) among the shielded labels, or null. Memory only. */
    fun match(number: String, region: String?): FamilyHit? {
        val labels = index ?: return null
        if (labels.isEmpty()) return null
        val e164 = canonical(number, region) ?: return null
        val hits = labels.mapNotNull { l ->
            FamilyShield.hash(l.key, e164)?.let { l.matches[it] }?.let { m -> FamilyHit(l.title, m.kind, m.members, FamilyShield.modeFor(l.mode, m)) }
        }
        return FamilyShield.strongest(hits)
    }

    // ---------------------------------------------------------------- this phone's own verdicts

    /**
     * The stored own verdicts: empty when there are none yet (or the file is damaged), null when it is there but can't
     * be opened now (the Keystore busy). Null is never written over: it holds the "Don't share" and withdrawn entries.
     */
    private fun read(): List<ShieldOwn>? {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val opened = runCatching { sealer.open(text) }.getOrNull() ?: return null
        return runCatching {
            val a = JSONArray(opened)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val kind = ShieldKind.of(o.optString("k")) ?: return@mapNotNull null
                ShieldOwn(o.getString("n"), kind, o.optLong("at"), o.optBoolean("r"), o.optBoolean("w"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<ShieldOwn>): Boolean {
        val json = JSONArray().apply {
            list.forEach { put(JSONObject().put("n", it.e164).put("k", it.kind.code).put("at", it.at).put("r", it.fromRule).put("w", it.withdrawn)) }
        }
        val sealed = sealer.seal(json.toString()) ?: return false
        return DurableFiles.writeText(file, sealed)
    }

    /**
     * Numbers blocked here with "Don't share" until their block rule is seen and the choice is stored: the rule is
     * written just after the choice, and the choice may not reach storage at once (the Keystore busy). Kept in memory
     * so that, meanwhile, no look at the rules shares them.
     */
    private val pendingPrivate = LinkedHashSet<String>()

    /** [stored] with every pending "Don't share" choice in it. */
    private fun withPending(stored: List<ShieldOwn>): List<ShieldOwn> = pendingPrivate.fold(stored) { list, e164 ->
        if (list.any { it.e164 == e164 && it.withdrawn }) list else FamilyShieldOwn.keepPrivate(list, e164, clock())
    }

    /**
     * The own verdicts with the block rules as they are now: a number blocked since is added, one unblocked goes. Null
     * while the stored ones can't be opened: nothing is shared then, and nothing written over them.
     */
    private suspend fun refreshed(): List<ShieldOwn>? {
        val stored = read() ?: return null
        val now = withPending(stored)
        val rules = catching { blockedNumbers() }.getOrNull()?.toSet() ?: return now
        // A pending choice counts as blocked: its withdrawn entry stays until the rule it is about exists.
        val out = FamilyShieldOwn.withRules(now, rules + pendingPrivate, clock())
        if (out == stored || write(out)) pendingPrivate.removeAll(rules)
        return out
    }

    /** What this phone shares in every shielded label (withdrawn ones left out). */
    suspend fun outgoing(): List<OwnVerdict> = mutex.withLock {
        withContext(Dispatchers.IO) { refreshed().orEmpty().filter { !it.withdrawn }.map { OwnVerdict(it.e164, it.kind, it.at) } }
    }

    /** This phone's verdicts as the shield page lists them, newest first, withdrawn ones out. */
    suspend fun mine(): List<ShieldOwn> =
        mutex.withLock { withContext(Dispatchers.IO) { refreshed().orEmpty().filter { !it.withdrawn }.sortedByDescending { it.at } } }

    /** Marks [number] as [kind] ("It's a scam"), shared from the next run; false when it isn't a full number or can't be stored. */
    suspend fun mark(number: String, region: String?, kind: ShieldKind): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val e164 = canonical(number, region) ?: return@withContext false
            pendingPrivate -= e164
            write(FamilyShieldOwn.mark(refreshed() ?: return@withContext false, e164, kind, clock()))
        }
    }

    /** Stops sharing [e164] (the number stays blocked here when it was). */
    suspend fun withdraw(e164: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { write(FamilyShieldOwn.withdraw(refreshed() ?: return@withContext false, e164)) }
    }

    /**
     * [number] is being blocked with "Don't share": never shared, though its block rule is. False when it isn't a full
     * number, or when the choice couldn't be stored yet: it is still kept out of what is shared, and stored with the
     * next write that works.
     */
    suspend fun keepPrivate(number: String, region: String?): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val e164 = canonical(number, region) ?: return@withContext false
            pendingPrivate += e164
            write(withPending(read() ?: return@withContext false))
        }
    }

    companion object {
        private const val OWN_FILE = "shield-own.sealed"

        /** The one form a number is hashed in on every phone: E.164, with legacy spellings of a line made one. */
        fun canonical(number: String, region: String?): String? =
            PhoneIdentity.e164(number, region)?.let(PhoneIdentity::canonicalE164)?.takeIf(FamilyShield::isE164)
    }
}
