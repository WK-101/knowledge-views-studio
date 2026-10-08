package app.parley.common.calls

import app.parley.common.Codecs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * A name the network sent with a call (caller name presentation, as India's CNAP and similar services do), as Parley
 * remembers it for a number that isn't saved: the name, when it was first and last sent, and the SIM and country the
 * call came in on. The call log has no column for it, so without this it is gone once the call ends.
 */
@Serializable
data class NetworkNameSeen(
    @SerialName("n") val name: String,
    /** The first call that sent this name. */
    @SerialName("f") val firstSeen: Long,
    /** The latest call that sent it. */
    @SerialName("t") val lastSeen: Long,
    /** The phone account (SIM) of the latest call. */
    @SerialName("s") val accountId: String? = null,
    /** The country the number was read in then. */
    @SerialName("r") val region: String? = null,
)

/**
 * The network's caller name: which names are worth keeping, how a new one joins what was kept, and where it shows.
 * Saved names always win; a private contact's number never gets one (the stores check before writing).
 */
object NetworkName {
    /** `TelecomManager.PRESENTATION_ALLOWED`: the only presentation whose name may be shown. */
    const val PRESENTATION_ALLOWED = 1

    /** Names kept per number: the latest and the ones before it, so a changed name can be told. */
    const val HISTORY = 3

    /** Longer "names" are cut here (they're shown on one line). */
    const val MAX_LENGTH = 60

    /** A name with this many digits is a number written out, not a name. */
    private const val MAX_DIGITS = 6

    private val json = Codecs.tolerant

    fun encode(seen: NetworkNameSeen): String = json.encodeToString(NetworkNameSeen.serializer(), seen)

    fun decode(stored: String): NetworkNameSeen? = runCatching { json.decodeFromString(NetworkNameSeen.serializer(), stored) }.getOrNull()

    /**
     * The name worth keeping from what the network sent ([raw], with its [presentation]), or null: only an allowed
     * presentation, with letters in it, that isn't a placeholder ("Unknown", "WIRELESS CALLER", "Private") or the
     * number itself written out. Spaces are tidied and the name is cut to [MAX_LENGTH].
     */
    fun clean(raw: CharSequence?, presentation: Int): String? {
        if (presentation != PRESENTATION_ALLOWED || raw == null) return null
        val name = raw.toString().replace(SPACES, " ").trim().trim { it in EDGE }.trim()
        if (name.isEmpty() || name.none { it.isLetter() }) return null
        if (name.count { it.isDigit() } > MAX_DIGITS) return null
        if (fold(name) in PLACEHOLDERS) return null
        return if (name.length <= MAX_LENGTH) name else name.take(MAX_LENGTH - 1).trimEnd() + "…"
    }

    /** Whether two names are the same name (case and spacing aside). */
    fun same(a: String, b: String): Boolean = fold(a) == fold(b)

    /**
     * [history] (newest first) with [name] sent at [at]: the same name as one kept moves to the front with its new
     * time, SIM and country (and keeps its first time); a new name goes in front. At most [HISTORY] names are kept.
     */
    fun record(history: List<NetworkNameSeen>, name: String, at: Long, accountId: String?, region: String?): List<NetworkNameSeen> {
        val earlier = history.firstOrNull { same(it.name, name) }
        val seen = NetworkNameSeen(
            name = name,
            firstSeen = minOf(earlier?.firstSeen ?: at, at),
            lastSeen = maxOf(earlier?.lastSeen ?: at, at),
            accountId = accountId ?: earlier?.accountId,
            region = region ?: earlier?.region,
        )
        return (listOf(seen) + history.filterNot { same(it.name, name) }).sortedByDescending { it.lastSeen }.take(HISTORY)
    }

    /** The name the network sent last. */
    fun latest(history: List<NetworkNameSeen>): NetworkNameSeen? = history.maxByOrNull { it.lastSeen }

    /** The name it sent before the latest one, when the name changed; null when it never did. */
    fun before(history: List<NetworkNameSeen>): NetworkNameSeen? {
        val latest = latest(history) ?: return null
        return history.filterNot { same(it.name, latest.name) }.maxByOrNull { it.lastSeen }
    }

    /** What to do with a name the network sent for a number. */
    enum class Keep { RECORD, SKIP, FORGET }

    /**
     * A name is kept only for a number nobody saved ([saved]: a contact or an archived contact). A private contact's
     * number gets nothing written, and loses a name kept before it became private.
     */
    fun keep(saved: Boolean, private: Boolean): Keep = when {
        private -> Keep.FORGET
        saved -> Keep.SKIP
        else -> Keep.RECORD
    }

    /** Where a shown name comes from. */
    enum class Source { SAVED, NETWORK, NUMBER }

    data class Shown(val text: String, val source: Source)

    /**
     * What stands for a caller: their saved name (a contact, private or archived contact), else the network's name,
     * else the number; null when there's none of them (a hidden number).
     */
    fun shown(savedName: String?, networkName: String?, number: String?): Shown? = when {
        !savedName.isNullOrBlank() -> Shown(savedName, Source.SAVED)
        !networkName.isNullOrBlank() -> Shown(networkName, Source.NETWORK)
        !number.isNullOrBlank() -> Shown(number, Source.NUMBER)
        else -> null
    }

    /**
     * The network's name in a missed-call notification, which can show on the lock screen: only where the lock-screen
     * rule shows names in full ([LockScreenCaller.showsName]); with Initials or Nothing the number stays, as on the
     * call screen for an unknown caller.
     */
    fun inNotification(networkName: String?, lockScreen: LockScreenCaller): String? = networkName?.takeIf { it.isNotBlank() && lockScreen.showsName }

    /** Lower case, letters and digits only, single spaces: "WIRELESS  CALLER." and "Wireless caller" fold alike. */
    private fun fold(s: String): String =
        s.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(SPACES, " ").trim()

    private val SPACES = Regex("\\s+")
    private const val EDGE = "-–—.,;:*·/\\|\"'()[]<>"

    /** What networks and phones send in place of a name (folded, see [fold]). */
    private val PLACEHOLDERS = setOf(
        "unknown", "unknown caller", "unknown name", "unknown number", "name unknown", "unknown unknown",
        "wireless caller", "wireless", "cell phone", "cellphone", "mobile", "mobile caller", "mobile phone", "landline",
        "private", "private caller", "private number", "private name", "restricted", "restricted number",
        "anonymous", "anonymous caller", "unavailable", "name unavailable", "number unavailable", "not available",
        "out of area", "out of area caller", "no caller id", "no caller i d", "no name", "no number", "blocked", "blocked caller",
        "blocked number", "withheld", "number withheld", "caller id withheld", "payphone", "pay phone", "toll free",
        "toll free call", "caller", "incoming call", "na", "n a", "null", "none", "voicemail", "emergency",
        "spam", "spam risk", "scam likely", "potential spam", "suspected spam", "telemarketer",
    )
}
