package app.parley.data

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.provider.ContactsContract
import app.parley.common.people.Batches

/**
 * Sends independent operations (deletes, label changes, updates of rows by id) to the contacts provider in batches it
 * accepts, however many there are: at most [Batches.MAX_OPS] per batch, with a yield point every
 * [Batches.YIELD_EVERY] so the provider commits as it goes and other readers aren't held up. A single applyBatch
 * with more than 500 operations between yield points is refused outright, which made "select all, delete" fail.
 *
 * Not for operations that refer back to earlier ones (an inserted raw contact and its rows) or that must commit
 * together: a batch boundary or a yield point would split them. Each batch that succeeds stays applied when a later
 * one fails, as each deleted contact stays deleted.
 */
fun ContentResolver.applyInBatches(
    ops: List<ContentProviderOperation.Builder>,
    authority: String = ContactsContract.AUTHORITY,
): List<ContentProviderResult> {
    val out = ArrayList<ContentProviderResult>(ops.size)
    for (chunk in Batches.chunks(ops)) {
        val batch = ArrayList<ContentProviderOperation>(chunk.size)
        chunk.forEachIndexed { i, b -> batch += b.withYieldAllowed(Batches.yieldsAt(i)).build() }
        out += applyBatch(authority, batch)
    }
    return out
}
