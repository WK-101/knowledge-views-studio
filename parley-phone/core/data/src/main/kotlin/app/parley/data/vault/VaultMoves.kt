package app.parley.data.vault

import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.record.withoutMessengers
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moving contacts into and out of the private vault without losing anything (F4).
 *
 * In: the vault keeps the lossless [ContactRecord] (IM, SIP, department, PO box, custom rows, the photo…) beside the
 * editable details, sealed with the strong key. Phone-only and never-synced copies are purged at once; synced copies
 * are deleted and disappear from other apps after the account's next sync.
 *
 * Out: the record is inserted back as it was (accounts kept when still writable here); edits made in the vault since
 * are applied on top.
 */
class VaultMoves(
    private val vault: VaultRepository,
    private val contacts: ContactsRepository,
    private val records: ContactRecordStore,
) {
    data class MovedIn(val vaultId: Long, val removedAfterSync: Boolean)

    /** Throws [VaultCrypto.LockedException] when the vault must be unlocked first; nothing is deleted then. */
    suspend fun moveIn(contactId: Long, shown: ContactDetails): MovedIn = withContext(Dispatchers.IO) {
        val record = records.read(contactId, fullPhoto = true)?.let { capPhoto(contactId, it) }?.withoutMessengers()
        val id = vault.save(null, shown, record = record)
        val synced = contacts.purgeForVault(contactId)
        contacts.refresh()
        MovedIn(id, synced)
    }

    /**
     * Moves vault entry [vaultId] back to the phone contacts; [d] are its current details and [account] the fallback
     * for entries without a stored record. Returns the new contact id, or null (then the vault entry stays).
     */
    suspend fun moveOut(vaultId: Long, d: ContactDetails, account: AccountRef): Long? = withContext(Dispatchers.IO) {
        val stored = vault.storedRecord(vaultId)
        val newId = if (stored == null) {
            contacts.save(null, d, account, null, false)
        } else {
            val id = records.insert(stored.record, target = null) ?: return@withContext null
            if (stored.editedSince) {
                contacts.editable(id)?.let { original -> contacts.save(original, overlay(original, d), null, null, false) } ?: id
            } else {
                id
            }
        }
        if (newId != null) vault.delete(vaultId)
        contacts.refresh()
        newId
    }

    /** A full-resolution photo can be several MB; keep the vault row small by using the thumbnail then. */
    private fun capPhoto(contactId: Long, r: ContactRecord): ContactRecord {
        val big = r.raws.any { raw -> raw.rows.any { it.mimeType == Mime.PHOTO && (it.blob?.size ?: 0) > MAX_PHOTO } }
        if (!big) return r
        val thumbs = records.read(contactId, fullPhoto = false)?.raws.orEmpty().associateBy { it.rawId }
        return r.copy(
            raws = r.raws.map { raw ->
                val thumb = thumbs[raw.rawId]?.rows?.firstOrNull { it.mimeType == Mime.PHOTO }
                raw.copy(rows = raw.rows.mapNotNull { row -> if (row.mimeType == Mime.PHOTO && (row.blob?.size ?: 0) > MAX_PHOTO) thumb else row })
            },
        )
    }

    companion object {
        private const val MAX_PHOTO = 512 * 1024

        /**
         * [original] (the restored contact, with row ids) changed to the edited vault [d]: single fields are taken
         * from [d]; list rows keep their id when [d] still has the same value, others are added or removed.
         */
        fun overlay(original: ContactDetails, d: ContactDetails): ContactDetails {
            fun items(old: List<DataItem>, new: List<DataItem>): List<DataItem> {
                val pool = old.toMutableList()
                return new.map { n ->
                    val i = pool.indexOfFirst { it.value.trim() == n.value.trim() }
                    if (i >= 0) n.copy(id = pool.removeAt(i).id) else n.copy(id = null)
                }
            }
            val addrPool = original.addresses.toMutableList()
            val events = original.events.toMutableList()
            return original.copy(
                prefix = d.prefix, given = d.given, middle = d.middle, family = d.family, suffix = d.suffix,
                phoneticGiven = d.phoneticGiven, phoneticFamily = d.phoneticFamily, nickname = d.nickname,
                company = d.company, title = d.title, note = d.note,
                phones = items(original.phones, d.phones), emails = items(original.emails, d.emails),
                websites = items(original.websites, d.websites), relations = items(original.relations, d.relations),
                addresses = d.addresses.map { a ->
                    val i = addrPool.indexOfFirst { it.formatted == a.formatted }
                    if (i >= 0) a.copy(id = addrPool.removeAt(i).id) else a.copy(id = null)
                },
                events = d.events.map { e ->
                    val i = events.indexOfFirst { it.date == e.date && it.type == e.type }
                    if (i >= 0) e.copy(id = events.removeAt(i).id) else e.copy(id = null)
                },
            )
        }
    }
}
