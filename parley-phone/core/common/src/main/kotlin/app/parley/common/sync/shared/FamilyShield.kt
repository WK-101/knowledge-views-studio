package app.parley.common.sync.shared

import app.parley.common.Hex
import app.parley.common.backup.RecordJson
import app.parley.common.crypto.Hkdf
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What a member said about a number they share with their label, from the strongest down. */
enum class ShieldKind(val code: String) {
    SCAM("s"),
    BLOCKED("b"),
    SPAM_LIKELY("p"),
    ;

    companion object {
        fun of(code: String?): ShieldKind? = entries.firstOrNull { it.code == code }
    }
}

/** What this phone does with a call whose number someone in the label warned about. Warn only is the default. */
enum class ShieldMode { WARN, SILENCE, BLOCK }

/** One verdict as it travels in a member's journal: the keyed hash of the number, its kind and when it was made. */
data class ShieldVerdict(val hash: String, val kind: ShieldKind, val at: Long)

/** One of this phone's own verdicts, before hashing: the number in E.164, never anything else about it. */
data class OwnVerdict(val e164: String, val kind: ShieldKind, val at: Long)

/** One of this phone's own verdicts as it keeps them: [fromRule] when a block rule gives it; [withdrawn] ones aren't shared. */
data class ShieldOwn(val e164: String, val kind: ShieldKind, val at: Long, val fromRule: Boolean, val withdrawn: Boolean = false)

/**
 * What the members of one label said about one hashed number: the strongest kind, how many said so, the latest, and
 * whether the label's anchor (who made it) is among them.
 */
data class ShieldMatch(val kind: ShieldKind, val members: Int, val at: Long, val anchor: Boolean = false)

/** A call's number matched a verdict shared in [label] (its title on this phone), handled with [mode]. */
data class FamilyHit(val label: String, val kind: ShieldKind, val members: Int, val mode: ShieldMode)

/**
 * The family spam shield (docs/SHARED_LABELS.md, "Family spam shield"): block and scam verdicts shared within a shared
 * label, inside each member's signed journal. Only a keyed hash of the number travels: HMAC-SHA256 under a key
 * derived from the label's key, cut to 128 bits. Someone without the label key can't tell which numbers they are,
 * even by trying every number; every member can (they hold the key, and phone numbers are few enough to try them all),
 * which is why only numbers someone blocked or called a scam are shared, never names or notes.
 */
object FamilyShield {
    /** Verdicts one member shares at most (the newest), so a journal stays small. */
    const val MAX_VERDICTS = 2_000
    private const val INFO = "parley/v1/family-shield"
    private const val HASH_BYTES = 16
    private val HASH = Regex("[0-9a-f]{32}")

    /** The hashing key of a label: its own, so a hash means nothing in another label or under another key. */
    fun key(labelKey: ByteArray, labelId: String): ByteArray = Hkdf.sha256(labelKey, labelId.toByteArray(Charsets.UTF_8), INFO)

    /** A number's keyed hash under [key], from its E.164 form; null for anything that isn't one. */
    fun hash(key: ByteArray, e164: String): String? {
        if (!isE164(e164)) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Hex.encode(mac.doFinal(e164.toByteArray(Charsets.UTF_8)), HASH_BYTES)
    }

    fun isE164(s: String): Boolean = s.length in 8..16 && s[0] == '+' && s.drop(1).all { it in '0'..'9' } && s[1] != '0'

    fun isHash(s: String): Boolean = HASH.matches(s)

    /**
     * What this phone writes into its journal for a label whose hashing key is [key]: each number once (its strongest
     * kind, its latest time), at most [MAX_VERDICTS] of the newest, in a fixed order so an unchanged list writes the
     * same bytes.
     */
    fun outgoing(key: ByteArray, own: List<OwnVerdict>): List<ShieldVerdict> =
        own.mapNotNull { v -> hash(key, v.e164)?.let { ShieldVerdict(it, v.kind, v.at) } }
            .groupBy { it.hash }
            .map { (h, vs) -> ShieldVerdict(h, vs.minOf { it.kind }, vs.maxOf { it.at }) }
            .sortedByDescending { it.at }.take(MAX_VERDICTS)
            .sortedBy { it.hash }

    /** A short digest of what was shared, so a phone knows when its journal must be written again ("" for nothing). */
    fun digest(verdicts: List<ShieldVerdict>?): String =
        if (verdicts == null) "" else RecordJson.sha256Hex(verdicts.joinToString("\n") { "${it.hash}|${it.kind.code}|${it.at}" }.toByteArray())

    /**
     * Every member's verdicts ([byMember]: member key hash → their list) as one map by hash: the strongest kind any
     * member gave, how many members said so, the latest time, and whether [anchorHex] (the label's anchor) said so. A
     * member counts once per number. [byMember] holds only the label's members now: someone who left or was removed,
     * or who held the label's key before it changed, isn't in it, so nothing they shared counts.
     */
    fun merge(byMember: Map<String, List<ShieldVerdict>>, anchorHex: String? = null): Map<String, ShieldMatch> {
        val out = HashMap<String, ShieldMatch>()
        for ((member, list) in byMember) {
            val anchor = anchorHex != null && member == anchorHex
            for (v in list.distinctBy { it.hash }) {
                val m = out[v.hash]
                out[v.hash] = if (m == null) {
                    ShieldMatch(v.kind, 1, v.at, anchor)
                } else {
                    ShieldMatch(minOf(m.kind, v.kind), m.members + 1, maxOf(m.at, v.at), m.anchor || anchor)
                }
            }
        }
        return out
    }

    /** Members who must agree before a label in Block mode blocks a number, unless its anchor said so. */
    const val BLOCK_VOICES = 2

    /**
     * What a label set to [mode] does with [match]. Block needs two voices: [BLOCK_VOICES] members, or the anchor, so
     * one member (or one stolen phone) can't make every phone in the family reject a number. With one voice it warns,
     * as Warn mode does. Warn and Silence act on one.
     */
    fun modeFor(mode: ShieldMode, match: ShieldMatch): ShieldMode =
        if (mode == ShieldMode.BLOCK && match.members < BLOCK_VOICES && !match.anchor) ShieldMode.WARN else mode

    /** Of several labels' hits for one call, the one that does most (the strongest mode, then the strongest kind). */
    fun strongest(hits: List<FamilyHit>): FamilyHit? = hits.maxWithOrNull(compareBy<FamilyHit> { it.mode }.thenByDescending { it.kind })

    /** What screening does with a call. Emergency numbers and saved contacts (private ones too) are never touched. */
    enum class Outcome { NONE, WARN, SILENCE, BLOCK }

    fun outcome(hit: FamilyHit?, isEmergency: Boolean, isContact: Boolean): Outcome = when {
        hit == null || isEmergency || isContact -> Outcome.NONE
        hit.mode == ShieldMode.WARN -> Outcome.WARN
        hit.mode == ShieldMode.SILENCE -> Outcome.SILENCE
        else -> Outcome.BLOCK
    }
}

/** This phone's own verdicts' bookkeeping: what block rules add and take away, marks and withdrawals. */
object FamilyShieldOwn {
    /**
     * [stored] with the block rules' numbers ([blocked], E.164) as they are now: a new one is added as blocked at
     * [now]; one no longer blocked goes, withdrawn or not; a number marked by hand stays as marked. When the list is
     * too long the oldest shared entries go first: a withdrawn one never does while its number is blocked, or the
     * rule would share it again.
     */
    fun withRules(stored: List<ShieldOwn>, blocked: Set<String>, now: Long): List<ShieldOwn> {
        val kept = stored.filter { !it.fromRule || it.e164 in blocked }
        val known = kept.map { it.e164 }.toSet()
        val added = blocked.filter { it !in known && FamilyShield.isE164(it) }.sorted().map { ShieldOwn(it, ShieldKind.BLOCKED, now, fromRule = true) }
        return trimmed(kept + added, MAX_OWN)
    }

    /** At most [max] entries: withdrawn ones are all kept; of the rest, the oldest go (the order is otherwise kept). */
    internal fun trimmed(list: List<ShieldOwn>, max: Int): List<ShieldOwn> {
        if (list.size <= max) return list
        val gone = list.withIndex().filter { !it.value.withdrawn }
            .sortedWith(compareBy({ it.value.at }, { it.index })).take(list.size - max).map { it.index }.toSet()
        return list.filterIndexed { i, _ -> i !in gone }
    }

    /** [e164] marked by hand as [kind] at [now]: shared again even when it was withdrawn. */
    fun mark(stored: List<ShieldOwn>, e164: String, kind: ShieldKind, now: Long): List<ShieldOwn> =
        stored.filter { it.e164 != e164 } + ShieldOwn(e164, kind, now, fromRule = false)

    /**
     * [e164] withdrawn. It is always remembered as a withdrawn rule entry, never just dropped: a number marked by hand
     * may also be blocked, and the next look at the block rules would share it again. Once the number isn't blocked
     * the entry goes with the next look.
     */
    fun withdraw(stored: List<ShieldOwn>, e164: String): List<ShieldOwn> {
        val hit = stored.lastOrNull { it.e164 == e164 } ?: return stored
        return stored.filter { it.e164 != e164 } + hit.copy(fromRule = true, withdrawn = true)
    }

    /**
     * [e164] about to be blocked with "Don't share" chosen on the Block question: remembered as a withdrawn rule entry
     * before the rule is written, so the next look at the block rules never shares it, not even once.
     */
    fun keepPrivate(stored: List<ShieldOwn>, e164: String, now: Long): List<ShieldOwn> =
        stored.filter { it.e164 != e164 } + ShieldOwn(e164, ShieldKind.BLOCKED, now, fromRule = true, withdrawn = true)

    /** Entries kept at most: own verdicts shared plus withdrawn ones. */
    private const val MAX_OWN = FamilyShield.MAX_VERDICTS * 2
}
