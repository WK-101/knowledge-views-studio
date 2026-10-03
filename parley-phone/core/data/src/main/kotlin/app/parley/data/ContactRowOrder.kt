package app.parley.data

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import app.parley.common.people.Handles
import app.parley.common.people.RowOrder

/**
 * Keeps the order the editor gave a contact's rows of each kind (Move up / Move down), for [ContactsRepository.save].
 * The provider has no order column and every app lists a kind's rows by `_ID`, so the rows that must come later are
 * deleted and inserted again in the chosen order ([RowOrder.rewrite]). Only rows of the copy being edited are ever
 * written (the editor holds no others), never a read-only one, and each keeps every column it had: the default
 * flags (`IS_PRIMARY`, `IS_SUPER_PRIMARY`) and the data columns the editor doesn't show (an email's display name, an
 * address's RFC 9554 parts, a date's calendar…). The sync adapter's own per-row columns (`DATA_SYNC1`–`4`) aren't
 * copied: they describe the old row on the server, and the new row is uploaded as the contact's next change.
 */
internal object ContactRowOrder {
    /** The saved rows of [edited] to write again so that every kind reads back in its edited order. */
    fun rewrite(original: ContactDetails?, edited: ContactDetails, locked: Set<Long>): Set<Long> {
        if (original == null) return emptySet()
        fun order(ids: List<Long?>) = RowOrder.rewrite(ids, locked)
        // A handle whose kind changed (XMPP to SIP) is replaced anyway, so it counts as a new row.
        val handleMime = original.handles.associate { it.id to Handles.toColumns(it.handle).first }
        return buildSet {
            addAll(order(edited.phones.filter { it.value.isNotBlank() }.map { it.id }))
            addAll(order(edited.emails.filter { it.value.isNotBlank() }.map { it.id }))
            addAll(order(edited.websites.filter { it.value.isNotBlank() }.map { it.id }))
            addAll(order(edited.relations.filter { it.value.isNotBlank() }.map { it.id }))
            addAll(order(edited.events.filter { it.date.isNotBlank() }.map { it.id }))
            addAll(order(edited.addresses.filter { !it.isBlank }.map { it.id }))
            addAll(order(edited.handles.filter { it.value.isNotBlank() }.map { h -> h.id?.takeIf { handleMime[it] == Handles.toColumns(h.handle).first } }))
            addAll(order(edited.customFields.filter { !it.isBlank }.map { it.id }))
        }
    }

    /** Every column a rewritten row keeps, by row id (rows that are gone are missing). */
    fun columns(cr: ContentResolver, ids: Set<Long>): Map<Long, ContentValues> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, ContentValues>()
        val projection = arrayOf(Data._ID, Data.MIMETYPE) + KEPT
        cr.safeQuery(Data.CONTENT_URI, projection, "${Data._ID} IN (${ids.joinToString(",")})")?.use { c ->
            while (c.moveToNext()) {
                val v = ContentValues()
                KEPT.forEachIndexed { i, col -> put(v, col, c, i + 2) }
                // Android works the matching form of a number out again from the number as saved.
                if (c.getString(1) == Phone.CONTENT_ITEM_TYPE) v.remove(Phone.NORMALIZED_NUMBER)
                out[c.getLong(0)] = v
            }
        }
        return out
    }

    private fun put(v: ContentValues, col: String, c: Cursor, i: Int) {
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> Unit
            Cursor.FIELD_TYPE_INTEGER -> v.put(col, c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> v.put(col, c.getDouble(i))
            Cursor.FIELD_TYPE_BLOB -> v.put(col, c.getBlob(i))
            else -> v.put(col, c.getString(i))
        }
    }

    private val KEPT: Array<String> = arrayOf(Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY) +
        arrayOf(
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8,
            Data.DATA9, Data.DATA10, Data.DATA11, Data.DATA12, Data.DATA13, Data.DATA14, Data.DATA15,
        )
}
