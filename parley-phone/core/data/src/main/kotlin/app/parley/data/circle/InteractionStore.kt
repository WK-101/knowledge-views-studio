package app.parley.data.circle

import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.InteractionType
import app.parley.data.db.InteractionDao
import app.parley.data.db.InteractionEntity
import app.parley.data.db.InteractionTouchRow
import app.parley.common.security.Concealed
import app.parley.data.security.Concealment
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** One logged interaction, with its note opened. */
data class Interaction(
    val id: Long,
    val lookupKey: String,
    val contactId: Long?,
    val type: InteractionType,
    val channel: InteractionChannel?,
    val time: Long,
    val note: String?,
    val dedupeKey: String,
)

/**
 * Interactions in Room. Notes are sealed with Parley's Keystore key for small private records (the key that
 * also seals the "messaged numbers" record): AES-GCM, no user authentication, so the Circle and reminders work
 * while the phone is locked, but nothing personal is readable at rest. Everything else in a row (who, when, which
 * kind) is what the call log already holds for calls.
 */
class InteractionStore(private val dao: InteractionDao) {
    /** A note couldn't be sealed (Keystore unavailable): nothing was saved. */
    class SealException(cause: Throwable) : Exception("Couldn't encrypt the note", cause)

    private fun seal(note: String?): ByteArray? {
        val text = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            VaultCrypto.sealCallerId(text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SealException(e)
        }
    }

    /**
     * A note that can't be opened (key reset) reads as none; the entry itself stays. After a duress unlock (I21) every
     * note reads as none too, promises included ([reveal]: the undo copy of a deleted entry, never shown).
     */
    private fun open(blob: ByteArray?, reveal: Boolean = false): String? {
        if (blob == null || (!reveal && Concealment.hides(Concealed.CIRCLE_NOTES))) return null
        return runCatching { String(VaultCrypto.openCallerId(blob), Charsets.UTF_8) }.getOrNull()
    }

    /** What an edit stores for [note]: while notes are hidden an existing note is kept as it is, never cleared. */
    private fun noteToStore(e: InteractionEntity, note: String?): ByteArray? =
        if (e.noteBlob != null && Concealment.hides(Concealed.CIRCLE_NOTES)) e.noteBlob else seal(note)

    private fun InteractionEntity.toModel(reveal: Boolean = false) = Interaction(
        id, lookupKey, contactId,
        InteractionType.entries.firstOrNull { it.name == type } ?: InteractionType.OTHER,
        InteractionChannel.decode(channel), time, open(noteBlob, reveal), dedupeKey,
    )

    /** [lookupKey]'s interactions, newest first (again when a duress unlock hides or shows the notes). */
    fun interactions(lookupKey: String): Flow<List<Interaction>> =
        combine(dao.forKey(lookupKey), Concealment.state.map { it.hiding }.distinctUntilChanged()) { list, _ -> list.map { it.toModel() } }
            .flowOn(Dispatchers.IO)

    suspend fun interactionsFor(lookupKey: String): List<Interaction> = withContext(Dispatchers.IO) { dao.forKeyNow(lookupKey).map { it.toModel() } }

    /** Every interaction (backups), newest first. */
    suspend fun all(): List<Interaction> = withContext(Dispatchers.IO) { dao.all().map { it.toModel() } }

    /** Newest interaction time and kind per lookup key; updates on every change. */
    val latest: Flow<Map<String, Pair<Long, InteractionType>>> = dao.latestPerKey().map { rows ->
        rows.associate { it.lookupKey to (it.time to (InteractionType.entries.firstOrNull { t -> t.name == it.type } ?: InteractionType.OTHER)) }
    }

    /** Time and kind of [lookupKey]'s newest interaction, without opening any note. */
    suspend fun latestFor(lookupKey: String): Pair<Long, InteractionType>? = withContext(Dispatchers.IO) {
        dao.latestFor(lookupKey)?.let { it.time to (InteractionType.entries.firstOrNull { t -> t.name == it.type } ?: InteractionType.OTHER) }
    }

    /** Times of [lookupKey]'s interactions (for the natural rhythm). */
    suspend fun timesFor(lookupKey: String): List<Long> = withContext(Dispatchers.IO) { dao.timesFor(lookupKey) }

    /**
     * Records an interaction. Returns its id, or null when [dedupeKey] was already recorded (the same launch, the
     * same occasion). Throws [SealException] when a note can't be encrypted.
     */
    suspend fun log(
        lookupKey: String,
        contactId: Long?,
        type: InteractionType,
        channel: InteractionChannel?,
        time: Long,
        note: String?,
        dedupeKey: String,
    ): Long? = withContext(Dispatchers.IO) {
        val id = dao.insert(InteractionEntity(lookupKey = lookupKey, contactId = contactId, type = type.name, channel = channel?.name, time = time, noteBlob = seal(note), dedupeKey = dedupeKey))
        id.takeIf { it > 0 }
    }

    /** Changes kind, note and (only if the user picked a new one) time. Throws [SealException]. */
    suspend fun edit(id: Long, type: InteractionType, note: String?, time: Long? = null) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext
        dao.update(e.copy(type = type.name, noteBlob = noteToStore(e, note), time = time ?: e.time))
    }

    /** Only the note changes (a promise ticked off). Throws [SealException]. */
    suspend fun setNote(id: Long, note: String?) = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext
        dao.update(e.copy(noteBlob = noteToStore(e, note)))
    }

    /** The current (opened) note of entry [id], or null. */
    suspend fun noteOf(id: Long): String? = withContext(Dispatchers.IO) { dao.get(id)?.let { open(it.noteBlob) } }

    /** (lookup key, time, dedupe key) of every interaction since [since]; notes stay sealed. */
    suspend fun touchesSince(since: Long): List<InteractionTouchRow> = withContext(Dispatchers.IO) { dao.touchesSince(since) }

    /** Changes whenever any interaction is added, edited or deleted (R7 widget refresh). */
    val changes: Flow<Int> get() = dao.countFlow()

    /** Deletes one entry and returns it, so the caller can offer Undo ([restore]). */
    suspend fun delete(id: Long): Interaction? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        dao.delete(id)
        e.toModel(reveal = true)
    }

    /** Puts a deleted entry back (Undo), with its original time and key. */
    suspend fun restore(i: Interaction): Long? =
        runCatching { log(i.lookupKey, i.contactId, i.type, i.channel, i.time, i.note, i.dedupeKey) }.getOrNull()

    /** Undo of an automatic "Log this?" entry. */
    suspend fun deleteByDedupe(key: String) = withContext(Dispatchers.IO) { dao.deleteByDedupe(key) }

    internal suspend fun rekey(from: String, to: String, toId: Long?) = dao.rekey(from, to, toId)

    internal suspend fun forget(key: String) = dao.deleteFor(key)

    /** Stored keys with their last known contact id. */
    internal suspend fun keys(): List<Pair<String, Long?>> = dao.keys().map { it.lookupKey to it.contactId }
}
