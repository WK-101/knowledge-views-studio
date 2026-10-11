package app.parley.data.memory

import androidx.room.InvalidationTracker
import app.parley.common.catching
import app.parley.common.memory.NumberMemory
import app.parley.common.people.ContactRef
import app.parley.common.people.RelationLinks
import app.parley.data.ContactsRepository
import app.parley.data.JournalRepository
import app.parley.data.backup.TimeMachine
import app.parley.data.db.AppDatabase
import app.parley.data.db.MetaDao
import app.parley.data.vault.PrivateTrash
import app.parley.data.vault.VaultRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What number memory and Recall both read from Parley's own stores (contacts deleted in Parley, deleted private
 * contacts, the snapshots' versions, notes, the names of the people they belong to), each with a stamp that changes
 * when the store does. One place, so a store is read once per change instead of by each feature on each use, and
 * each feature applies its own privacy rule on top: number memory when it answers, Recall when it loads.
 *
 * Nothing opened here outlives the call that asked for it, except the snapshots' hints (names and numbers of the
 * address book's own past versions), kept while the snapshots are unchanged; the decrypted notes are never held.
 */
class KeptSources(
    private val db: () -> AppDatabase,
    private val journal: () -> JournalRepository,
    private val privateTrash: () -> PrivateTrash,
    private val timeMachine: () -> TimeMachine,
    private val contacts: ContactsRepository,
    private val vault: () -> VaultRepository,
    private val meta: () -> MetaDao,
) {
    /** Bumped by Room whenever a table holding notes changes; with [process], a stamp no earlier run can match. */
    private val notesVersion = AtomicLong()
    private val watching = AtomicBoolean()
    private val process = System.nanoTime()

    private fun watchNotes() {
        if (!watching.compareAndSet(false, true)) return
        db().invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("contact_meta", "interactions", "call_notes")) {
                override fun onInvalidated(tables: Set<String>) {
                    notesVersion.incrementAndGet()
                }
            },
        )
    }

    /** Changes when a pinned note, a moment or a call note may have changed (and once per process start). */
    fun notesStamp(): String {
        watchNotes()
        return "$process:${notesVersion.get()}"
    }

    suspend fun deletedStamp(): String = journal().memoryStamp() + "|" + relationsStamp()

    suspend fun deletedPrivateStamp(): String = privateTrash().memoryStamp()

    suspend fun snapshotsStamp(): String = timeMachine().memoryStamp()

    /** All of them, for a reader that keeps what it loaded while nothing it read has changed. */
    suspend fun stamp(): String = listOf(notesStamp(), deletedStamp(), deletedPrivateStamp(), snapshotsStamp()).joinToString("/")

    @Volatile private var hints: Triple<String, String, List<NumberMemory.Entry>>? = null

    /** What the snapshots know that the newest no longer has, by number; kept while the snapshots are unchanged. */
    suspend fun snapshotHints(region: String): List<NumberMemory.Entry> {
        val stamp = snapshotsStamp()
        hints?.takeIf { it.first == stamp && it.second == region }?.let { return it.third }
        return NumberMemory.snapshotHints(timeMachine().peopleForMemory(), region).also { hints = Triple(stamp, region, it) }
    }

    /** Drops the snapshots' hints (the phone is short of memory). */
    fun drop() {
        hints = null
    }

    suspend fun deleted() = journal().deletedForMemory()

    suspend fun deletedPrivate() = privateTrash().list()

    /** Parley key → name: device contacts by lookup key, private contacts by their key (names only, from caller ID). */
    suspend fun ownerNames(): Map<String, String> {
        val out = HashMap<String, String>()
        (contacts.contacts.value ?: contacts.loadNow()).forEach { out[it.lookupKey] = it.displayName }
        catching { vault().summariesNow() }.getOrNull()?.forEach { out[ContactRef.privateKey(it.id)] = it.name }
        return out
    }

    /** Deleted contact's key → the device contact whose relations still link to it (private owners never named). */
    suspend fun relationOwners(): Map<String, String> {
        val names = ownerNames()
        val out = HashMap<String, String>()
        meta().allMetaNow().forEach { m ->
            if (ContactRef.isPrivateKey(m.lookupKey)) return@forEach
            val owner = names[m.lookupKey] ?: return@forEach
            RelationLinks.decode(m.relationLinks).values.forEach { link -> out.putIfAbsent(link.lookupKey, owner) }
        }
        return out
    }

    @Volatile private var relations: Pair<String, Int>? = null

    /** The relations' stamp: read again only when a note's table changed (relations live in contact_meta). */
    private suspend fun relationsStamp(): Int {
        val notes = notesStamp()
        relations?.takeIf { it.first == notes }?.let { return it.second }
        return meta().allMetaNow().sumOf { (it.lookupKey + it.relationLinks.orEmpty()).hashCode() }.also { relations = notes to it }
    }
}
