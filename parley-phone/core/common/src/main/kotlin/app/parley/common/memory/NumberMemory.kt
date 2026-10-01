package app.parley.common.memory

import app.parley.common.NumberText
import app.parley.common.PhoneIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a remembered fact about a number comes from, best first (see [NumberMemory.rank]). */
@Serializable
enum class MemorySource {
    /** A contact deleted in Parley (History & undo, 30 days). */
    DELETED_CONTACT,

    /** A private contact deleted in Parley ("Deleted private contacts", sealed, 30 days). */
    DELETED_PRIVATE,

    /** A contact (or one of its numbers) that a daily snapshot still has and the address book no longer does. */
    SNAPSHOT,

    /** A note written after a call with this number. */
    CALL_NOTE,

    /** A note, moment or promise on someone else's page that mentions this number. */
    NOTE,

    /** The name the call history showed for this number back then. */
    ARCHIVE_NAME,

    /** On the To call list. */
    TO_CALL,

    /** A chat opened with this number from Parley. */
    MESSAGED,

    /** Calls with this number in the call history (archive included). */
    CALLS,
}

/**
 * One thing Parley remembers about a number, small enough to be sealed on its own in the number-memory index. It
 * never holds the number itself: the index keys it by a keyed hash of the number.
 */
@Serializable
data class MemoryHint(
    @SerialName("s") val source: MemorySource,
    /** The contact's name then (deleted, snapshot, call history), or whose page a note is on. */
    @SerialName("n") val name: String? = null,
    /** A short excerpt of a note, or the chat app's name. */
    @SerialName("e") val excerpt: String? = null,
    /** When: deleted, last seen in a snapshot, the note or the newest call. */
    @SerialName("t") val at: Long = 0,
    /** How many calls ([MemorySource.CALLS]). */
    @SerialName("c") val count: Int = 0,
    /** The oldest call ([MemorySource.CALLS]). */
    @SerialName("f") val since: Long = 0,
    /**
     * What the hint's action opens: the History & undo entry id, the deleted private contact's file, the Parley key of
     * the contact a note is on, or the snapshot's time.
     */
    @SerialName("r") val ref: String? = null,
    /** About a private contact: shown only while the vault is unlocked, and never in discreet mode. */
    @SerialName("p") val private: Boolean = false,
    /** A contact whose relations still name this deleted contact ("Ana"). */
    @SerialName("l") val relatedTo: String? = null,
)

/**
 * Number memory (I1, P12): what Parley knows offline about a number that isn't a contact, as one quiet line. The pure
 * part: building hints from what each store holds, and choosing the one to show.
 */
object NumberMemory {
    /** Where the line is shown; each place leaves out what it already shows. */
    enum class Place { CALL, POST_CALL, KEYPAD, HISTORY }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    fun encode(hint: MemoryHint): String = json.encodeToString(MemoryHint.serializer(), hint)

    fun decode(stored: String): MemoryHint? = runCatching { json.decodeFromString(MemoryHint.serializer(), stored) }.getOrNull()

    /**
     * The hints worth showing at [place], best first: deleted contacts, then snapshots, notes, the call history's old
     * name, To call, chats and plain call counts; within a kind, the newest. Private hints only when [privateAllowed]
     * (the vault is unlocked and discreet mode is off). The number history already lists its call notes, calls and the
     * last chat, so it doesn't repeat them.
     */
    fun rank(hints: List<MemoryHint>, place: Place, privateAllowed: Boolean): List<MemoryHint> =
        hints.asSequence()
            .filter { privateAllowed || !it.private }
            .filter { place != Place.HISTORY || it.source !in SHOWN_BY_HISTORY }
            .distinct()
            .sortedWith(compareBy<MemoryHint> { it.source.ordinal }.thenByDescending { it.at })
            .toList()

    fun best(hints: List<MemoryHint>, place: Place, privateAllowed: Boolean): MemoryHint? = rank(hints, place, privateAllowed).firstOrNull()

    private val SHOWN_BY_HISTORY = setOf(MemorySource.CALL_NOTE, MemorySource.CALLS, MemorySource.MESSAGED)

    // ---------------------------------------------------------------- Building hints

    /** A number with the hint it gets. */
    data class Entry(val number: String, val hint: MemoryHint)

    /** A contact Parley deleted, as History & undo keeps it. */
    data class Deleted(val id: Long, val key: String, val name: String, val numbers: List<String>, val at: Long)

    /** [relatedTo]: the name of a contact whose relations link to the deleted contact's key, if any. */
    fun deleted(entries: List<Deleted>, relatedTo: (key: String) -> String? = { null }): List<Entry> =
        entries.flatMap { d ->
            val related = relatedTo(d.key)
            d.numbers.filter { it.isNotBlank() }.distinct().map { n ->
                Entry(n, MemoryHint(MemorySource.DELETED_CONTACT, name = d.name, at = d.at, ref = d.id.toString(), relatedTo = related))
            }
        }

    /** A person as one snapshot had them. */
    data class Person(val name: String, val numbers: List<String>)

    /** One daily snapshot: contact key → person. */
    data class SnapshotPeople(val at: Long, val people: Map<String, Person>)

    /**
     * Numbers the snapshots had on a contact that the newest snapshot no longer has there (the contact was deleted,
     * or the number removed from it): "Was saved as Plumber Mike until 12 March". One hint per line, the most recent.
     */
    fun snapshotHints(snapshots: List<SnapshotPeople>, region: String?): List<Entry> {
        val sorted = snapshots.sortedBy { it.at }
        val latest = sorted.lastOrNull() ?: return emptyList()
        // (contact key, line) → when last seen, the name then and the number as written.
        val seen = HashMap<Pair<String, String>, Triple<Long, String, String>>()
        for (snap in sorted) {
            for ((key, person) in snap.people) {
                for (n in person.numbers) {
                    val line = PhoneIdentity.key(n, region).takeIf { it.isNotEmpty() } ?: continue
                    seen[key to line] = Triple(snap.at, person.name, n)
                }
            }
        }
        val stillThere = HashSet<Pair<String, String>>()
        latest.people.forEach { (key, p) -> p.numbers.forEach { n -> stillThere += key to PhoneIdentity.key(n, region) } }
        val byLine = HashMap<String, Entry>()
        for ((k, v) in seen) {
            if (k in stillThere) continue
            val (at, name, number) = v
            val prev = byLine[k.second]
            if (prev == null || prev.hint.at < at) {
                byLine[k.second] = Entry(number, MemoryHint(MemorySource.SNAPSHOT, name = name, at = at, ref = at.toString()))
            }
        }
        return byLine.values.toList()
    }

    /** One call from the history (system log or Parley's archive), with the name it showed then. */
    data class PastCall(val number: String, val date: Long, val name: String?)

    /**
     * Per line: how many calls and since when ([MemorySource.CALLS]), and the newest name the history showed for it
     * ([MemorySource.ARCHIVE_NAME]), "Showed as Plumber Mike in your calls in March 2024".
     */
    fun pastCalls(calls: List<PastCall>, region: String?): List<Entry> {
        val out = ArrayList<Entry>()
        calls.filter { it.number.isNotBlank() }.groupBy { PhoneIdentity.key(it.number, region) }.forEach { (line, list) ->
            if (line.isEmpty()) return@forEach
            val newest = list.maxBy { it.date }
            out += Entry(newest.number, MemoryHint(MemorySource.CALLS, count = list.size, since = list.minOf { it.date }, at = newest.date))
            list.filter { !it.name.isNullOrBlank() }.maxByOrNull { it.date }?.let { named ->
                out += Entry(named.number, MemoryHint(MemorySource.ARCHIVE_NAME, name = named.name!!.trim(), at = named.date))
            }
        }
        return out
    }

    /** A note (pinned note, logged moment or promise) on someone's page. [ownerKey] is their Parley key. */
    data class Note(val text: String, val ownerKey: String, val ownerName: String?, val at: Long, val private: Boolean)

    /** Every number a note mentions, with an excerpt: "In your note on Ana: 'Dr Lee's office'". */
    fun noteHints(notes: List<Note>, region: String?): List<Entry> =
        notes.flatMap { note ->
            mentions(note.text, region).map { (number, excerpt) ->
                val hint = MemoryHint(MemorySource.NOTE, name = note.ownerName, excerpt = excerpt, at = note.at, ref = note.ownerKey, private = note.private)
                Entry(number, hint)
            }
        }

    /** A note written after a call with [number] (its own number, not one it mentions). */
    fun callNote(number: String, text: String, at: Long): Entry? {
        val excerpt = firstLine(text) ?: return null
        return Entry(number, MemoryHint(MemorySource.CALL_NOTE, excerpt = excerpt, at = at))
    }

    /**
     * Phone numbers [text] mentions (whole numbers only: at least [MIN_DIGITS] digits, never a short code), each with
     * the words around it on its line, without the number: "Dr Lee's office: 020 7946 0000" gives "Dr Lee's office".
     */
    fun mentions(text: String, region: String?): List<Pair<String, String?>> =
        NumberText.find(text, region).filter { f -> f.e164 != null && f.raw.count { it.isDigit() } >= MIN_DIGITS }.map { f ->
            f.e164!! to excerpt(text, f.range)
        }

    /** The rest of the line around [range], or the note's first other line, trimmed to [EXCERPT_MAX] characters. */
    fun excerpt(text: String, range: IntRange): String? {
        val start = text.lastIndexOf('\n', range.first - 1).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', range.last + 1).let { if (it < 0) text.length else it }
        val around = (text.substring(start, range.first) + " " + text.substring(range.last + 1, end)).let(::tidy)
        if (around.isNotEmpty()) return clip(around)
        val other = text.lines().map(::tidy).firstOrNull { it.isNotEmpty() && NumberText.find(it, null).isEmpty() }
        return other?.let(::clip)
    }

    /** The first line with text, clipped (a call note's own summary). */
    fun firstLine(text: String): String? = text.lines().map(::tidy).firstOrNull { it.isNotEmpty() }?.let(::clip)

    // Promise boxes, list marks, separators and spare spaces around a mention.
    private fun tidy(s: String): String =
        s.replace(PROMISE_BOX, " ").replace(Regex("\\s+"), " ").trim().trim { it in TRIM }.trim()

    private fun clip(s: String): String = if (s.length <= EXCERPT_MAX) s else s.take(EXCERPT_MAX - 1).trimEnd() + "…"

    private val PROMISE_BOX = Regex("\\[[ xX]?]")
    private const val TRIM = ":-–—,;()/*·. \"'"
    private const val MIN_DIGITS = 7
    const val EXCERPT_MAX = 60
}
