package app.parley.data.recall

import android.util.Log
import app.parley.common.CallEntry
import app.parley.common.PhoneIdentity
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
 * calls, Circle notes (and the promises in them), notes written after calls, contacts deleted in Parley, the
 * snapshots' gone contacts and the chats opened from Parley. Everything is opened in memory only, for as long as the
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
    )

    val region: String get() = PhoneEnv.countryIso(c.appContext)

    /** Reads every store; one that can't be read now adds nothing (the search shows what the others found). */
    suspend fun load(access: Access): Stored = withContext(Dispatchers.IO) {
        val privateNumbers = if (access.privateShown) null else safely { PhoneIdentity.LineSet(c.vault.allNumbers(), region) }
        Stored(
            notes = safely { notes(access, privateNumbers) }.orEmpty(),
            deleted = safely { deleted(access) }.orEmpty(),
            snapshots = safely { snapshots() }.orEmpty(),
            messaged = c.messaging.lastMessaged.value.values.mapNotNull { m ->
                m.number?.takeIf { it.isNotBlank() }?.let { RecallCorpus.Messaged(it, m.label, m.at) }
            },
        )
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

    /** Number memory's hints for a number typed whole (the vault's and duress's rules applied by the store). */
    suspend fun remembered(number: String): List<MemoryHint> = c.numberMemory.hints(number, NumberMemory.Place.KEYPAD)

    /** A private contact's list id from one of its numbers (calls with private contacts open their page). */
    suspend fun privateIds(): PhoneIdentity.LineMap<Long> = withContext(Dispatchers.IO) {
        PhoneIdentity.LineMap<Long>(region).also { m ->
            safely { c.vault.summariesNow() }.orEmpty().forEach { v -> v.numbers.forEach { n -> m.putIfAbsent(n, ContactRef.Private(v.id).navId) } }
        }
    }

    private suspend fun notes(access: Access, privateNumbers: PhoneIdentity.LineSet?): List<RecallCorpus.Note> {
        val names = c.numberMemory.ownerNames()
        fun mayShow(key: String) = access.privateShown || !ContactRef.isPrivateKey(key)
        fun note(kind: RecallCorpus.Note.Kind, text: String?, key: String, at: Long): RecallCorpus.Note? =
            text?.takeIf { it.isNotBlank() && mayShow(key) }?.let { RecallCorpus.Note(kind, it, key, names[key], null, at, ContactRef.isPrivateKey(key)) }
        // The stores hide notes during a duress unlock already; asked again here, as number memory does.
        val notesShown = !Concealment.hides(Concealed.NOTES)
        val pinned = if (!notesShown) emptyList() else c.meta.allMetaNow().mapNotNull { m -> note(RecallCorpus.Note.Kind.PINNED, m.pinnedNote, m.lookupKey, 0) }
        val calls = if (!notesShown) emptyList() else callNotes(privateNumbers)
        val circle = if (Concealment.hides(Concealed.CIRCLE_NOTES)) {
            emptyList()
        } else {
            c.circle.interactions.all().mapNotNull { i -> note(RecallCorpus.Note.Kind.CIRCLE, i.note, i.lookupKey, i.time) }
        }
        return pinned + calls + circle
    }

    /** Notes written after calls; one about a private contact's number stays out with them ([privateNumbers]). */
    private suspend fun callNotes(privateNumbers: PhoneIdentity.LineSet?): List<RecallCorpus.Note> = c.meta.allCallNotesNow().mapNotNull { n ->
        val number = c.numberMemory.numberOf(n.numberKey)?.takeIf { privateNumbers == null || it !in privateNumbers }
        if (number == null || n.text.isBlank()) null else RecallCorpus.Note(RecallCorpus.Note.Kind.CALL, n.text, null, null, number, n.callDate, id = n.id)
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

    private companion object {
        const val TAG = "Recall"
    }
}
