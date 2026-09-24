package app.parley.data.messaging

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.edit
import app.parley.common.people.Batches
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.TemporaryContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** Where "Add several numbers…" saves (M11). */
sealed interface BulkDestination {
    /** Contacts in [account], in the label [label] (created when new; null: no label). */
    data class Label(val account: AccountRef, val label: String?) : BulkDestination

    /** Private contacts (the vault). */
    data object Private : BulkDestination

    /** Temporary contacts that delete themselves after [days]; private (vault) unless [private] is false. */
    data class Temporary(val days: Int, val private: Boolean = true) : BulkDestination
}

/** One number to save and its name. */
data class BulkItem(val name: String, val number: String)

/**
 * One saved batch: what "Undo this batch" and "Delete this batch" remove. Only ids are kept (never names or
 * numbers): the raw contacts Parley created, their lookup keys (temporary flags) and vault ids.
 */
data class BulkBatch(
    val tag: String,
    val at: Long,
    val where: String,
    val count: Int,
    val rawIds: List<Long>,
    val lookupKeys: List<String>,
    val vaultIds: List<Long>,
)

/**
 * M11: saves many numbers at once, in chunks of [CHUNK] (contacts through [app.parley.data.records.ContactRecordStore.insertAll],
 * private ones through the vault), as one batch with one undo. Batches are remembered for [KEEP_DAYS] days so a batch
 * can still be deleted later.
 */
class BulkAddStore(private val c: DataContainer) {
    private val prefs = c.appContext.getSharedPreferences("bulk_add", Context.MODE_PRIVATE)
    private val _batches = MutableStateFlow(load())
    val batches: StateFlow<List<BulkBatch>> = _batches.asStateFlow()

    /** Saved; [failed] lines say why a number wasn't. */
    data class Result(val batch: BulkBatch, val saved: Int, val failed: List<String>)

    suspend fun save(
        items: List<BulkItem>,
        destination: BulkDestination,
        where: String,
        progress: (Int, Int) -> Unit = { _, _ -> },
        now: Long = System.currentTimeMillis(),
    ): Result = withContext(Dispatchers.IO) {
        val rawIds = ArrayList<Long>()
        val keys = ArrayList<String>()
        val vaultIds = ArrayList<Long>()
        val failed = ArrayList<String>()
        var done = 0
        when (destination) {
            is BulkDestination.Label -> {
                val groups = c.records.groupResolver()
                for (chunk in items.chunked(CHUNK)) {
                    coroutineContext.ensureActive()
                    val results = c.records.insertAll(chunk.map { record(it, destination.label) }, destination.account, groups)
                    results.forEachIndexed { i, r ->
                        if (r.contactId != null) rawIds += r.rawIds else failed += "${chunk[i].name}: ${r.error ?: "not saved"}"
                    }
                    done += chunk.size
                    progress(done, items.size)
                }
                c.contacts.refresh()
            }
            BulkDestination.Private -> for (chunk in items.chunked(CHUNK)) {
                chunk.forEach { item ->
                    coroutineContext.ensureActive()
                    runCatching { c.vault.save(null, details(item)) }
                        .onSuccess { vaultIds += it; runCatching { c.messaging.forget(item.number) } }
                        .onFailure { failed += "${item.name}: ${it.message ?: "not saved"}" }
                }
                done += chunk.size
                progress(done, items.size)
            }
            is BulkDestination.Temporary -> for (chunk in items.chunked(CHUNK)) {
                chunk.forEach { item ->
                    coroutineContext.ensureActive()
                    val saved = runCatching { TemporaryContacts.save(c, item.name, item.number, destination.days, private = destination.private, now = now) }.getOrNull()
                    when {
                        saved == null -> failed += "${item.name}: not saved"
                        saved.private -> vaultIds += saved.id
                        else -> {
                            rawIds += c.contacts.rawIds(saved.id)
                            c.contacts.lookupKeyOf(saved.id)?.let { keys += it }
                        }
                    }
                }
                done += chunk.size
                progress(done, items.size)
            }
        }
        val batch = BulkBatch(app.parley.common.messaging.BulkAdd.batchTag(now), now, where, items.size - failed.size, rawIds, keys, vaultIds)
        if (batch.count > 0) remember(batch)
        Result(batch, batch.count, failed)
    }

    /**
     * "Undo this batch" (right after saving): removes exactly what the batch created, without keeping copies in
     * Recently deleted. [journal] true ("Delete this batch" later, when contacts may have been edited since) keeps
     * a 30-day copy of each one.
     */
    suspend fun remove(batch: BulkBatch, journal: Boolean) = withContext(Dispatchers.IO) {
        batch.lookupKeys.forEach { runCatching { c.temporaries.clear(it) } }
        if (batch.rawIds.isNotEmpty()) {
            if (journal) {
                c.contacts.deleteRaws(batch.rawIds)
            } else {
                val ops = batch.rawIds.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it)).build() }
                Batches.chunks(ops).forEach { runCatching { c.appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) } }
            }
        }
        batch.vaultIds.forEach { runCatching { c.vault.delete(it) } }
        c.contacts.refresh()
        forget(batch.tag)
    }

    /** Stops remembering a batch (its contacts stay). */
    fun forget(tag: String) = store(_batches.value.filterNot { it.tag == tag })

    private fun remember(b: BulkBatch) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        store((listOf(b) + _batches.value).filter { it.at >= cutoff }.take(MAX_BATCHES))
    }

    private fun record(item: BulkItem, label: String?): ContactRecord {
        val rows = buildList {
            // The name as the given name only, so Android doesn't split "Lead 01" into a first and a last name.
            add(DataRow(Mime.NAME, mapOf(Col.D2 to item.name)))
            add(DataRow(Mime.PHONE, mapOf(Col.D1 to item.number, Col.D2 to Phone.TYPE_MOBILE.toString())))
            if (!label.isNullOrBlank()) add(DataRow(Mime.GROUP, mapOf(Col.GROUP_TITLE to label.trim())))
        }
        return ContactRecord(key = "", displayName = item.name, raws = listOf(RawRecord(null, null, rows = rows)))
    }

    private fun details(item: BulkItem) = ContactDetails(given = item.name, phones = listOf(DataItem(value = item.number, type = Phone.TYPE_MOBILE)))

    private fun store(list: List<BulkBatch>) {
        _batches.value = list
        val a = JSONArray()
        list.forEach { b ->
            a.put(
                JSONObject().put("tag", b.tag).put("at", b.at).put("where", b.where).put("count", b.count)
                    .put("raw", JSONArray(b.rawIds)).put("keys", JSONArray(b.lookupKeys)).put("vault", JSONArray(b.vaultIds)),
            )
        }
        prefs.edit { putString(K_BATCHES, a.toString()) }
    }

    private fun load(): List<BulkBatch> = runCatching {
        val a = JSONArray(prefs.getString(K_BATCHES, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun longs(k: String) = o.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.getLong(it) } }.orEmpty()
            BulkBatch(
                o.getString("tag"), o.getLong("at"), o.optString("where"), o.optInt("count"), longs("raw"),
                o.optJSONArray("keys")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty(), longs("vault"),
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        const val CHUNK = 50
        const val KEEP_DAYS = 30
        private const val MAX_BATCHES = 20
        private const val K_BATCHES = "batches"
    }
}
