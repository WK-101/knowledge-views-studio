package app.parley.data.memory

import android.util.Log
import app.parley.common.PhoneIdentity
import app.parley.common.memory.MemoryHint
import app.parley.common.memory.MemorySource
import app.parley.common.memory.NumberMemory
import app.parley.common.people.ContactRef
import app.parley.common.people.RelationLinks
import app.parley.common.security.Concealed
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.security.Concealment
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.io.File

/**
 * Number memory (I1, P12): "who is this?" for a number that isn't a contact, from what Parley already keeps on this
 * phone. The index ([NumberMemoryIndex]) covers the stores that must be opened or scanned to be searched: contacts
 * deleted in Parley, deleted private contacts, the daily snapshots, the call-history archive (with the names calls
 * showed then), notes, moments and promises that mention a number, and relations that still name a deleted contact.
 * The To call list and the chats opened from Parley are already in memory by number and are asked directly.
 *
 * Built by the daily upkeep and after contacts are deleted or restored in Parley; read on the call path within its
 * lookup time, never on the main thread, and failing open (no line) when anything can't be read.
 */
class NumberMemoryStore(private val c: DataContainer) {
    val index = NumberMemoryIndex(File(c.appContext.noBackupFilesDir, "number_memory"), KeystoreMemoryKeys(c.appContext))

    private val region: String get() = PhoneEnv.countryIso(c.appContext)

    /** Brings the index up to date (the stores that changed since the last run are read again). */
    suspend fun rebuild(): NumberMemoryIndex.Stats = index.rebuild(sources(), region)

    /**
     * What to show for [number] at [place], best first; empty for a number that is a private contact (named elsewhere,
     * or hidden by discreet mode, which must not be hinted at either). Private hints only while the vault is
     * unlocked and discreet mode is off. None that quotes a note while a duress unlock hides notes.
     */
    @Suppress("TooGenericExceptionCaught") // Fail open: no line rather than a broken call screen or keypad.
    suspend fun hints(number: String, place: NumberMemory.Place, region: String = this.region): List<MemoryHint> = try {
        if (number.isBlank() || c.vault.lookup(number, region) != null) {
            emptyList()
        } else {
            val privateAllowed = !c.settings.current().hideVault && !VaultCrypto.detailNeedsUnlock()
            // H3: the index keeps excerpts of notes from before a duress unlock; they're dropped here while it hides notes.
            val notesHidden = Concealment.hides(Concealed.NOTES) || Concealment.hides(Concealed.CIRCLE_NOTES)
            NumberMemory.rank(NumberMemory.concealNotes(index.lookup(number, region) + live(number, region), notesHidden), place, privateAllowed)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Number memory lookup failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    suspend fun best(number: String, place: NumberMemory.Place, region: String = this.region): MemoryHint? = hints(number, place, region).firstOrNull()

    /** The To call list and the last chat, straight from their in-memory stores. */
    private suspend fun live(number: String, region: String): List<MemoryHint> = buildList {
        runCatching { c.messaging.lastMessaged(number) }.getOrNull()?.let { m ->
            add(MemoryHint(MemorySource.MESSAGED, excerpt = m.label, at = m.at))
        }
        runCatching {
            if (!c.toCall.available) c.toCall.load()
            c.toCall.state.value.items.filter { PhoneIdentity.same(it.number, number, region) }.minByOrNull { it.since }
        }.getOrNull()?.let { add(MemoryHint(MemorySource.TO_CALL, at = it.since)) }
    }

    /**
     * Rebuilds once things settle after a contact is deleted or restored in Parley (only what changed is read again),
     * and at start when the index is older than a day (the daily upkeep didn't run, or never did).
     */
    @OptIn(FlowPreview::class)
    suspend fun follow() {
        if (System.currentTimeMillis() - index.builtAt() > STALE_MS) rebuildLogged()
        c.journal.recent().drop(1).debounce(FOLLOW_DEBOUNCE_MS).collect { rebuildLogged() }
    }

    @Suppress("TooGenericExceptionCaught") // A failed rebuild keeps the previous index; the next change retries.
    private suspend fun rebuildLogged() {
        try {
            rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Number memory rebuild failed: ${e.javaClass.simpleName}")
        }
    }

    private fun sources(): List<NumberMemoryIndex.Source> = listOf(deleted, deletedPrivate, snapshots, archive, notes)

    /** Contacts deleted in Parley (History & undo), with a contact whose relations still name them. */
    private val deleted = object : NumberMemoryIndex.Source {
        override val id = "deleted"
        override suspend fun fingerprint() = c.journal.memoryStamp() + "|" + relationsStamp()
        override suspend fun read(): List<NumberMemory.Entry> {
            val links = relationOwners()
            return NumberMemory.deleted(c.journal.deletedForMemory()) { key -> links[key] }
        }
    }

    /** "Deleted private contacts": sealed copies, read with the caller-ID key; shown only after the vault's unlock. */
    private val deletedPrivate = object : NumberMemoryIndex.Source {
        override val id = "deleted_private"
        override suspend fun fingerprint() = c.privateTrash.memoryStamp()
        override suspend fun read(): List<NumberMemory.Entry> = c.privateTrash.list().flatMap { k ->
            k.numbers.filter { it.isNotBlank() }.distinct().map { n ->
                NumberMemory.Entry(n, MemoryHint(MemorySource.DELETED_PRIVATE, name = k.name, at = k.deletedAt, ref = k.file, private = true))
            }
        }
    }

    private val snapshots = object : NumberMemoryIndex.Source {
        override val id = "snapshots"
        override suspend fun fingerprint() = c.timeMachine.memoryStamp()
        override suspend fun read() = NumberMemory.snapshotHints(c.timeMachine.peopleForMemory(), region)
    }

    private val archive = object : NumberMemoryIndex.Source {
        override val id = "archive"
        override suspend fun fingerprint() = c.history.memoryStamp()
        override suspend fun read() = c.history.pastCallsForMemory()?.let { NumberMemory.pastCalls(it, region) }
    }

    /**
     * Pinned notes, moments and promises that mention a number (on device and private contacts' pages), and notes
     * written after calls. Small enough to read every time.
     */
    private val notes = object : NumberMemoryIndex.Source {
        override val id = "notes"
        override suspend fun fingerprint(): String? = null
        override suspend fun read(): List<NumberMemory.Entry> {
            val names = ownerNames()
            fun note(text: String?, key: String, at: Long) = text?.takeIf { it.isNotBlank() }?.let {
                NumberMemory.Note(it, key, names[key], at, private = ContactRef.isPrivateKey(key))
            }
            val pinned = c.meta.allMetaNow().mapNotNull { m -> note(m.pinnedNote, m.lookupKey, 0) }
            val moments = c.circle.interactions.all().mapNotNull { i -> note(i.note, i.lookupKey, i.time) }
            val callNotes = c.meta.allCallNotesNow().mapNotNull { n -> numberOf(n.numberKey)?.let { NumberMemory.callNote(it, n.text, n.callDate) } }
            return NumberMemory.noteHints(pinned + moments, region) + callNotes
        }
    }

    /** A number to hash for a call note's stored line key ([PhoneIdentity.key], or the last digits it had before). */
    private fun numberOf(key: String): String? = when {
        key.startsWith("+") -> key
        key.startsWith("~") -> key.drop(2).takeIf { it.isNotEmpty() }
        PhoneIdentity.isLegacyKey(key) -> key
        else -> null
    }

    /** Parley key → name: device contacts by lookup key, private contacts by their key (names only, from caller ID). */
    private suspend fun ownerNames(): Map<String, String> {
        val out = HashMap<String, String>()
        (c.contacts.contacts.value ?: c.contacts.loadNow()).forEach { out[it.lookupKey] = it.displayName }
        runCatching { c.vault.summariesNow() }.getOrNull()?.forEach { out[ContactRef.privateKey(it.id)] = it.name }
        return out
    }

    /** Deleted contact's key → the device contact whose relations still link to it (private owners never named). */
    private suspend fun relationOwners(): Map<String, String> {
        val names = ownerNames()
        val out = HashMap<String, String>()
        c.meta.allMetaNow().forEach { m ->
            if (ContactRef.isPrivateKey(m.lookupKey)) return@forEach
            val owner = names[m.lookupKey] ?: return@forEach
            RelationLinks.decode(m.relationLinks).values.forEach { link -> out.putIfAbsent(link.lookupKey, owner) }
        }
        return out
    }

    private suspend fun relationsStamp(): Int = c.meta.allMetaNow().sumOf { (it.lookupKey + it.relationLinks.orEmpty()).hashCode() }

    private companion object {
        const val TAG = "NumberMemory"
        const val FOLLOW_DEBOUNCE_MS = 20_000L
        const val STALE_MS = 24 * 3_600_000L
    }
}
