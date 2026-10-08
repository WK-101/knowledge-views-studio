package app.parley.data.recall

import android.util.Log
import app.parley.common.CallEntry
import app.parley.common.PhoneIdentity
import app.parley.common.calls.NetworkName
import app.parley.common.memory.MemoryHint
import app.parley.common.memory.MemorySource
import app.parley.common.memory.NumberMemory
import app.parley.common.people.ContactRef
import app.parley.common.recall.RecallCorpus
import app.parley.common.security.Concealed
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.history.CallHistory
import app.parley.data.security.Concealment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Recall searches besides the contact list, read from Parley's own stores when a search asks for it: notes for
 * calls, Circle notes (and the promises in them), notes written after calls, case files, contacts deleted in Parley,
 * the snapshots' gone contacts, archived contacts and the chats opened from Parley. Everything is opened in memory only, for as long as the
 * search is open; nothing is indexed or written.
 *
 * Private contacts' notes, calls and deleted copies are read only when [Access.privateShown]: the vault is unlocked,
 * private contacts aren't hidden, and Parley isn't locked. A duress unlock hides notes here as in their stores.
 */
class RecallSources(private val c: DataContainer) {
    /** What may be read now. */
    data class Access(val privateShown: Boolean)

    /** The stored part of a corpus: everything but the contacts and the calls, which the screens already hold. */
    data class Stored(
        val notes: List<RecallCorpus.Note> = emptyList(),
        val deleted: List<RecallCorpus.Gone> = emptyList(),
        val snapshots: List<RecallCorpus.Gone> = emptyList(),
        val messaged: List<RecallCorpus.Messaged> = emptyList(),
        val cases: List<RecallCorpus.Case> = emptyList(),
        /** Archived contacts, by name and number (they are out of the contact list Recall searches too). */
        val archived: List<RecallCorpus.Gone> = emptyList(),
    )

    val region: String get() = PhoneEnv.countryIso(c.appContext)

    /**
     * Reads every store; one that can't be read now adds nothing (the search shows what the others found). While
     * private contacts may not be shown, anything naming a private contact's number stays out, as number memory keeps
     * it out: a call note, a contact deleted earlier or a snapshot's copy (made private since, or an older copy under
     * another key), a chat opened before the number became private. When the private numbers can't be read, nothing
     * with a number is taken from those sources.
     */
    suspend fun load(access: Access): Stored = withContext(Dispatchers.IO) {
        val privateNumbers = if (access.privateShown) null else safely { PhoneIdentity.LineSet(c.vault.allNumbers(), region) }
        val hidden: (String) -> Boolean = when {
            access.privateShown -> { _ -> false }
            privateNumbers == null -> { _ -> true }
            else -> { n -> n.isNotBlank() && n in privateNumbers }
        }
        val stored = Stored(
            notes = safely { notes(access, hidden) }.orEmpty(),
            deleted = safely { deleted(access) }.orEmpty(),
            snapshots = safely { snapshots() }.orEmpty(),
            messaged = c.messaging.lastMessaged.value.values.mapNotNull { m ->
                m.number?.takeIf { it.isNotBlank() }?.let { RecallCorpus.Messaged(it, m.label, m.at) }
            },
            cases = safely { cases(access) }.orEmpty(),
            archived = safely { c.archive.all().map { a -> RecallCorpus.Gone(a.name, a.numbers, a.archivedAt, a.id.toString()) } }.orEmpty(),
        )
        withoutPrivate(stored, hidden)
    }

    /**
     * The calls held in memory, newest first: Android's call log with the newest of Parley's copy, and the calls with
     * private contacts when they may be shown. Older archived calls are read by date range ([archivedBetween]).
     */
    fun calls(access: Access): List<CallEntry> {
        val shared = c.history.calls.value.orEmpty()
        if (!access.privateShown) return shared
        val private = c.vault.privateCalls.value
        if (private.isEmpty()) return shared
        return (shared + private.map(CallHistory::privateEntry)).sortedByDescending { it.date }
    }

    /**
     * Where the archive goes beyond what [calls] holds: the oldest archived call in memory (older ones are read with
     * [archivedBetween]); null when none is held, so the archive (if any) is read for every date asked.
     */
    fun archiveWindowStart(calls: List<CallEntry>): Long? = calls.asSequence().filter(CallHistory::isArchived).minOfOrNull { it.date }

    /** Archived calls from [from] until [until], newest first, until [visit] says enough. */
    suspend fun archivedBetween(from: Long, until: Long, visit: (CallEntry) -> Boolean) = c.history.archivedBetween(from, until, visit)

    /**
     * The name the network last sent per number, for calls from numbers nobody saved: never for a private contact's
     * number (whether private contacts may show or not); none when the private numbers can't be read, and none while
     * "Remember names from the network" is off.
     */
    suspend fun networkNames(): (String, String?) -> String? = withContext(Dispatchers.IO) {
        val none = { _: String, _: String? -> null }
        if (safely { c.settings.current().rememberNetworkNames } != true) return@withContext none
        val privateNumbers = safely { PhoneIdentity.LineSet(c.vault.allNumbers(), region) } ?: return@withContext none
        val read = safely { c.networkNames.reader { it in privateNumbers } } ?: return@withContext none
        // Asked as the call's SIM reads the number, as it was written.
        val names: (String, String?) -> String? = { n, account ->
            NetworkName.latest(read(NetworkName.line(n, PhoneEnv.countryIso(c.appContext, account))))?.name
        }
        names
    }

    /** Number memory's hints for a number typed whole (the vault's and duress's rules applied by the store). */
    suspend fun remembered(number: String): List<MemoryHint> = c.numberMemory.hints(number, NumberMemory.Place.KEYPAD)

    /** A private contact's list id from one of its numbers (calls with private contacts open their page). */
    suspend fun privateIds(): PhoneIdentity.LineMap<Long> = withContext(Dispatchers.IO) {
        PhoneIdentity.LineMap<Long>(region).also { m ->
            safely { c.vault.summariesNow() }.orEmpty().forEach { v -> v.numbers.forEach { n -> m.putIfAbsent(n, ContactRef.Private(v.id).navId) } }
        }
    }

    private suspend fun notes(access: Access, hidden: (String) -> Boolean): List<RecallCorpus.Note> {
        val names = c.numberMemory.ownerNames()
        fun mayShow(key: String) = access.privateShown || !ContactRef.isPrivateKey(key)
        fun note(kind: RecallCorpus.Note.Kind, text: String?, key: String, at: Long): RecallCorpus.Note? =
            text?.takeIf { it.isNotBlank() && mayShow(key) }?.let { RecallCorpus.Note(kind, it, key, names[key], null, at, ContactRef.isPrivateKey(key)) }
        // The stores hide notes during a duress unlock already; asked again here, as number memory does.
        val notesShown = !Concealment.hides(Concealed.NOTES)
        val pinned = if (!notesShown) emptyList() else c.meta.allMetaNow().mapNotNull { m -> note(RecallCorpus.Note.Kind.PINNED, m.pinnedNote, m.lookupKey, 0) }
        val calls = if (!notesShown) emptyList() else callNotes(hidden)
        val circle = if (Concealment.hides(Concealed.CIRCLE_NOTES)) {
            emptyList()
        } else {
            c.circle.interactions.all().mapNotNull { i -> note(RecallCorpus.Note.Kind.CIRCLE, i.note, i.lookupKey, i.time) }
        }
        return pinned + calls + circle
    }

    /** Notes written after calls; one about a private contact's number stays out with them ([hidden]). */
    private suspend fun callNotes(hidden: (String) -> Boolean): List<RecallCorpus.Note> = c.meta.allCallNotesNow().mapNotNull { n ->
        val number = c.numberMemory.numberOf(n.numberKey)?.takeIf { !hidden(it) }
        if (number == null || n.text.isBlank()) null else RecallCorpus.Note(RecallCorpus.Note.Kind.CALL, n.text, null, null, number, n.callDate, id = n.id)
    }

    /**
     * Case files kept (by name, numbers and the labels of their reference numbers, never the numbers themselves); none
     * during a duress unlock, and a private contact's only while private contacts may show.
     */
    private suspend fun cases(access: Access): List<RecallCorpus.Case> {
        if (Concealment.hides(Concealed.NOTES)) return emptyList()
        val state = c.cases.load()
        return state.cases.filter { it.kept && (access.privateShown || !it.private) }.map { k ->
            val last = k.calls.maxOfOrNull { it.at } ?: k.created
            RecallCorpus.Case(k.id, k.name, k.numbers, k.references.map { it.label }.filter { it.isNotBlank() }, last, k.private)
        }
    }

    private suspend fun deleted(access: Access): List<RecallCorpus.Gone> {
        val device = c.journal.deletedForMemory().map { d -> RecallCorpus.Gone(d.name, d.numbers, d.at, d.id.toString()) }
        if (!access.privateShown || Concealment.hides(Concealed.DELETED_PRIVATE_CONTACTS)) return device
        return device + c.privateTrash.list().map { k -> RecallCorpus.Gone(k.name, k.numbers, k.deletedAt, k.file, private = true) }
    }

    /** Contacts and numbers the snapshots had that the newest no longer has, one entry per name then. */
    private suspend fun snapshots(): List<RecallCorpus.Gone> {
        val hints = NumberMemory.snapshotHints(c.timeMachine.peopleForMemory(), region).filter { it.hint.source == MemorySource.SNAPSHOT }
        return hints.groupBy { it.hint.name.orEmpty() to it.hint.at }.map { (key, entries) ->
            RecallCorpus.Gone(key.first, entries.map { it.number }.distinct(), key.second, entries.first().hint.ref ?: key.second.toString())
        }
    }

    @Suppress("TooGenericExceptionCaught") // One store that can't be read leaves the others' results.
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A Recall source couldn't be read: ${e.javaClass.simpleName}")
        null
    }

    internal companion object {
        private const val TAG = "Recall"

        /**
         * [stored] without what names a [hidden] number: a device contact deleted earlier, a snapshot's copy and a chat.
         * A deleted private contact (shown only when private contacts may be) stays. Call notes are filtered as read.
         */
        fun withoutPrivate(stored: Stored, hidden: (String) -> Boolean): Stored = stored.copy(
            deleted = stored.deleted.filter { it.private || it.numbers.none(hidden) },
            snapshots = stored.snapshots.filter { it.numbers.none(hidden) },
            messaged = stored.messaged.filterNot { hidden(it.number) },
            cases = stored.cases.filter { it.private || it.numbers.none(hidden) },
        )
    }
}
