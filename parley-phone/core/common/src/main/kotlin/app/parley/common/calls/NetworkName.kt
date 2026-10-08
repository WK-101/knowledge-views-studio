package app.parley.common.calls

import app.parley.common.Codecs
import app.parley.common.PhoneIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
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
 * Nothing is kept or shown after a call unless Settings › Calls › Answering › "Remember names from the network" is
 * on (off by default, also for phones that kept names before the setting existed). Saved names always win; under a
 * saved name the network's shows only as a quiet second line, and only when it is a different name ([underSaved]).
 * A private contact's number gets none written ([keep]), loses one kept while it was unknown when it becomes private
 * (the vault tells the store), and never shows one in place of a name ([mayShow]).
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
        val name = visible(raw).replace(SPACES, " ").trim().trim { it in EDGE }.trim()
        if (name.isEmpty() || name.none { it.isLetter() }) return null
        if (name.count { it.isDigit() } > MAX_DIGITS) return null
        if (fold(name) in PLACEHOLDERS || cityAndState(name)) return null
        return if (name.length <= MAX_LENGTH) name else name.take(MAX_LENGTH - 1).trimEnd() + "…"
    }

    /**
     * [raw] without what doesn't show: direction overrides and isolates (a spoofed name could turn the rest of a row
     * around), zero-width and other format characters are dropped; control characters and other spaces become spaces.
     */
    private fun visible(raw: CharSequence): String = buildString {
        raw.toString().codePoints().forEach { cp ->
            when {
                Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> append(' ')
                else -> appendCodePoint(cp)
            }
        }
    }

    /**
     * A US network's stand-in for a mobile caller's name: the city and state it was registered in, in capitals
     * ("NEW YORK NY", "ST. LOUIS MO"). A name in capitals that ends in a state's two letters is taken for one.
     */
    private fun cityAndState(name: String): Boolean {
        if (name.any { it.isLowerCase() || it.isDigit() }) return false
        val words = name.split(' ').filter { it.isNotEmpty() }
        return words.size >= 2 && words.last() in US_STATES
    }

    /**
     * The line a name is kept under: [number] read with the country of the SIM the call came in on ([simRegion]),
     * so "0612 345678" on a French SIM is the same line as "+33 6 12 34 56 78" from a contact; the number as it is
     * when that country isn't known or the number can't be read with it (the phone's country then applies).
     */
    fun line(number: String, simRegion: String?): String =
        simRegion?.takeIf { it.isNotBlank() }?.let { PhoneIdentity.e164(number, it) } ?: number

    /**
     * Whether the network's name may show in place of a name for a number: names from the network are remembered
     * ([enabled]), nobody saved the number ([saved]), and it is known not to be a private contact's ([private]). Null
     * [private]: the private contacts couldn't be checked, which counts as private.
     */
    fun mayShow(enabled: Boolean, saved: Boolean, private: Boolean?): Boolean = enabled && !saved && private == false

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

    /**
     * [history] (newest first) with [seen] put back (an undone delete): a name kept meanwhile keeps the newer of the two
     * SIMs and the wider span of times. At most [HISTORY] names are kept.
     */
    fun merge(history: List<NetworkNameSeen>, seen: NetworkNameSeen): List<NetworkNameSeen> {
        val earlier = history.firstOrNull { same(it.name, seen.name) }
        val merged = if (earlier == null) {
            seen
        } else {
            (if (earlier.lastSeen >= seen.lastSeen) earlier else seen)
                .copy(firstSeen = minOf(earlier.firstSeen, seen.firstSeen), lastSeen = maxOf(earlier.lastSeen, seen.lastSeen))
        }
        return (listOf(merged) + history.filterNot { same(it.name, seen.name) }).sortedByDescending { it.lastSeen }.take(HISTORY)
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
     * A name is kept only while names from the network are remembered ([enabled]): for a number nobody saved, and for a
     * contact's or an archived contact's number too, whose name may then show under the saved one. A private contact's
     * number gets nothing written, and loses any name still kept for it (whether remembering is on or not). Null
     * [private]: the private contacts couldn't be checked, which counts as private.
     */
    fun keep(enabled: Boolean, private: Boolean?): Keep = when {
        private != false -> Keep.FORGET
        !enabled -> Keep.SKIP
        else -> Keep.RECORD
    }

    /**
     * Whether [network] is a different name from [saved], not just the same one written otherwise: case, spacing,
     * accents and punctuation aside, in any order, and not merely part of it ("Sharma"), its initials ("R Sharma",
     * "RS") or a shortening of its words ("Rahul S."). "Rahul Kumar" for a contact saved as "Rahul Sharma" differs.
     */
    fun differs(saved: String, network: String): Boolean {
        val s = words(saved)
        val n = words(network)
        if (n.isEmpty() || s.isEmpty()) return false
        val sJoined = s.joinToString("")
        val nJoined = n.joinToString("")
        if (sJoined == nJoined || sJoined.contains(nJoined)) return false
        // "RS" for "Rahul Sharma": the saved name's initials run together.
        if (n.size == 1 && n[0].length > 1 && n[0] == s.joinToString("") { it.take(1) }) return false
        // Every word of the network's name is a saved word, or the start of one (an initial, "Sh"), each used once.
        val left = s.toMutableList()
        for (w in n.sortedByDescending { it.length }) {
            val hit = left.firstOrNull { it == w } ?: left.firstOrNull { it.startsWith(w) } ?: return true
            left.remove(hit)
        }
        return false
    }

    /** What decides whether the network's name may show under a saved name on a screen. */
    data class Gate(
        /** "Remember names from the network" is on. */
        val enabled: Boolean,
        /** The caller is a private contact. */
        val privateContact: Boolean = false,
        /** Private contacts' names are hidden here (Hide private contacts, a duress session, a locked vault). */
        val privateNamesHidden: Boolean = false,
        /** The lock-screen rule hides the caller's name here (Initials or Nothing while the phone is locked). */
        val nameMasked: Boolean = false,
    ) {
        /** The saved name itself doesn't show here, or names from the network aren't wanted. */
        val holdsBack: Boolean get() = !enabled || nameMasked || (privateContact && privateNamesHidden)
    }

    /**
     * The network's name to show as a small second line under [savedName] ("Network: Rahul S."), or null: only while
     * names are remembered, where the saved name itself shows in full (never for a hidden private contact or a masked
     * name), and only when it is a different name ([differs]). It says what the network sent, not who is calling.
     */
    fun underSaved(savedName: String?, networkName: String?, gate: Gate): String? {
        if (gate.holdsBack) return null
        if (savedName.isNullOrBlank() || networkName.isNullOrBlank()) return null
        return networkName.takeIf { differs(savedName, it) }
    }

    /** [s] as words to compare: accents folded, lower case, letters and digits only. */
    private fun words(s: String): List<String> =
        fold(Normalizer.normalize(s, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() })
            .split(' ').filter { it.isNotEmpty() }

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
    private const val EDGE = "-–—.,;:*·/\\|\"'()[]<>?!"

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

    /** The states, district and territories a US network's "CITY ST" stand-in ends in. */
    private val US_STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME",
        "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA",
        "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC", "PR", "GU", "VI", "AS", "MP",
    )
}
