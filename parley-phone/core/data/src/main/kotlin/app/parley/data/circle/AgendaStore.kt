package app.parley.data.circle

import app.parley.common.PhoneIdentity
import app.parley.common.catching
import app.parley.common.circle.Agenda
import app.parley.common.circle.Promises
import app.parley.common.people.ContactRef
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.db.CallNoteEntity
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whose agenda: a contact (by their Parley key), a private contact, or a number that isn't saved. */
sealed interface AgendaTarget {
    data class Contact(val lookupKey: String, val contactId: Long?) : AgendaTarget
    data class Private(val vaultId: Long) : AgendaTarget
    data class Number(val number: String) : AgendaTarget

    /** The Parley key the items are kept under (null for a number, whose items are on its notes). */
    val parleyKey: String?
        get() = when (this) {
            is Contact -> lookupKey
            is Private -> ContactRef.privateKey(vaultId)
            is Number -> null
        }

    companion object {
        /** The target of a Parley key: a private contact's ([ContactRef.privateKey]) or a device contact's lookup key. */
        fun forKey(key: String): AgendaTarget? = ContactRef.vaultIdOf(key)?.let(::Private)
            ?: key.takeIf { it.isNotEmpty() && !ContactRef.isPrivateKey(it) }?.let { Contact(it, null) }
    }
}

/**
 * The agenda ([Agenda]) over the stores it lives in: a contact's note for calls (contact_meta, sealed), a private
 * contact's (inside its sealed vault entry, so read and changed only while private contacts are unlocked and shown),
 * and for a number that isn't saved, its notes (call notes, sealed). A duress unlock hides the items as it hides those
 * notes; nothing here keeps a copy.
 */
class AgendaStore(private val c: DataContainer) {
    /** What adding an item did. */
    enum class Added { ADDED, ALREADY, LOCKED, FAILED }

    private val region: String get() = PhoneEnv.countryIso(c.appContext)

    /** Private contacts may be read now: shown (no discreet mode or duress hiding) and their details unlocked. */
    private suspend fun privateOpen(): Boolean =
        c.privacy.now().let { it.privateShown && it.notesShown } && !VaultCrypto.detailNeedsUnlock()

    /**
     * Who [number] is for the agenda: a contact, a private contact, an archived contact, or the number itself. Null for a work-profile
     * contact (Parley keeps nothing for those), a private contact while private contacts are hidden (the number must
     * not show an agenda of its own either), and something too short to be a number.
     */
    suspend fun targetFor(number: String, accountId: String? = null): AgendaTarget? = withContext(Dispatchers.IO) {
        if (PhoneIdentity.digits(number).length < MIN_DIGITS) return@withContext null
        // Who owns the number, found once per ring ([app.parley.data.people.NumberOwners]).
        val owners = catching { c.numberOwners.find(number, accountId) }.getOrNull()
        val found = owners?.contact
        if (found != null) {
            if (found.work) return@withContext null
            found.lookupKey?.takeIf { it.isNotEmpty() }?.let { return@withContext AgendaTarget.Contact(it, found.contactId) }
        }
        val private = owners?.private
        if (private != null) {
            return@withContext if (c.privacy.now().privateHidden) null else AgendaTarget.Private(private.first)
        }
        // An archived contact keeps its items in its note for calls, under its archived key, like a contact's.
        val archived = owners?.archived
        if (archived != null) return@withContext AgendaTarget.Contact(archived.parleyKey, null)
        AgendaTarget.Number(number)
    }

    /** The target of a contact's page: [navId] positive for a device contact, negative for a private one. */
    fun targetFor(navId: Long, lookupKey: String?): AgendaTarget? = when (val ref = ContactRef.ofNavId(navId)) {
        is ContactRef.Private -> AgendaTarget.Private(ref.vaultId)
        is ContactRef.Device -> lookupKey?.takeIf { it.isNotEmpty() && !ContactRef.isPrivateKey(it) }?.let { AgendaTarget.Contact(it, navId) }
        null -> null
    }

    /** The open items, in the order written; null when they can't be read now (a private contact while locked or hidden). */
    @Suppress("TooGenericExceptionCaught") // Fails closed: no agenda rather than a broken screen.
    suspend fun open(target: AgendaTarget): List<String>? = withContext(Dispatchers.IO) {
        try {
            when (target) {
                is AgendaTarget.Contact -> Agenda.open(c.meta.meta(target.lookupKey)?.pinnedNote)
                is AgendaTarget.Private -> if (!privateOpen()) null else Agenda.open(c.vault.details(target.vaultId)?.pinnedNote)
                is AgendaTarget.Number -> numberNotes(target.number).flatMap { Agenda.open(it.text) }.distinct()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Adds [text] as an item. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun add(target: AgendaTarget, text: String): Added = withContext(Dispatchers.IO) {
        val item = Agenda.clean(text) ?: return@withContext Added.FAILED
        try {
            when (target) {
                is AgendaTarget.Contact -> {
                    val before = Agenda.open(c.meta.meta(target.lookupKey)?.pinnedNote)
                    if (before.any { it.equals(item, ignoreCase = true) }) return@withContext Added.ALREADY
                    if (c.circle.editPinnedNote(target.lookupKey, target.contactId) { Agenda.add(it, item) }) Added.ADDED else Added.FAILED
                }
                is AgendaTarget.Private -> editPrivate(target.vaultId) { Agenda.add(it, item) }
                is AgendaTarget.Number -> {
                    if (numberNotes(target.number).any { n -> Agenda.open(n.text).any { it.equals(item, ignoreCase = true) } }) {
                        return@withContext Added.ALREADY
                    }
                    val now = System.currentTimeMillis()
                    val note = CallNoteEntity(numberKey = PhoneIdentity.key(target.number, region), callDate = now, text = Promises.OPEN + item)
                    val id = c.meta.addCallNote(note)
                    if (id > 0) Added.ADDED else Added.FAILED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: VaultCrypto.LockedException) {
            Added.LOCKED
        } catch (_: Exception) {
            Added.FAILED
        }
    }

    /**
     * Ticks the item reading [text] off ([done]) or opens it again. Returns the note it was in as it was before (for
     * a number's call note, what an expected call made from that note follows), or null when nothing changed.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun setDone(target: AgendaTarget, text: String, done: Boolean): CircleRepository.PersonNote? = withContext(Dispatchers.IO) {
        try {
            when (target) {
                is AgendaTarget.Contact -> {
                    var before: String? = null
                    val changed = c.circle.editPinnedNote(target.lookupKey, target.contactId) { note ->
                        before = note
                        note?.let { Agenda.setDone(it, text, done) }
                    }
                    if (changed) CircleRepository.PersonNote(CircleRepository.NoteSource.PINNED, 0, 0, before.orEmpty()) else null
                }
                is AgendaTarget.Private -> {
                    var before: String? = null
                    val changed = editPrivate(target.vaultId) { note ->
                        before = note
                        note?.let { Agenda.setDone(it, text, done) }
                    } == Added.ADDED
                    if (changed) CircleRepository.PersonNote(CircleRepository.NoteSource.PINNED, 0, 0, before.orEmpty()) else null
                }
                is AgendaTarget.Number -> {
                    val note = numberNotes(target.number).firstOrNull { n -> Promises.parse(n.text).any { it.text == text && it.done != done } }
                        ?: return@withContext null
                    val line = Promises.parse(note.text).first { it.text == text && it.done != done }.line
                    val shown = CircleRepository.PersonNote(CircleRepository.NoteSource.CALL, note.id, note.callDate, note.text)
                    if (c.circle.setPromiseDone("", shown, line, done)) shown else null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** A number's notes, newest first (read with each key it may be stored under). */
    private suspend fun numberNotes(number: String): List<CallNoteEntity> {
        val keys = PhoneIdentity.lookupKeys(number, region).distinct()
        return if (keys.isEmpty()) emptyList() else c.meta.callNotesNow(keys)
    }

    /**
     * Changes a private contact's note for calls inside its sealed entry: only while private contacts are shown and
     * unlocked, never over details rebuilt after their key was lost (that's the user's "Keep what's left" choice).
     */
    private suspend fun editPrivate(vaultId: Long, change: (String?) -> String?): Added {
        if (!privateOpen()) return Added.LOCKED
        if (c.vault.detailsLost(vaultId)) return Added.FAILED
        val d = c.vault.details(vaultId) ?: return Added.FAILED
        val now = d.pinnedNote.ifBlank { null }
        val next = change(now)
        if (next == now) return Added.ALREADY
        c.vault.save(vaultId, d.copy(pinnedNote = next.orEmpty()))
        return Added.ADDED
    }

    private companion object {
        /** Fewer digits than this is a service code, not a number to keep things for. */
        const val MIN_DIGITS = 3
    }
}
