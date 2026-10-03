package app.parley.data.security

import app.parley.data.db.BlockDao
import app.parley.data.db.BlockedCallEntity
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.JournalEntity
import app.parley.data.db.MetaDao
import app.parley.common.security.Concealed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [MetaDao] with pinned notes, Parley-only relations, call-note texts and journal payloads sealed at rest ([RecordCrypto]). Everything above
 * it sees plain values; the table rows hold sealed ones. Plain rows from older versions read as they are until
 * [RecordSealing] re-seals them.
 *
 * A sealed note that can't be opened right now (a Keystore hiccup, or a lost key) reads as no note, but is never
 * replaced by one of the whole-row writes ([setMeta], [setPersonalMeta]) that pass that "no note" back: the stored
 * ciphertext stays until the note is explicitly set ([setPinnedNote]).
 */
class SealedMetaDao(private val dao: MetaDao, private val crypto: RecordCrypto) : MetaDao by dao {
    /**
     * I21: after a duress unlock, notes for calls and call notes read as none (kept as stored, see [kept]). L1: except
     * those written since (shown as written for as long as the hiding lasts), and a note typed over a hidden one,
     * which shows instead of it, in memory only: the hidden note is never replaced unseen.
     */
    private val hidden: Boolean get() = Concealment.hides(Concealed.NOTES)

    /** Re-emits when the duress hiding starts or ends, or what it shows changes (L1). */
    private val hiding = combine(Concealment.state.map { it.hiding }.distinctUntilChanged(), Concealment.revisions) { h, r -> h to r }

    private fun noteToken(key: String) = "note:$key"

    private fun callNoteToken(id: Long) = "callnote:$id"

    private fun open(note: String?) = if (crypto.isSealed(note)) crypto.openText(note) else note

    private fun ContactMetaEntity.opened() = when {
        hidden -> copy(pinnedNote = shownWhileHidden(lookupKey, pinnedNote), parleyRelations = open(parleyRelations))
        else -> copy(pinnedNote = open(pinnedNote), parleyRelations = open(parleyRelations))
    }

    /**
     * What to store as [key]'s Parley-only relations when a write passes [relations]: sealed; or the stored ciphertext
     * kept when a whole-row write passes back the "none" an unreadable one read as.
     */
    private suspend fun relationsToStore(key: String, relations: String?): String? =
        crypto.sealText(relations) ?: dao.meta(key)?.parleyRelations?.takeIf { crypto.isUnreadable(it) }

    /** [key]'s note while notes are hidden: the one typed over a hidden one, one written since, or none. */
    private fun shownWhileHidden(key: String, stored: String?): String? {
        val t = noteToken(key)
        return when {
            Concealment.hasOverlay(t) -> Concealment.overlay(t)
            Concealment.writtenWhileHiding(t) -> open(stored)
            else -> null
        }
    }

    /**
     * While notes are hidden, [key]'s stored note as it is when it is one written before (a write that passes "no
     * note" back must not clear it, and a new one must not replace it unseen: [note] then shows instead, in memory).
     * Null when [note] may be stored: no note was stored, or it was written while hiding.
     */
    private suspend fun kept(key: String, note: String?): String? {
        if (!hidden) return null
        val t = noteToken(key)
        val stored = dao.meta(key)?.pinnedNote
        if (stored == null || Concealment.writtenWhileHiding(t)) {
            if (note != null) Concealment.markWritten(t)
            return null
        }
        if (note != null || Concealment.hasOverlay(t)) Concealment.setOverlay(t, note)
        return stored
    }

    /** What to store for [key]'s note when a write passes [note]: sealed, or the stored ciphertext kept for null. */
    private suspend fun noteToStore(key: String, note: String?): String? =
        kept(key, note) ?: note?.let { crypto.sealText(it) } ?: unreadableNote(key)

    /** [key]'s stored note as it is (sealed) when it can't be opened right now; null otherwise. */
    suspend fun unreadableNote(key: String): String? = dao.meta(key)?.pinnedNote?.takeIf { crypto.isUnreadable(it) }

    /** Stores [sealed] (a ciphertext read with [unreadableNote]) unchanged as the note of [key]'s existing row. */
    suspend fun keepSealedNote(key: String, sealed: String) {
        require(crypto.isSealed(sealed))
        dao.meta(key)?.let { dao.setMeta(it.copy(pinnedNote = sealed)) }
    }
    private fun CallNoteEntity.opened() = if (crypto.isSealed(text)) copy(text = crypto.openText(text).orEmpty()) else this

    private fun CallNoteEntity.visible() = !hidden || Concealment.writtenWhileHiding(callNoteToken(id))

    /** Call notes as shown: while a duress unlock hides notes, only those written since (L1). */
    private fun List<CallNoteEntity>.shown() = filter { it.visible() }.map { it.opened() }

    override suspend fun addJournal(e: JournalEntity): Long = dao.addJournal(e.copy(payload = crypto.sealBytes(e.payload)))

    override suspend fun journalEntry(id: Long): JournalEntity? = dao.journalEntry(id)?.let { it.copy(payload = crypto.openBytes(it.payload)) }

    override suspend fun meta(key: String): ContactMetaEntity? = dao.meta(key)?.opened()

    override fun metaFlow(key: String): Flow<ContactMetaEntity?> = combine(dao.metaFlow(key), hiding) { m, _ -> m?.opened() }

    override suspend fun setPinnedNote(key: String, contactId: Long, note: String?) =
        dao.setPinnedNote(key, contactId, kept(key, note) ?: crypto.sealText(note))

    override fun allMeta(): Flow<List<ContactMetaEntity>> = combine(dao.allMeta(), hiding) { l, _ -> l.map { it.opened() } }

    override suspend fun allMetaNow(): List<ContactMetaEntity> = dao.allMetaNow().map { it.opened() }

    override suspend fun setPersonalMeta(key: String, note: String?, messenger: String?, links: String?, nudged: Long?): Int =
        dao.setPersonalMeta(key, noteToStore(key, note), messenger, links, nudged)

    override suspend fun setMeta(e: ContactMetaEntity) =
        dao.setMeta(e.copy(pinnedNote = noteToStore(e.lookupKey, e.pinnedNote), parleyRelations = relationsToStore(e.lookupKey, e.parleyRelations)))

    override suspend fun setParleyRelations(key: String, relations: String?) = dao.setParleyRelations(key, crypto.sealText(relations))

    override suspend fun addCallNote(n: CallNoteEntity): Long =
        dao.addCallNote(n.copy(text = crypto.sealText(n.text).orEmpty())).also { if (hidden && it > 0) Concealment.markWritten(callNoteToken(it)) }

    override fun callNotes(key: String): Flow<List<CallNoteEntity>> = combine(dao.callNotes(key), hiding) { l, _ -> l.shown() }

    override fun callNotesAny(keys: List<String>): Flow<List<CallNoteEntity>> = combine(dao.callNotesAny(keys), hiding) { l, _ -> l.shown() }

    override suspend fun allCallNotesNow(): List<CallNoteEntity> = dao.allCallNotesNow().shown()

    /** Sealed texts can't be compared in SQL (each has its own nonce): compared after opening. */
    override suspend fun countCallNote(key: String, callDate: Long, text: String): Int =
        dao.callNotesNow(listOf(key)).count { it.callDate == callDate && it.opened().text == text }

    override fun allCallNotes(): Flow<List<CallNoteEntity>> = combine(dao.allCallNotes(), hiding) { l, _ -> l.shown() }

    override suspend fun callNotesNow(keys: List<String>): List<CallNoteEntity> = dao.callNotesNow(keys).shown()

    override suspend fun callNote(id: Long): CallNoteEntity? = dao.callNote(id)?.takeIf { it.visible() }?.opened()

    override suspend fun setCallNoteText(id: Long, text: String) {
        // Hidden notes can't be edited (none is shown); nothing reaches the stored one. One written since can (L1).
        if (!hidden || Concealment.writtenWhileHiding(callNoteToken(id))) dao.setCallNoteText(id, crypto.sealText(text).orEmpty())
    }
}

/** [BlockDao] with screened callers' names (as the network presented them) sealed at rest. */
class SealedBlockDao(private val dao: BlockDao, private val crypto: RecordCrypto) : BlockDao by dao {
    private fun BlockedCallEntity.opened() = if (crypto.isSealed(callerName)) copy(callerName = crypto.openText(callerName)) else this
    private fun BlockedCallEntity.sealed() = copy(callerName = crypto.sealText(callerName))

    override suspend fun logBlocked(call: BlockedCallEntity) = dao.logBlocked(call.sealed())

    override suspend fun logScreened(call: BlockedCallEntity): Long = dao.logScreened(call.sealed())

    override fun blockedCalls(): Flow<List<BlockedCallEntity>> = dao.blockedCalls().map { l -> l.map { it.opened() } }

    override fun screenedCalls(): Flow<List<BlockedCallEntity>> = dao.screenedCalls().map { l -> l.map { it.opened() } }

    override suspend fun screenedSince(since: Long): List<BlockedCallEntity> = dao.screenedSince(since).map { it.opened() }

    override suspend fun blockedCallsNow(): List<BlockedCallEntity> = dao.blockedCallsNow().map { it.opened() }
}
