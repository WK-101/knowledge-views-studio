package app.parley.data.people

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.RawContacts
import app.parley.common.record.ContactRecord
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Saved in" account actions on the contact page: move one copy to another account, or unlink it.
 * Every change is journaled first (30-day undo in "Recently deleted & changed").
 */
class ContactMover(context: Context, private val contacts: ContactsRepository, private val records: ContactRecordStore) {
    private val cr = context.contentResolver

    sealed interface Result {
        data class Done(val contactId: Long?) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Lossless move of raw contact [rawId] into [target]: every data row (including photo and labels, resolved by
     * title in the new account) is inserted as a new raw contact there, linked back to the contact's other copies,
     * and only then is the old raw contact deleted. Nothing is deleted if the copy can't be written.
     */
    suspend fun move(contactId: Long, rawId: Long, target: AccountRef): Result = withContext(Dispatchers.IO) {
        val raws = rawIds(contactId)
        if (rawId !in raws) return@withContext Result.Failed("That copy no longer exists")
        val record = records.read(contactId, fullPhoto = true) ?: return@withContext Result.Failed("Couldn't read the contact")
        // By id, not by position: the two reads may list the copies differently.
        val raw = record.raws.firstOrNull { it.rawId == rawId } ?: return@withContext Result.Failed("Couldn't read that copy")
        if (raw.accountType == target.type && raw.accountName == target.name) return@withContext Result.Failed("It's already saved there")

        contacts.recordChange(listOf(contactId), "MOVE")
        if (contacts.lastJournalIds.isEmpty()) return@withContext Result.Failed("Couldn't keep an undo copy, so nothing was moved")

        val single = ContactRecord(record.key, record.displayName, record.starred, record.customRingtone, record.sendToVoicemail, listOf(raw))
        val inserted = records.insertAll(listOf(single), target).single()
        val newRaw = inserted.rawIds.firstOrNull() ?: return@withContext Result.Failed(inserted.error ?: "Couldn't write the copy in the new account")

        val others = raws.filter { it != rawId }
        if (others.isNotEmpty()) setAggregation(listOf(newRaw) + others, AggregationExceptions.TYPE_KEEP_TOGETHER)
        cr.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), null, null)
        contacts.refresh()
        Result.Done(contactIdForRaw(newRaw))
    }

    /** Separates one copy from the others, so it becomes its own contact. Returns that contact's id. */
    suspend fun unlink(contactId: Long, rawId: Long): Result = withContext(Dispatchers.IO) {
        val raws = rawIds(contactId)
        if (rawId !in raws || raws.size < 2) return@withContext Result.Failed("There's nothing to unlink")
        contacts.recordChange(listOf(contactId), "SEPARATE")
        val ops = ArrayList<ContentProviderOperation>()
        raws.filter { it != rawId }.forEach { other ->
            ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_SEPARATE)
                .withValue(AggregationExceptions.RAW_CONTACT_ID1, rawId)
                .withValue(AggregationExceptions.RAW_CONTACT_ID2, other)
                .build()
        }
        cr.applyBatch(ContactsContract.AUTHORITY, ops)
        contacts.refresh()
        Result.Done(contactIdForRaw(rawId))
    }

    /** The contact's live raw contact ids. */
    private fun rawIds(contactId: Long): List<Long> = try {
        cr.query(
            RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()), RawContacts._ID,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun setAggregation(raws: List<Long>, type: Int) {
        val ops = ArrayList<ContentProviderOperation>()
        for (i in raws.indices) for (j in i + 1 until raws.size) {
            ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                .withValue(AggregationExceptions.TYPE, type)
                .withValue(AggregationExceptions.RAW_CONTACT_ID1, raws[i])
                .withValue(AggregationExceptions.RAW_CONTACT_ID2, raws[j])
                .build()
        }
        if (ops.isNotEmpty()) cr.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    private fun contactIdForRaw(rawId: Long): Long? = try {
        cr.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (_: Exception) {
        null
    }
}
