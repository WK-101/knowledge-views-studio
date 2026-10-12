package app.parley.common.people

import app.parley.common.Codecs
import app.parley.common.catching
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a saved number came from, as far as Parley can tell. */
@Serializable
enum class NumberOrigin(
    /** A number from here, recently added, makes the call screen say so (you didn't type or confirm it yourself). */
    val warns: Boolean,
) {
    /** Typed or kept in Parley's editor. */
    YOU(false),

    /** A signed card update from the person, which Parley checked. */
    CARD(false),

    /** Put back from a backup, History & undo, a snapshot or the archive. */
    RESTORED(false),

    /** Filled in from pasted text. */
    PASTED(true),

    /** Filled in from a scanned code. */
    SCANNED(true),

    /** Imported from a file or the SIM. */
    IMPORTED(true),

    /** Another app opened Parley's editor with it ("Add to contacts" from a message or a web page). */
    HANDED_OVER(true),

    /** A shared label's member sent it. */
    SHARED_LABEL(true),

    /** Another app on this phone changed the contact. */
    OTHER_APP(true),

    /** The account's sync brought it (the web, another phone). */
    SYNC(true),

    /** Changed outside Parley, and Android doesn't say how. Never warns: an unknown origin isn't a reason to worry. */
    UNKNOWN(false),
}

/** When a number came onto a contact and from where; [older] is the number it replaced, when one went in the same change. */
@Serializable
data class NumberStamp(
    @SerialName("t") val since: Long,
    @SerialName("o") val origin: NumberOrigin,
    /** The contact's lookup key; empty until a sweep sees which contact a bulk write put it on. */
    @SerialName("c") val contact: String = "",
    @SerialName("p") val older: String? = null,
)

/** A number Parley has seen on a contact: the contact's lookup key (empty: written, not seen yet) and the number as saved. */
@Serializable
data class SeenNumber(@SerialName("c") val contact: String, @SerialName("n") val number: String)

/**
 * The number passport: every number seen on the address book's contacts ([known], by line key), and for each one that
 * arrived after Parley started keeping track ([since]) when and from where. Numbers already there at the start have
 * no stamp: where they came from is unknown, so they never warn.
 */
@Serializable
data class PassportState(
    @SerialName("s") val since: Long? = null,
    @SerialName("k") val known: Map<String, SeenNumber> = emptyMap(),
    @SerialName("m") val stamps: Map<String, NumberStamp> = emptyMap(),
    /** When the address book was last looked at ([NumberPassport.sweep]). */
    @SerialName("l") val looked: Long? = null,
)

/** One number of a contact: its line key ([app.parley.common.PhoneIdentity.key]) and the number as saved. */
data class PassportLine(val key: String, val number: String)

/**
 * A call from a saved contact's fresh number, for the call screen: where the number came from, since when, and the
 * number to offer instead ([older], null when there is none). It names nobody, so it can show on the lock screen.
 */
data class FreshNumber(val origin: NumberOrigin, val since: Long, val older: String?)

/** How a sweep attributes a contact's new numbers: [origin], and when the contact changed ([at], null when Android doesn't say). */
data class Attribution(val origin: NumberOrigin, val at: Long?)

/**
 * The fresh-number warning ("number passport"). A common scam step is to get a saved number changed ("our bank has a
 * new number", "Mum's new phone"), or for another app to change it; the scammer's calls then show a trusted name. So
 * Parley records when each number came onto a contact and from where, and when a saved contact calls from a number
 * added recently by something other than you, the call screen says so calmly and offers their older number.
 *
 * Pure: the app reads the address book and Parley's own writes, this keeps the record and decides.
 */
object NumberPassport {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A number this recent (by its stamp) is "fresh". */
    const val FRESH_MS = 14 * DAY_MS

    /** After this many calls with the number since it arrived, the warning has done its job and stays quiet. */
    const val QUIET_AFTER_CALLS = 3

    /** Stamps kept at most (the newest); the numbers on the contacts are far fewer. */
    const val MAX_STAMPS = 5000

    /** More contacts than this changing at once is an account being added or restored elsewhere, not one number: no warning. */
    const val MASS_CHANGE = 20

    private val json = Codecs.tolerant

    fun encode(state: PassportState): String = json.encodeToString(PassportState.serializer(), state)

    /** A document that can't be read starts again from nothing: no stamp, so no warning (never a false one). */
    fun decode(stored: String?): PassportState =
        stored?.takeIf { it.isNotBlank() }?.let { catching { json.decodeFromString(PassportState.serializer(), it) }.getOrNull() } ?: PassportState()

    /**
     * Parley's editor saved [contact]: numbers in [after] but not [before] are stamped with [originOf] (the editor's
     * "you", or where the details were filled in from); a number that went in the same save is the older one.
     */
    fun saved(
        state: PassportState,
        contact: String,
        before: List<PassportLine>,
        after: List<PassportLine>,
        now: Long,
        originOf: (PassportLine) -> NumberOrigin,
    ): PassportState {
        val beforeKeys = before.map { it.key }.toSet()
        val afterKeys = after.map { it.key }.toSet()
        val added = after.filter { it.key.isNotEmpty() && it.key !in beforeKeys }.distinctBy { it.key }
        val removed = before.filter { it.key.isNotEmpty() && it.key !in afterKeys }
        if (added.isEmpty() && removed.isEmpty()) return state
        val older = removed.firstOrNull()?.number
        val stamps = state.stamps + added.map { it.key to NumberStamp(now, originOf(it), contact, older) }
        val gone = removed.map { it.key }.filter { state.known[it]?.contact.let { c -> c == contact || c.isNullOrEmpty() } }
        val known = state.known - gone.toSet() + after.filter { it.key.isNotEmpty() }.map { it.key to SeenNumber(contact, it.number) }
        return state.copy(known = known, stamps = capped(stamps))
    }

    /**
     * Parley wrote [lines] in bulk (an import, a restore, a shared label) without knowing the contact yet: each number
     * not seen before gets [origin]; the next [sweep] fills in the contact. Numbers already seen keep what they have.
     */
    fun written(state: PassportState, lines: List<PassportLine>, origin: NumberOrigin, now: Long): PassportState {
        val new = lines.filter { it.key.isNotEmpty() && it.key !in state.known }.distinctBy { it.key }
        if (new.isEmpty()) return state
        return state.copy(
            known = state.known + new.map { it.key to SeenNumber("", it.number) },
            stamps = capped(state.stamps + new.map { it.key to NumberStamp(now, origin) }),
        )
    }

    /**
     * The contacts whose numbers a sweep of [current] (lookup key → numbers) must attribute: numbers not seen before and
     * not written by Parley. Empty before the first sweep, which only takes stock.
     */
    fun unattributed(state: PassportState, current: Map<String, List<PassportLine>>): Map<String, List<PassportLine>> {
        if (state.since == null) return emptyMap()
        return current.mapValues { (_, lines) -> lines.filter { it.key.isNotEmpty() && it.key !in state.known && it.key !in state.stamps } }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Takes stock of the address book ([current]: lookup key → numbers). The first time, every number is simply known
     * (no stamp). Afterwards a number not seen before is stamped: with what Parley wrote, else [origins] for its contact
     * (from [Attribution]; [NumberOrigin.UNKNOWN] when there is none or [MASS_CHANGE] contacts changed at once), and a
     * number the same contact lost meanwhile is its older one. Numbers no longer on any contact are forgotten, except
     * those Parley wrote since the last look: [current] may have been read before that write landed, and dropping its
     * stamp would leave the next sweep asking Android, which can't tell your own save from a sync.
     */
    fun sweep(state: PassportState, current: Map<String, List<PassportLine>>, origins: Map<String, Attribution>, now: Long): PassportState {
        val present = current.values.flatten().filter { it.key.isNotEmpty() }.map { it.key }.toSet()
        val listed = current.flatMap { (contact, lines) -> lines.filter { it.key.isNotEmpty() }.map { it.key to SeenNumber(contact, it.number) } }.toMap()
        if (state.since == null) return PassportState(since = now, known = listed, stamps = state.stamps.filterKeys { it in present }, looked = now)
        val massChange = origins.size > MASS_CHANGE
        val stamps = state.stamps.toMutableMap()
        for ((contact, lines) in current) {
            val keys = lines.map { it.key }.toSet()
            val lost = state.known.entries.firstOrNull { (k, seen) -> seen.contact == contact && k !in keys }?.value?.number
            for (line in lines) {
                val seen = state.known[line.key]
                if (line.key.isEmpty() || (seen != null && seen.contact.isNotEmpty())) continue
                val stamp = stamps[line.key]
                stamps[line.key] = if (stamp != null) {
                    stamp.copy(contact = contact, older = stamp.older ?: lost)
                } else {
                    val a = origins[contact]?.takeUnless { massChange }
                    NumberStamp(minOf(now, a?.at ?: now), a?.origin ?: NumberOrigin.UNKNOWN, contact, lost)
                }
            }
        }
        return keepingNewer(state, listed, stamps, present).copy(looked = now)
    }

    /**
     * The sweep's [listed] numbers and [stamps] on those [present], plus the numbers stamped since the last look that
     * [present] doesn't show: a write the sweep's copy of the address book may predate.
     */
    private fun keepingNewer(state: PassportState, listed: Map<String, SeenNumber>, stamps: Map<String, NumberStamp>, present: Set<String>): PassportState {
        val lastLook = state.looked ?: Long.MIN_VALUE
        val newer = state.stamps.filter { (k, st) -> k !in present && st.since > lastLook }.keys
        val known = listed + newer.mapNotNull { k -> state.known[k]?.let { k to it } }
        return state.copy(known = known, stamps = capped(stamps.filterKeys { it in present || it in newer }))
    }

    /**
     * A number on a contact that the last look at the address book ([looked]) didn't see, attributed while it rings:
     * only a change Android dates after that look counts (an older one is the passport catching up, say after a new
     * SIM changed how national numbers read), and only an origin that warns. [changedSince] is how many contacts Android
     * dates after that look: more than [MASS_CHANGE] is an account arriving, which the sweep would mark unknown too.
     */
    fun unseen(attribution: Attribution?, looked: Long?, now: Long, changedSince: Int = 0): NumberStamp? {
        val at = attribution?.at ?: return null
        if (changedSince > MASS_CHANGE) return null
        if (looked == null || at <= looked || !attribution.origin.warns) return null
        return NumberStamp(minOf(at, now), attribution.origin)
    }

    /**
     * Whether a call from a number with [stamp] shows the fresh-number line: it arrived from somewhere that warns, less
     * than [FRESH_MS] ago, and there have been fewer than [QUIET_AFTER_CALLS] calls with it since ([callsSince]). No
     * stamp (unknown origin) never warns.
     */
    fun warns(stamp: NumberStamp?, now: Long, callsSince: Int): Boolean =
        stamp != null && stamp.origin.warns && now - stamp.since in 0..FRESH_MS && callsSince < QUIET_AFTER_CALLS

    /**
     * The number to offer instead ("Call their older number"): the one [stamp] replaced, else another of the contact's
     * numbers ([others], with their stamps) that isn't fresh itself; null when there is none.
     */
    fun olderNumber(stamp: NumberStamp, key: String, others: List<Pair<PassportLine, NumberStamp?>>, now: Long): String? =
        stamp.older?.takeIf { it.any(Char::isDigit) }
            ?: others.firstOrNull { (line, s) -> line.key != key && line.number.any(Char::isDigit) && !warns(s, now, 0) }?.first?.number

    /** How many whole days ago [since] was (0 today). */
    fun daysAgo(since: Long, now: Long): Int = ((now - since).coerceAtLeast(0) / DAY_MS).toInt()

    private fun capped(stamps: Map<String, NumberStamp>): Map<String, NumberStamp> =
        if (stamps.size <= MAX_STAMPS) stamps else stamps.entries.sortedByDescending { it.value.since }.take(MAX_STAMPS).associate { it.key to it.value }
}
