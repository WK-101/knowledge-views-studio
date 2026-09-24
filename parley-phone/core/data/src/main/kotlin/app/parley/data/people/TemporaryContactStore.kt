package app.parley.data.people

import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import app.parley.common.people.TemporaryExpiry
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataContainer
import app.parley.data.db.TemporaryContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Visible (phone) temporary contacts: people saved for a few days that delete themselves (F2). New ones are created
 * through the facade [app.parley.data.TemporaryContacts.save], which is the single entry point for both kinds
 * (private ones live in the vault and expire with its `expiresAt`); it calls [createPhone] here. Screens and workers
 * use this store to mark, keep, clear and expire them and never write the table directly.
 *
 * Safety rules:
 * - Parley records the raw contacts that make up a temporary contact and only ever deletes those. If the person now
 *   has other raw contacts (merged by the user or linked by Android), those stay, and so does the call history.
 * - Linking a temporary contact with a real one (Duplicates, multi-select merge, the picker's "add to existing")
 *   clears the flag: the user chose to keep the details with a real person.
 * - The first real edit asks "Keep this contact?" ([needsKeepPrompt] / [answerKeep]).
 */
class TemporaryContactStore(private val c: DataContainer) {
    private val mutex = Mutex()

    /** Every temporary phone contact, soonest first. */
    val all: Flow<List<TemporaryContactEntity>> get() = c.meta.temporaryContacts()

    /** A temporary contact expired but not everything was deleted; tell the user. */
    /**
     * An expired temporary contact that was merged with another one. [name] is null when unknown (the app says
     * "A temporary contact"); [keptDetails] is true when the merged-in details were kept and the rest deleted,
     * false when nothing was deleted.
     */
    data class Notice(val name: String?, val keptDetails: Boolean)

    /**
     * Saves [details] as a phone-only contact (no account, never synced) that deletes itself at [expiresAt], recording
     * the raw contact it created (and only that one). Returns the contact and raw ids, or null when nothing could be
     * saved. Use [app.parley.data.TemporaryContacts.save] rather than calling this directly.
     */
    suspend fun createPhone(details: ContactDetails, expiresAt: Long, purgeHistory: Boolean = true): ContactsRepository.SaveResult? = mutex.withLock {
        val saved = c.contacts.save(null, details, null, null, false) ?: return@withLock null
        val raw = saved.rawId
        // The lookup key can briefly be unreadable right after the insert (aggregation runs asynchronously): retry,
        // and if it still isn't there, record the entry by its raw contact so it is never left without an expiry.
        var key: String? = null
        for (attempt in 0 until KEY_TRIES) {
            key = c.contacts.lookupKeyOf(saved.contactId)?.takeIf { it.isNotEmpty() }
            if (key != null) break
            delay(KEY_RETRY_MS)
        }
        val recordKey = key ?: raw?.let(TemporaryExpiry::pendingKey)
        if (recordKey != null) {
            c.meta.setTemporary(
                TemporaryContactEntity(
                    recordKey, saved.contactId, expiresAt, purgeHistory,
                    rawIds = raw?.let { TemporaryExpiry.encodeIds(listOf(it)) }, name = details.composedName.ifBlank { null },
                ),
            )
        }
        saved
    }

    /**
     * Makes an existing contact delete itself after [days] days ([days] null: keep it, clearing the flag). All of
     * its current raw contacts are recorded, since the user chose this whole contact.
     */
    suspend fun mark(contactId: Long, days: Int?, purgeHistory: Boolean = true) = withContext(Dispatchers.IO) {
        val key = c.contacts.lookupKeyOf(contactId) ?: return@withContext
        mutex.withLock {
            if (days == null) {
                c.meta.clearTemporary(key)
                return@withLock
            }
            val name = c.contacts.contacts.value?.firstOrNull { it.id == contactId }?.displayName
            c.meta.setTemporary(
                TemporaryContactEntity(
                    key, contactId, System.currentTimeMillis() + days * DAY, purgeHistory,
                    rawIds = TemporaryExpiry.encodeIds(c.contacts.rawIds(contactId)), name = name,
                ),
            )
        }
    }

    suspend fun clear(lookupKey: String) = mutex.withLock { c.meta.clearTemporary(lookupKey) }

    suspend fun forKey(lookupKey: String): TemporaryContactEntity? = c.meta.temporary(lookupKey)

    /** True once per temporary contact: after its first real edit, ask "Keep this contact?". */
    suspend fun needsKeepPrompt(lookupKey: String): Boolean = c.meta.temporary(lookupKey)?.keepAsked == false

    /** The user's answer to "Keep this contact?": keep clears the flag; otherwise it still expires as planned. */
    suspend fun answerKeep(lookupKey: String, keep: Boolean) = mutex.withLock {
        val t = c.meta.temporary(lookupKey) ?: return@withLock
        if (keep) c.meta.clearTemporary(lookupKey) else c.meta.setTemporary(t.copy(keepAsked = true))
    }

    /**
     * After contacts were linked ([before]: their ids and keys): when a real contact is among them the temporary flag
     * goes (the user kept the details with a real person); when all were temporary, the result stays temporary.
     * Returns true when a flag was cleared.
     */
    suspend fun onJoined(before: List<Pair<Long, String>>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val temps = before.mapNotNull { (id, key) -> c.meta.temporary(key)?.let { it to id } }
            if (temps.isEmpty()) return@withLock false
            val merged = before.firstNotNullOfOrNull { (id, key) -> c.contacts.currentOf(key, id) }
            val keep = TemporaryExpiry.afterJoin(
                temps.map { (t, id) -> (TemporaryExpiry.decodeIds(t.rawIds) ?: c.contacts.rawIds(id).toSet()) to t.expiresAt },
                joinedContacts = before.size,
            )
            temps.forEach { (t, _) -> c.meta.clearTemporary(t.lookupKey) }
            if (keep != null && merged != null) {
                val first = temps.first().first
                c.meta.setTemporary(first.copy(lookupKey = merged.second, contactId = merged.first, expiresAt = keep.second, rawIds = TemporaryExpiry.encodeIds(keep.first)))
                false
            } else {
                true
            }
        }
    }

    /**
     * Deletes what expired by [now] (see the class rules). Returns notices for contacts that were only partly
     * deleted, or not at all because they had been merged into someone else.
     */
    suspend fun expire(now: Long = System.currentTimeMillis()): List<Notice> = withContext(Dispatchers.IO) {
        val notices = ArrayList<Notice>()
        for (t in c.meta.expiredContacts(now)) {
            mutex.withLock {
                val stored = TemporaryExpiry.decodeIds(t.rawIds)
                val current: Set<Long> = if (stored != null) {
                    c.contacts.contactsOfRaws(stored).values.toSet().flatMap { c.contacts.rawIds(it) }.toSet()
                } else {
                    c.contacts.resolve(t.lookupKey, t.contactId)?.let { c.contacts.rawIds(it).toSet() }.orEmpty()
                }
                val d = TemporaryExpiry.decide(stored, current, t.purgeHistory)
                val name = t.name ?: c.contacts.contacts.value?.firstOrNull { it.lookupKey == t.lookupKey }?.displayName
                val numbers = numbersOf(d.deleteRaws)
                if (d.deleteRaws.isNotEmpty()) {
                    // Journaled first; if that or the delete fails the entry stays and is retried tomorrow.
                    if (runCatching { c.contacts.deleteRaws(d.deleteRaws) }.isFailure) return@withLock
                }
                c.meta.clearTemporary(t.lookupKey)
                // Only numbers no remaining contact uses lose their call history.
                val unused = numbers.filter { c.contacts.isContact(it) == false }
                // F13: they leave the "last messaged" record too.
                unused.forEach { n -> runCatching { c.messaging.forget(n) } }
                if (d.purgeHistory) unused.forEach { n -> runCatching { c.history.purgeNumber(n) } }
                if (d.keptMerged) {
                    notices += Notice(name, keptDetails = d.deleteRaws.isNotEmpty())
                }
            }
        }
        notices
    }

    private fun numbersOf(rawIds: Set<Long>): List<String> {
        if (rawIds.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        runCatching {
            c.appContext.contentResolver.query(
                Data.CONTENT_URI, arrayOf(Phone.NUMBER),
                "${Data.RAW_CONTACT_ID} IN (${rawIds.joinToString(",")}) AND ${Data.MIMETYPE}=?", arrayOf(Phone.CONTENT_ITEM_TYPE), null,
            )?.use { q -> while (q.moveToNext()) q.getString(0)?.let { out += it } }
        }
        return out.distinct()
    }

    companion object {
        private const val DAY = 86_400_000L
        private const val KEY_TRIES = 5
        private const val KEY_RETRY_MS = 200L
    }
}
