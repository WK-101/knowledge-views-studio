package app.parley.data

import android.util.Base64
import app.parley.common.catching
import app.parley.common.backup.PhotoRefs
import app.parley.common.backup.RecordJson
import app.parley.common.memory.NumberMemory
import app.parley.common.record.Mime
import app.parley.data.db.JournalEntity
import app.parley.data.db.JournalPhotoEntity
import app.parley.data.db.JournalRow
import app.parley.data.db.MetaDao
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Keeps a lossless copy of a contact before Parley deletes, edits or merges it, so the user can
 * undo for 30 days ("Recently deleted & changed"). Stored only in Parley's private database.
 */
class JournalRepository(private val dao: MetaDao, private val records: ContactRecordStore) {

    fun recent(days: Int = 30): Flow<List<JournalRow>> = dao.journal(System.currentTimeMillis() - days * 86_400_000L)

    /**
     * Snapshots contacts; returns the journal ids (for "Undo"). A contact that can't be read throws (so a delete never
     * goes ahead without its copy); one that no longer exists has nothing to keep and is skipped.
     */
    suspend fun snapshot(contactIds: Collection<Long>, action: String): List<Long> = withContext(Dispatchers.IO) {
        contactIds.mapNotNull { id ->
            val record = records.read(id, fullPhoto = true) ?: return@mapNotNull null
            // Photos are kept once by hash beside the copies: the same photo in a thousand edits is stored once.
            val photos = LinkedHashMap<String, ByteArray>()
            val line = RecordJson.encode(record) { hash, bytes -> photos[hash] = bytes }
            val payload = JSONObject().put("record", line).toString().toByteArray()
            val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(payload) } }.toByteArray()
            journalPhotoLock.withLock {
                for ((hash, bytes) in photos) if (dao.journalPhotoKept(hash) == 0) dao.addJournalPhoto(JournalPhotoEntity(hash, bytes))
                dao.addJournal(
                    JournalEntity(
                        contactKey = record.key, displayName = record.displayName, action = action, time = System.currentTimeMillis(),
                        photoHashes = photos.keys.joinToString(",").ifEmpty { null }, payload = zipped,
                    ),
                )
            }
        }
    }

    /** Number memory: changes whenever a contact is journaled, restored or forgotten. */
    suspend fun memoryStamp(): String = recent().first().joinToString(",") { "${it.id}:${it.restored}" }

    /** Number memory: contacts deleted in Parley and not restored, with their numbers (each copy opened once). */
    suspend fun deletedForMemory(): List<NumberMemory.Deleted> = withContext(Dispatchers.IO) {
        recent().first().filter { it.action == "DELETE" && !it.restored }.mapNotNull { row ->
            val e = runCatching { dao.journalEntry(row.id) }.getOrNull() ?: return@mapNotNull null
            val json = runCatching { JSONObject(String(GZIPInputStream(e.payload.inputStream()).use { it.readBytes() })) }.getOrNull() ?: return@mapNotNull null
            // Photos aren't needed for the numbers: they stay hashes, so a contact with a photo is read too.
            val record = catching { RecordJson.decodeLight(json.getString("record")) }.getOrNull() ?: return@mapNotNull null
            val numbers = record.raws.flatMap { r -> r.rows.filter { it.mimeType == Mime.PHONE }.mapNotNull { it["data1"] } }
            NumberMemory.Deleted(row.id, row.contactKey, row.displayName, numbers, row.time)
        }
    }

    /** Forgets every journaled copy of a contact (it moved into the private vault). */
    suspend fun forget(key: String) {
        dao.deleteJournalFor(key)
        dropUnusedJournalPhotos(dao)
    }

    /** Forgets copies older than [before] (daily upkeep) and the photos only they needed. */
    suspend fun prune(before: Long) {
        dao.pruneJournal(before)
        dropUnusedJournalPhotos(dao)
    }

    private val restoring = Mutex()

    /**
     * Re-creates the contact exactly as it was (in its original accounts). Returns the new contact id, or null when it
     * failed or was already restored (a double tap or a retry never makes a second copy).
     */
    suspend fun restore(entryId: Long): Long? = withContext(Dispatchers.IO) { restoring.withLock { restoreLocked(entryId) } }

    private suspend fun restoreLocked(entryId: Long): Long? {
        val e = dao.journalEntry(entryId) ?: return null
        if (e.restored) return null
        val json = JSONObject(String(GZIPInputStream(e.payload.inputStream()).use { it.readBytes() }))
        // A copy written before photos were kept apart holds them itself.
        val blobs = json.optJSONObject("blobs") ?: JSONObject()
        // A kept photo that is gone or can't be opened leaves the contact without it: the rest still comes back.
        val kept = e.photoHashes?.split(',').orEmpty().mapNotNull { h -> catching { dao.journalPhoto(h) }.getOrNull()?.let { h to it.blob } }.toMap()
        val record = PhotoRefs.filled(RecordJson.decodeLight(json.getString("record"))) { hash ->
            val bytes = blobs.optString(hash).takeIf { it.isNotEmpty() }?.let { catching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() } ?: kept[hash]
            bytes?.takeIf { RecordJson.sha256Hex(it) == hash }
        }
        val id = records.insert(record, target = null)
        if (id != null) dao.markRestored(entryId)
        return id
    }
}

/** Taken while journal photos are added with their copy, or dropped: a photo is never dropped between the two. */
private val journalPhotoLock = Mutex()

/** Drops the journal photos no copy names any more (after copies were pruned, cleared or forgotten). */
internal suspend fun dropUnusedJournalPhotos(dao: MetaDao) = journalPhotoLock.withLock {
    val used = dao.journalPhotoRefs().flatMapTo(HashSet()) { it.split(',') }
    dao.journalPhotoHashes().filter { it !in used }.chunked(500).forEach { dao.deleteJournalPhotos(it) }
}
