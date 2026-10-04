package app.parley.common.recall

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.circle.Promises
import app.parley.common.memory.MemoryHint
import app.parley.common.people.ContactListSearch
import app.parley.common.people.ContactSearch
import java.time.ZoneId

/** Where a Recall result comes from: the groups, in the order they are shown. */
enum class RecallSource {
    /** A contact found by what is left of the query ("plumber" of "plumber march"). */
    CONTACT,

    /** A call in Parley's copy of calls (Android's call log, the archive, calls with private contacts). */
    CALL,

    /** An open promise (a `[ ]` line of a note). */
    PROMISE,

    /** A note for calls (pinned) or a Circle note (a chat or visit). */
    NOTE,

    /** A note written after a call. */
    CALL_NOTE,

    /** A number a chat was opened with from Parley. */
    MESSAGED,

    /** A contact deleted in Parley (History & undo). */
    DELETED,

    /** A private contact deleted in Parley (shown only while private contacts may be). */
    DELETED_PRIVATE,

    /** A contact or number the daily snapshots still have and the address book no longer does. */
    SNAPSHOT,

    /** What number memory has on a number typed whole (the names calls showed then, how many calls). */
    REMEMBERED,
}

/**
 * Everything Recall searches beyond the contact list, gathered once while the search is open (the stores are read off
 * the main thread, opened in memory only, and dropped when the search closes). Built by the app from its stores; pure
 * from here on.
 */
class RecallCorpus(
    /** The contacts as the Contacts search has them (private contacts' docs only while they may be searched). */
    val contacts: List<ContactListSearch.Entry> = emptyList(),
    /** Calls held in memory, newest first. Older archived calls are read by date range ([RecallEngine.callMatcher]). */
    val calls: List<CallEntry> = emptyList(),
    val notes: List<Note> = emptyList(),
    val deleted: List<Gone> = emptyList(),
    val snapshots: List<Gone> = emptyList(),
    val messaged: List<Messaged> = emptyList(),
    /** The phone's country, for numbers written nationally. */
    val region: String? = null,
) {
    /** A note: pinned on a contact, a Circle note, or one written after a call (then [number] and no [ownerKey]). */
    data class Note(
        val kind: Kind,
        val text: String,
        /** Whose page it is on (a lookup key, or a private contact's key); null for a call note. */
        val ownerKey: String?,
        val ownerName: String?,
        /** The call note's number. */
        val number: String?,
        /** When (a Circle note, a call note); 0 for a pinned note, which has no date. */
        val at: Long,
        val private: Boolean = false,
        /** A row id the app can open it by (a call note's id). */
        val id: Long = 0,
    ) {
        enum class Kind { PINNED, CIRCLE, CALL }
    }

    /** A contact that is gone: deleted in Parley, or only in the snapshots now. */
    data class Gone(
        val name: String,
        val numbers: List<String>,
        val at: Long,
        /** What restores or opens it: the History & undo entry id, the deleted private contact's file, the snapshot time. */
        val ref: String,
        val private: Boolean = false,
    )

    data class Messaged(val number: String, val label: String, val at: Long)
}

/**
 * One result. [title] and [detail] are what the row shows, with the parts that matched ([titleMarks], [detailMarks],
 * index ranges into them). Calls and gone contacts leave their dates and kinds to the row to say.
 */
data class RecallHit(
    val source: RecallSource,
    val title: String,
    val detail: String = "",
    val at: Long = 0,
    val number: String? = null,
    /** A listed contact's id (negative for a private contact). */
    val contactId: Long? = null,
    /** What the row opens or restores (see [RecallCorpus.Gone.ref], [RecallCorpus.Note.ownerKey]). */
    val ref: String? = null,
    val callType: CallType? = null,
    val durationSec: Long = 0,
    /** The field of a contact that explains the match ("Matched: address"). */
    val field: ContactSearch.Field? = null,
    val memory: MemoryHint? = null,
    val private: Boolean = false,
    val titleMarks: List<IntRange> = emptyList(),
    val detailMarks: List<IntRange> = emptyList(),
    /** Higher is better: matched by name over another field over the date alone (see [RecallEngine]). */
    val score: Int = 0,
)

/** A group of results: the first [hits] of [total] found (more were found than kept when [total] is larger). */
data class RecallGroup(val source: RecallSource, val hits: List<RecallHit>, val total: Int)

/** What Recall found, by group, in the order they are shown. */
data class RecallResult(val query: RecallQuery, val groups: List<RecallGroup>) {
    val isEmpty: Boolean get() = groups.all { it.hits.isEmpty() }
    val count: Int get() = groups.sumOf { it.total }

    companion object {
        fun empty(query: RecallQuery) = RecallResult(query, emptyList())
    }
}

/** Ranking and merging of results (pure, tested on its own). */
object RecallRanking {
    /** Matched by the name or number, by another field, or by the date or kind of call alone. */
    const val BY_NAME = 30
    const val BY_FIELD = 20
    const val BY_DATE = 10

    /** An open promise or a contact asked for by name ranks a little above the rest of its kind. */
    const val BONUS = 5

    /** Within a group: best match first, then the newest. */
    val ORDER: Comparator<RecallHit> = compareByDescending<RecallHit> { it.score }.thenByDescending { it.at }

    /**
     * The groups from every source's [hits], each sorted by [ORDER] and cut to [limit] (its total kept). A contact
     * that is both deleted and in the snapshots shows once, as deleted (that is where it can be restored), and a
     * snapshot line for a number a deleted contact had is left out too. Empty groups are dropped; the order is
     * [RecallSource]'s, except that calls lead when the query asks only for calls.
     */
    fun merge(query: RecallQuery, hits: Map<RecallSource, List<RecallHit>>, limit: Int, region: String? = null): RecallResult {
        val deleted = hits[RecallSource.DELETED].orEmpty() + hits[RecallSource.DELETED_PRIVATE].orEmpty()
        val goneNames = deleted.mapTo(HashSet()) { ContactSearch.fold(it.title) }
        val goneNumbers = app.parley.common.PhoneIdentity.LineSet(deleted.mapNotNull { it.number }, region)
        val groups = RecallSource.entries.mapNotNull { source ->
            var list = hits[source].orEmpty()
            if (source == RecallSource.SNAPSHOT && deleted.isNotEmpty()) {
                list = list.filterNot { ContactSearch.fold(it.title) in goneNames || (it.number != null && it.number in goneNumbers) }
            }
            if (list.isEmpty()) null else RecallGroup(source, list.sortedWith(ORDER).take(limit).map { marked(query, it) }, list.size)
        }
        val ordered = if (query.onlyCalls) groups.sortedBy { if (it.source == RecallSource.CALL) 0 else 1 } else groups
        return RecallResult(query, ordered)
    }

    /** [hit] with the parts of its title and detail that hold the query's words marked (only for the hits kept). */
    private fun marked(query: RecallQuery, hit: RecallHit): RecallHit =
        hit.copy(titleMarks = RecallText.marks(query.search, hit.title), detailMarks = RecallText.marks(query.search, hit.detail))
}

/**
 * Recall's search over a [RecallCorpus] for a [RecallQuery]: every source with the same matching as the Contacts
 * search ([ContactSearch.match]: every word in some field, a word of digits in a number, accents and case ignored),
 * filtered by the query's dates and kinds of call. Pure and bounded: each source keeps at most [limit] results (with
 * their total), so a search over 10,000 contacts and 50,000 calls stays a scan of prepared strings.
 */
class RecallEngine(private val corpus: RecallCorpus, private val zone: ZoneId = ZoneId.systemDefault()) {
    /** Docs for the corpus's own items, prepared once: notes, gone contacts, chats. */
    private val noteDocs = corpus.notes.mapIndexed { i, n ->
        ContactSearch.Builder(i.toLong(), corpus.region).apply {
            note(n.text)
            shownName(n.ownerName)
            number(n.number)
        }.build()
    }
    private val deletedDocs = corpus.deleted.map(::goneDoc)
    private val snapshotDocs = corpus.snapshots.map(::goneDoc)
    private val messagedDocs = corpus.messaged.mapIndexed { i, m ->
        ContactSearch.Builder(i.toLong(), corpus.region).apply {
            number(m.number)
            shownName(m.label)
        }.build()
    }

    /**
     * What each call's number and shown name were found to be, kept across queries (a keystroke names only numbers
     * not seen before). Concurrent: a search still finishing may overlap the next keystroke's.
     */
    private val named = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val callerDocs = java.util.concurrent.ConcurrentHashMap<String, ContactSearch.Doc>()

    private fun callerDoc(number: String, shown: String?): ContactSearch.Doc = callerDocs.getOrPut(number + "\u0000" + shown.orEmpty()) {
        ContactSearch.Builder(0, corpus.region).apply {
            shownName(shown)
            number(number)
        }.build()
    }

    /** Contact docs by list id, for naming and matching calls. */
    private val contactsById: Map<Long, ContactListSearch.Entry> = corpus.contacts.associateBy { it.contact.id }

    private fun goneDoc(g: RecallCorpus.Gone) = ContactSearch.Builder(0, corpus.region).apply {
        name(g.name)
        g.numbers.forEach { number(it) }
    }.build()

    /**
     * Searches every source but calls older than the corpus holds ([callMatcher] matches those, read page by page). [contactOf]
     * names a call's number (a listed contact's id, or null), and is asked once per distinct number.
     */
    fun search(query: RecallQuery, contactOf: (String) -> Long?, limit: Int = DEFAULT_LIMIT): Map<RecallSource, List<RecallHit>> {
        if (query.isEmpty) return emptyMap()
        val out = HashMap<RecallSource, List<RecallHit>>()
        out[RecallSource.CALL] = CallMatcher(query, contactOf).collect(corpus.calls, limit)
        if (query.onlyCalls) return out
        val q = query.search
        if (query.interpreted && !q.isEmpty) out[RecallSource.CONTACT] = contacts(q, limit)
        notes(query, out)
        out[RecallSource.MESSAGED] = corpus.messaged.indices.mapNotNull { i ->
            val m = corpus.messaged[i]
            if (!query.inDates(m.at, zone)) return@mapNotNull null
            val field = ContactSearch.match(q, messagedDocs[i]) ?: return@mapNotNull null
            RecallHit(RecallSource.MESSAGED, m.number, m.label, m.at, m.number, score = scoreOf(q, field))
        }
        val gone = gone(query, corpus.deleted, deletedDocs)
        out[RecallSource.DELETED] = gone.filterNot { it.private }
        out[RecallSource.DELETED_PRIVATE] = gone.filter { it.private }.map { it.copy(source = RecallSource.DELETED_PRIVATE) }
        out[RecallSource.SNAPSHOT] = gone(query, corpus.snapshots, snapshotDocs).map { it.copy(source = RecallSource.SNAPSHOT) }
        return out
    }

    /** Notes, promises and notes after calls that answer [query], into [out] by source. */
    private fun notes(query: RecallQuery, out: MutableMap<RecallSource, List<RecallHit>>) {
        val promises = ArrayList<RecallHit>()
        val notes = ArrayList<RecallHit>()
        val callNotes = ArrayList<RecallHit>()
        corpus.notes.forEachIndexed { i, n ->
            val hit = noteHit(query, n, noteDocs[i]) ?: return@forEachIndexed
            when (hit.source) {
                RecallSource.PROMISE -> promises += hit
                RecallSource.CALL_NOTE -> callNotes += hit
                else -> notes += hit
            }
        }
        out[RecallSource.PROMISE] = promises
        out[RecallSource.NOTE] = notes
        out[RecallSource.CALL_NOTE] = callNotes
    }

    /** [n] as a hit: a promise when an open one holds the words, else the note; null when it doesn't answer [query]. */
    private fun noteHit(query: RecallQuery, n: RecallCorpus.Note, doc: ContactSearch.Doc): RecallHit? {
        val q = query.search
        // A date asks for dated things (a pinned note has none); words alone find undated ones too.
        if (query.dates != null && (n.at <= 0 || !query.inDates(n.at, zone))) return null
        val field = ContactSearch.match(q, doc) ?: return null
        val score = when {
            q.isEmpty -> RecallRanking.BY_DATE
            field == ContactSearch.Field.NOTE -> RecallRanking.BY_FIELD
            else -> RecallRanking.BY_NAME
        }
        val title = n.ownerName ?: n.number.orEmpty()
        val promise = Promises.open(n.text).firstOrNull { p -> q.isEmpty || RecallText.containsAll(q, p.text) }
        if (promise != null) {
            return RecallHit(
                RecallSource.PROMISE, title, promise.text, n.at, n.number, ref = n.ownerKey, private = n.private, score = score + RecallRanking.BONUS,
            )
        }
        return RecallHit(
            if (n.kind == RecallCorpus.Note.Kind.CALL) RecallSource.CALL_NOTE else RecallSource.NOTE,
            title, RecallText.excerpt(q, Promises.preview(n.text)), n.at, n.number,
            ref = n.ownerKey ?: n.id.toString(), private = n.private, score = score,
        )
    }

    private fun contacts(q: ContactSearch.Query, limit: Int): List<RecallHit> =
        corpus.contacts.asSequence().mapNotNull { e ->
            val field = ContactSearch.match(q, e.doc, e.name) ?: return@mapNotNull null
            RecallHit(
                RecallSource.CONTACT, e.contact.displayName, contactId = e.contact.id, field = field.takeIf(ContactSearch::explains),
                private = e.contact.id < 0, score = scoreOf(q, field) + if (field == ContactSearch.Field.NAME) RecallRanking.BONUS else 0,
            )
        }.take(limit * 4).toList() // Enough to rank: the list itself has the rest.

    private fun gone(query: RecallQuery, items: List<RecallCorpus.Gone>, docs: List<ContactSearch.Doc>): List<RecallHit> {
        val q = query.search
        return items.indices.mapNotNull { i ->
            val g = items[i]
            if (!query.inDates(g.at, zone)) return@mapNotNull null
            val field = ContactSearch.match(q, docs[i]) ?: return@mapNotNull null
            RecallHit(
                RecallSource.DELETED, g.name, g.numbers.firstOrNull { RecallText.numberMatches(q, it) }.orEmpty(), g.at, g.numbers.firstOrNull(),
                ref = g.ref, private = g.private, score = scoreOf(q, field),
            )
        }
    }

    private fun scoreOf(q: ContactSearch.Query, field: ContactSearch.Field): Int = when {
        q.isEmpty -> RecallRanking.BY_DATE
        field == ContactSearch.Field.NAME || field == ContactSearch.Field.NUMBER -> RecallRanking.BY_NAME
        else -> RecallRanking.BY_FIELD
    }

    /**
     * Matches calls: the date and kind first (cheap), then the words against the caller: the contact the number
     * belongs to (every field, as the Contacts search), the name the call showed, and the number. Each distinct
     * number is named and matched once per query.
     */
    inner class CallMatcher(private val query: RecallQuery, private val contactOf: (String) -> Long?) {
        private val q = query.search
        private val from = query.dates?.startMillis(zone) ?: Long.MIN_VALUE
        private val until = query.dates?.endMillis(zone) ?: Long.MAX_VALUE
        private val callers = HashMap<String, RecallHit?>()

        /** [call] as a hit, or null when it doesn't answer the query. */
        fun hit(call: CallEntry): RecallHit? {
            if (call.date < from || call.date >= until) return null
            val types = query.callTypes
            if (types != null && call.type !in types) return null
            if (call.presentationHidden && !q.isEmpty) return null
            val who = callers.getOrPut(call.number + "\u0000" + call.cachedName.orEmpty()) { who(call) } ?: return null
            return who.copy(at = call.date, callType = call.type, durationSec = call.durationSec, ref = call.id.toString())
        }

        /** The caller's part of a hit, or null when the words aren't theirs. */
        private fun who(call: CallEntry): RecallHit? {
            val id = call.number.takeIf { it.isNotBlank() }?.let { n -> named.getOrPut(n) { contactOf(n) ?: NOBODY }.takeIf { it != NOBODY } }
            val entry = id?.let { contactsById[it] }
            val name = entry?.contact?.displayName ?: call.cachedName?.takeIf { it.isNotBlank() }
            val title = name ?: call.number
            if (q.isEmpty) return RecallHit(RecallSource.CALL, title, number = call.number, contactId = id, score = RecallRanking.BY_DATE)
            // The caller's contact by every field ("plumber" in the company or a note), else the name shown and the number.
            val field = entry?.let { ContactSearch.match(q, it.doc, it.name) }
                ?: ContactSearch.match(q, callerDoc(call.number, call.cachedName))
                ?: return null
            return RecallHit(
                RecallSource.CALL, title, number = call.number, contactId = id, field = field.takeIf(ContactSearch::explains),
                private = call.id < 0, score = scoreOf(q, field),
            )
        }

        /** The hits among [calls], at most [limit] (newest first, as given). */
        fun collect(calls: List<CallEntry>, limit: Int): List<RecallHit> {
            val out = ArrayList<RecallHit>()
            for (c in calls) {
                hit(c)?.let { out += it }
                if (out.size >= limit) break
            }
            return out
        }
    }

    /** A matcher for calls read later (the archive's older pages) with the same naming and matching as [search]. */
    fun callMatcher(query: RecallQuery, contactOf: (String) -> Long?): CallMatcher = CallMatcher(query, contactOf)

    companion object {
        /** [named]'s value for a number no contact has. */
        private const val NOBODY = Long.MIN_VALUE

        /** Results kept per group. */
        const val DEFAULT_LIMIT = 50
    }
}
