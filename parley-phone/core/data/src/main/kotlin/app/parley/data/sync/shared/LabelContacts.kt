package app.parley.data.sync.shared

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import app.parley.common.people.ContactRef
import app.parley.common.record.ContactRecord
import app.parley.common.sync.shared.SharedCards
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.people.LabelsRepository
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The contacts of one label on this phone, as a shared label's sync reads and changes them. */
interface LabelContacts {
    /** A device contact in the label: its id, lookup key and shared card ([SharedCards.project]). */
    class Member(val id: Long, val key: String, val card: ContactRecord)

    suspend fun labelExists(title: String): Boolean

    /** The label's address-book contacts (private ones are never shared). */
    suspend fun members(title: String): List<Member>

    /** One contact as [members] gives it (whether or not it is in the label), or null when it is gone. */
    suspend fun card(id: Long): Member?

    /** How many private contacts are in the label (they stay on this phone). */
    suspend fun privateMembers(title: String): Int

    /** The contact a key names now (it can change after a sync or a link), or null when it is gone. */
    suspend fun idFor(key: String, lastId: Long?): Long?

    /** Puts [card]'s shared fields on contact [id], journaled for History & undo. Returns the id that holds it. */
    suspend fun apply(id: Long, card: ContactRecord): Long?

    /** Adds [card] as a new contact in the label. */
    suspend fun import(title: String, card: ContactRecord): Long?

    /** A contact not in [exclude] with one of [card]'s numbers or e-mails (a contact first received joins it). */
    suspend fun findMatch(card: ContactRecord, exclude: Set<Long>): Long?

    suspend fun addToLabel(title: String, id: Long): Boolean

    /** Deletes [id] (History & undo keeps it). */
    suspend fun delete(id: Long)

    suspend fun removeFromLabel(title: String, id: Long)

    fun keyOf(id: Long): String?

    /** After a run that changed contacts. */
    fun changed() {}
}

/** [LabelContacts] on the address book. */
class ProviderLabelContacts(
    private val context: Context,
    private val contacts: ContactsRepository,
    private val records: ContactRecordStore,
    private val labels: LabelsRepository,
    /** Where a label that doesn't exist yet is made (the "Save new contacts to" account). */
    private val defaultAccount: () -> AccountRef?,
) : LabelContacts {
    private suspend fun deviceIds(title: String): List<Long> =
        labels.members(title).filter { ContactRef.ofNavId(it) !is ContactRef.Private }.sorted()

    override suspend fun labelExists(title: String): Boolean = labels.label(title) != null

    override suspend fun members(title: String): List<LabelContacts.Member> = withContext(Dispatchers.IO) {
        val ids = deviceIds(title)
        if (ids.isEmpty()) return@withContext emptyList()
        val keys = records.heads(ids).associate { it.contactId to it.key }
        records.readAllById(ids, fullPhoto = false).mapNotNull { (id, r) ->
            keys[id]?.let { LabelContacts.Member(id, it, SharedCards.project(r)) }
        }.toList()
    }

    override suspend fun card(id: Long): LabelContacts.Member? = withContext(Dispatchers.IO) {
        val key = records.heads(listOf(id)).firstOrNull()?.key ?: return@withContext null
        records.read(id, fullPhoto = false)?.let { LabelContacts.Member(id, key, SharedCards.project(it)) }
    }

    override suspend fun privateMembers(title: String): Int = labels.members(title).count { ContactRef.ofNavId(it) is ContactRef.Private }

    override suspend fun idFor(key: String, lastId: Long?): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = if (lastId != null) {
                ContactsContract.Contacts.getLookupUri(lastId, key)
            } else {
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key)
            }
            ContactsContract.Contacts.lookupContact(context.contentResolver, uri)?.let { ContentUris.parseId(it) }
        }.getOrNull()
    }

    override suspend fun apply(id: Long, card: ContactRecord): Long? = withContext(Dispatchers.IO) {
        val local = records.read(id, fullPhoto = false) ?: return@withContext null
        val merged = SharedCards.overlay(local, card)
        contacts.recordChange(listOf(id), "EDIT")
        val (target, writable) = contacts.writableRaws(id)
        if (target == null) return@withContext null
        if (records.replaceContent(id, merged, target, writable)) id else null
    }

    override suspend fun import(title: String, card: ContactRecord): Long? = withContext(Dispatchers.IO) {
        val account = labels.label(title)?.groups?.firstOrNull()?.account ?: defaultAccount()
        records.insert(SharedCards.forImport(card, title), target = account)
    }

    override suspend fun findMatch(card: ContactRecord, exclude: Set<Long>): Long? = withContext(Dispatchers.IO) {
        val wanted = SharedCards.matchKeys(card)
        if (wanted.isEmpty()) return@withContext null
        // One pass over the address book, a page at a time; only the keys are kept.
        val ids = records.contactIds().filter { it !in exclude }
        for (page in ids.chunked(ContactRecordStore.BATCH)) {
            for ((id, r) in records.readAllById(page, fullPhoto = false)) {
                if (SharedCards.matchKeys(SharedCards.project(r)).any { it in wanted }) return@withContext id
            }
        }
        null
    }

    override suspend fun addToLabel(title: String, id: Long): Boolean {
        val label = labels.label(title) ?: return false
        return label.groups.any { g -> contacts.addToGroup(listOf(id), g) == 0 }
    }

    override suspend fun delete(id: Long) = contacts.delete(listOf(id))

    override suspend fun removeFromLabel(title: String, id: Long) = labels.removeMembers(title, listOf(id))

    override fun keyOf(id: Long): String? = contacts.lookupKeyOf(id)

    override fun changed() = contacts.refresh()
}
