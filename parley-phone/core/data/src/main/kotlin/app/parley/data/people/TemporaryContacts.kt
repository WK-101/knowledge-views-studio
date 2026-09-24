package app.parley.data.people

import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import app.parley.common.people.TemporaryExpiry
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.db.TemporaryContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Temporary contacts: people saved for a few days (a plumber, a delivery driver, someone you messaged once) that
 * delete themselves. **The one API for them**; screens and workers never write the table directly (F2).
 *
 * Safety rules:
 * - Parley records the raw contacts that make up a temporary contact and only ever deletes those. If the person now
 *   has other raw contacts (merged by the user or linked by Android), those stay, and so does the call history.
 * - Linking a temporary contact with a real one (Duplicates, multi-select merge, the picker's "add to existing")
 *   clears the flag: the user chose to keep the details with a real person.
 * - The first real edit asks "Keep this contact?" ([needsKeepPrompt] / [answerKeep]).
 *
 * Private temporary contacts live in the vault (never visible to other apps) and expire through the vault's own
 * `expiresAt`.
 */
class TemporaryContacts(private val c: DataContainer) {
    private val mutex = Mutex()

    /** Every temporary phone contact, soonest first. */
    val all: Flow<List<TemporaryContactEntity>> get() = c.meta.temporaryContacts()

    /** What [create] made: a phone contact id, or a private (vault) contact when [private] (then [id] is `-vaultId`). */
    data class Created(val id: Long, val private: Boolean) {
        val vaultId: Long? get() = if (private) -id else null
    }

    /** A temporary contact expired but not everything was deleted; tell the user. */
    data class Notice(val name: String, val text: String)

    /**
     * Saves [number] as a temporary contact named [name] that deletes itself after [days] days.
     *
     * - `private = false`: a phone-only contact (no account, never synced). Other apps with contacts access can see
     *   it until it expires. Its call history is purged with it.
     * - `private = true`: a private (vault) contact, encrypted inside Parley and invisible to other apps; the vault
     *   removes it (and its private call history) when it expires. Needs the vault key; throws the vault's
     *   locked exception when it must be unlocked first.
     *
     * Returns null when nothing could be saved.
     */
    suspend fun create(number: String, name: String, days: Int = DEFAULT_DAYS, private: Boolean = false): Created? {
        val details = ContactDetails(
            given = name.trim().ifEmpty { number },
            phones = listOf(DataItem(value = number, type = Phone.TYPE_MOBILE)),
        )
        val expiresAt = System.currentTimeMillis() + days * DAY
        if (private) {
            val id = c.vault.save(null, details, expiresAt)
            return Created(-id, true)
        }
        return mutex.withLock {
            // Kept on this phone only (never synced to an account): it's meant to disappear.
            val id = c.contacts.save(null, details, null, null, false) ?: return@withLock null
            val raw = c.contacts.lastSavedRawId
            val key = c.contacts.lookupKeyOf(id)?.takeIf { it.isNotEmpty() } ?: return@withLock Created(id, false)
            c.meta.setTemporary(
                TemporaryContactEntity(
                    key, id, expiresAt, purgeHistory = true,
                    rawIds = raw?.let { TemporaryExpiry.encodeIds(listOf(it)) }, name = details.given,
                ),
            )
            Created(id, false)
        }
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
                val name = t.name ?: c.contacts.contacts.value?.firstOrNull { it.lookupKey == t.lookupKey }?.displayName ?: "A temporary contact"
                val numbers = if (d.purgeHistory) numbersOf(d.deleteRaws) else emptyList()
                if (d.deleteRaws.isNotEmpty()) {
                    // Journaled first; if that or the delete fails the entry stays and is retried tomorrow.
                    if (runCatching { c.contacts.deleteRaws(d.deleteRaws) }.isFailure) return@withLock
                }
                c.meta.clearTemporary(t.lookupKey)
                // Only numbers no remaining contact uses lose their call history.
                numbers.filter { c.contacts.isContact(it) == false }.forEach { n -> runCatching { c.history.purgeNumber(n) } }
                if (d.keptMerged) {
                    notices += Notice(
                        name,
                        if (d.deleteRaws.isNotEmpty()) "$name expired; the details you merged were kept"
                        else "$name expired, but it was merged with another contact, so nothing was deleted",
                    )
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
        const val DEFAULT_DAYS = 7
        private const val DAY = 86_400_000L
    }
}
