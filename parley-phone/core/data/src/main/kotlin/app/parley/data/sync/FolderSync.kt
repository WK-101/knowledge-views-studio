package app.parley.data.sync

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.ContactsContract
import app.parley.common.PhoneIdentity
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.parley.common.Duplicates
import app.parley.common.StoredStatus
import app.parley.common.backup.RecordJson
import app.parley.common.backup.SyncCrypto
import app.parley.common.security.Bounded
import app.parley.common.security.LimitExceededException
import app.parley.data.security.RecordCrypto
import android.util.Base64
import app.parley.common.sync.FolderSyncRules
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.record.withoutMessengers
import app.parley.common.vcard.VCardMapper
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
import app.parley.data.records.ContactRecordStore
import ezvcard.Ezvcard
import ezvcard.VCardVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import android.content.res.Resources
import app.parley.data.R

data class SyncStatus(
    val folderUri: String? = null,
    val folderName: String? = null,
    val auto: Boolean = true,
    val lastSyncAt: Long = 0,
    /** Stored as a [app.parley.common.StoredStatus] (older versions: text); shown with [resultText]. */
    val lastResult: String? = null,
    /** Deletions a paused run is waiting for the user to confirm. */
    val pendingDeletions: Int = 0,
    /** Encrypted or plain files; unset for a folder set up before encryption existed (it waits for a choice). */
    val mode: SyncMode = SyncMode.UNSET,
) {
    /** The last result in the current language (rendered now, not when it was stored). */
    fun resultText(res: Resources): String? {
        val s = StoredStatus.decode(lastResult) ?: return lastResult
        return when (s.kind) {
            NO_PERMISSION -> res.getString(R.string.data_sync_no_permission)
            FOLDER_GONE -> res.getString(R.string.data_sync_folder_gone)
            PAUSED -> res.getQuantityString(R.plurals.data_sync_paused, s.int(0), s.int(0))
            CHOOSE_MODE -> res.getString(R.string.data_sync_choose_mode)
            REPORT -> SyncReport(s.int(0), s.int(1), s.int(2), s.int(3), s.int(4), s.int(5), s.int(6)).summary(res)
            else -> null
        }
    }

    companion object {
        const val NO_PERMISSION = "no_permission"
        const val FOLDER_GONE = "folder_gone"
        const val PAUSED = "paused"
        const val REPORT = "report"
        const val CHOOSE_MODE = "choose_mode"
    }
}

data class SyncReport(
    val written: Int = 0,
    val imported: Int = 0,
    val updatedFromFolder: Int = 0,
    val deletedLocal: Int = 0,
    val deletedFiles: Int = 0,
    val conflicts: Int = 0,
    val linked: Int = 0,
) {
    fun summary(res: Resources): String = buildList {
        fun n(id: Int, v: Int) = res.getQuantityString(id, v, v)
        if (imported > 0) add(n(R.plurals.data_sync_imported, imported))
        if (updatedFromFolder > 0) add(n(R.plurals.data_sync_updated, updatedFromFolder))
        if (written > 0) add(n(R.plurals.data_sync_written, written))
        if (deletedLocal > 0) add(n(R.plurals.data_sync_deleted_local, deletedLocal))
        if (deletedFiles > 0) add(n(R.plurals.data_sync_deleted_files, deletedFiles))
        if (linked > 0) add(n(R.plurals.data_sync_linked, linked))
        if (conflicts > 0) add(n(R.plurals.data_sync_conflicts, conflicts))
    }.ifEmpty { listOf(res.getString(R.string.data_sync_in_sync)) }.joinToString(" · ")
}

/**
 * Serverless multi-device sync: one vCard 4.0 file per contact ("vdir" layout, like khard/vdirsyncer)
 * in a folder the user syncs with Syncthing, Nextcloud, a USB drive… Three-way merge per contact
 * against the last synced state, so edits on either phone flow to the other. Parley itself never
 * touches the network. Deletions are journaled (30-day undo).
 */
class FolderSync(private val context: Context, private val contacts: ContactsRepository, private val records: ContactRecordStore) {
    private val cr = context.contentResolver
    private val prefs = context.getSharedPreferences("folder_sync", Context.MODE_PRIVATE)
    private val stateFile = File(context.filesDir, "folder_sync_state.json")
    private val mutex = Mutex()

    private val _status = MutableStateFlow(load())
    val status: StateFlow<SyncStatus> = _status

    private fun load() = SyncStatus(
        prefs.getString("folder", null), prefs.getString("folderName", null), prefs.getBoolean("auto", true),
        prefs.getLong("lastAt", 0), prefs.getString("lastResult", null), prefs.getInt("pendingDeletions", 0),
        mode = runCatching { SyncMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(SyncMode.UNSET),
    )

    /** The codec for the chosen mode; null until the user chose (a folder from before encrypted sync, or a new one). */
    private fun codec(): SyncCodec? = when (status.value.mode) {
        SyncMode.PLAIN -> SyncCodec.Plain
        SyncMode.ENCRYPTED -> prefs.getString("folderKey", null)?.let { stored ->
            val crypto = RecordCrypto.get(context)
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            if (!crypto.isSealed(sealed)) null else runCatching { SyncCodec.Encrypted(crypto.openBytes(sealed)) }.getOrNull()
        }
        SyncMode.UNSET -> null
    }

    /** Whether the chosen folder already holds an encrypted sync (another phone set it up): then its passphrase is needed. */
    suspend fun folderIsEncrypted(): Boolean = withContext(Dispatchers.IO) { readHeader() != null }

    private fun folderDoc(): Pair<Uri, String>? {
        val folder = status.value.folderUri?.let(Uri::parse) ?: return null
        return folder to DocumentsContract.getTreeDocumentId(folder)
    }

    private fun headerUri(): Uri? {
        val (folder, treeId) = folderDoc() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return cr.query(children, columns, null, null, null)?.use { c ->
            var found: Uri? = null
            while (c.moveToNext()) if (c.getString(1) == SyncCrypto.HEADER_NAME) found = DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))
            found
        }
    }

    private fun readHeader(): ByteArray? = runCatching {
        headerUri()?.let { u -> cr.openInputStream(u)?.use { Bounded.readBytes(it, 4096, "sync header") } }
    }.getOrNull()

    /** Result of [useEncryption]. */
    enum class EncryptionSetup { READY, WRONG_PASSPHRASE, FAILED }

    /**
     * Encrypts the folder's files with a key from [passphrase]: the folder's existing one when another phone set it up
     * (the same passphrase is needed), or a new one. The plain .vcf files this phone wrote there before are removed.
     */
    suspend fun useEncryption(passphrase: CharArray): EncryptionSetup = mutex.withLock {
        withContext(Dispatchers.IO) {
            val (folder, treeId) = folderDoc() ?: return@withContext EncryptionSetup.FAILED
            val existing = readHeader()
            val key = try {
                if (existing != null) {
                    SyncCrypto.unlock(existing, passphrase) ?: return@withContext EncryptionSetup.WRONG_PASSPHRASE
                } else {
                    val (header, k) = SyncCrypto.newFolder(passphrase)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(folder, treeId)
                    val uri = DocumentsContract.createDocument(cr, parent, "application/octet-stream", SyncCrypto.HEADER_NAME)
                        ?: return@withContext EncryptionSetup.FAILED
                    cr.openOutputStream(uri, "wt")!!.use { it.write(header) }
                    k
                }
            } catch (_: Exception) {
                return@withContext EncryptionSetup.FAILED
            }
            val crypto = RecordCrypto.get(context)
            val sealed = crypto.sealBytes(key)
            key.fill(0)
            // The key is only ever stored sealed by the Keystore.
            if (!crypto.isSealed(sealed)) return@withContext EncryptionSetup.FAILED
            if (status.value.mode == SyncMode.PLAIN) removePlainFiles()
            prefs.edit().putString("mode", SyncMode.ENCRYPTED.name).putString("folderKey", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .remove("lastResult").apply()
            stateFile.delete() // new file names and format: the first run links matching contacts instead of duplicating
            _status.value = load()
            EncryptionSetup.READY
        }
    }

    /** Plain vCard files, after the user agreed that anyone with access to the folder can read them. */
    fun usePlain() {
        prefs.edit().putString("mode", SyncMode.PLAIN.name).remove("folderKey").remove("lastResult").apply()
        stateFile.delete()
        _status.value = load()
    }

    /** Deletes the plain files this phone manages (those in the sync state), after switching to encryption. */
    private fun removePlainFiles() {
        val (folder, treeId) = folderDoc() ?: return
        val mine = readState().keys
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId)
        runCatching {
            cr.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name in mine && name.endsWith(SyncCodec.Plain.extension)) {
                        runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))) }
                    }
                }
            }
        }
    }

    fun setFolder(uri: Uri?, name: String?) {
        // A new folder starts without a mode: the user chooses encrypted (default) or plain files for it.
        prefs.edit().remove("pendingDeletions").remove("lastResult").remove("mode").remove("folderKey").apply()
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.edit().putString("folder", uri?.toString()).putString("folderName", name).apply()
        stateFile.delete() // new folder: start fresh (first sync links matching contacts instead of duplicating)
        _status.value = load()
    }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        _status.value = load()
    }

    /**
     * The last synced state of one file and its contact. [localHash] is of the contact's canonical form; [v] says how
     * photos enter it (1: the full photo inline, as older versions stored it; 2: by the photo's digest, so hashing
     * needn't load photos). [token], [stamp], [contactId] and [rawIds] let a run skip unchanged contacts and files
     * without reading them, and find a contact again after its lookup key changed.
     */
    private data class Entry(
        val contactKey: String,
        val fileHash: String,
        val localHash: String,
        val v: Int = HASH_V2,
        val token: String? = null,
        val stamp: String? = null,
        val contactId: Long? = null,
        val rawIds: List<Long> = emptyList(),
    )

    private fun readState(): MutableMap<String, Entry> = runCatching {
        val o = JSONObject(stateFile.readText())
        o.keys().asSequence().associateWith { k ->
            o.getJSONObject(k).let {
                Entry(
                    it.getString("k"), it.getString("f"), it.getString("l"), it.optInt("v", 1),
                    it.optString("t").ifEmpty { null }, it.optString("s").ifEmpty { null },
                    it.optLong("c", 0L).takeIf { c -> c > 0 },
                    it.optString("r").split(',').mapNotNull { r -> r.toLongOrNull() },
                )
            }
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    /** Written to a temporary file and renamed, so a crash never leaves a half-written state (which would look like "everything new"). */
    private fun writeState(s: Map<String, Entry>) {
        val o = JSONObject()
        s.forEach { (name, e) ->
            o.put(
                name,
                JSONObject().put("k", e.contactKey).put("f", e.fileHash).put("l", e.localHash).put("v", e.v)
                    .putOpt("t", e.token).putOpt("s", e.stamp).putOpt("c", e.contactId)
                    .putOpt("r", e.rawIds.takeIf { it.isNotEmpty() }?.joinToString(",")),
            )
        }
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
    }

    private fun sha(bytes: ByteArray) = RecordJson.sha256Hex(bytes)

    /** How older versions hashed a contact: [r] read with full photos. Only used to carry their state over. */
    private fun localHashV1(r: ContactRecord) = sha(RecordJson.encode(VCardMapper.canonical(r.withoutMessengers())).toByteArray())

    /** Photo digests of this run, by raw contact and thumbnail. */
    private val photoDigests = HashMap<String, String>()

    /** A contact's hash with each photo as its digest ([ContactRecordStore.photoDigest]), from a record read without full photos. */
    private fun localHash(r: ContactRecord): String {
        val digested = r.copy(
            raws = r.raws.map { raw ->
                raw.copy(
                    rows = raw.rows.map { row ->
                        val blob = row.blob
                        if (row.mimeType != Mime.PHOTO || blob == null || blob.isEmpty()) {
                            row
                        } else {
                            val d = raw.rawId?.let { records.photoDigest(it, blob, photoDigests) } ?: sha(blob)
                            row.copy(blob = d.toByteArray())
                        }
                    },
                )
            },
        )
        return sha(RecordJson.encode(VCardMapper.canonical(digested.withoutMessengers())).toByteArray())
    }

    /** [r] (read without full photos) with its full-resolution photos, for the one file being written. */
    private fun withFullPhotos(r: ContactRecord): ContactRecord = r.copy(
        raws = r.raws.map { raw ->
            val id = raw.rawId
            if (id == null) {
                raw
            } else {
                raw.copy(
                    rows = raw.rows.map { row ->
                        if (row.mimeType == Mime.PHOTO && row[Col.D14] != null) records.fullPhoto(id)?.let { row.copy(blob = it) } ?: row else row
                    },
                )
            }
        },
    )

    /** Group titles, read once per run rather than once per file. */
    private var runTitles: Map<Long, String>? = null

    private fun render(r: ContactRecord, uid: String): ByteArray {
        val card = VCardMapper.toVCard(r.withoutMessengers(), runTitles ?: records.groupTitles())
        card.uid = ezvcard.property.Uid(uid)
        return Ezvcard.write(card).version(VCardVersion.V4_0).prodId(false).go().toByteArray()
    }

    private fun parse(bytes: ByteArray): ContactRecord? = runCatching {
        Ezvcard.parse(String(bytes, Charsets.UTF_8)).first()?.let { VCardMapper.fromVCard(it) }
    }.getOrNull()

    /** Hashed, so names are short, filesystem-safe, never collide after sanitising and say nothing about the person. */
    private fun fileNameFor(key: String, codec: SyncCodec) = codec.fileName(sha(key.toByteArray()).take(32))

    private fun finish(status: StoredStatus, pending: Int = 0) {
        prefs.edit().putLong("lastAt", System.currentTimeMillis()).putString("lastResult", status.encode()).putInt("pendingDeletions", pending).apply()
        _status.value = load()
    }

    /** What the last run read, for tests and diagnostics: whole contacts and whole files. */
    internal data class RunStats(val contactsRead: Int = 0, val filesRead: Int = 0, val filesWritten: Int = 0, val largestPage: Int = 0)

    @Volatile internal var lastRun = RunStats()
        private set

    /**
     * Runs one sync. Stops without changing anything when the folder can't be listed or contacts can't be read,
     * and pauses when a run would delete many contacts or files at once (a folder that was emptied or swapped,
     * a sync app that hasn't finished) or a recently edited contact: the user confirms with [allowMassDelete].
     *
     * It streams: contacts are compared by their change token and files by their stamp first, so an unchanged
     * contact or file is never read; what must be read is read a page (contacts) or one file at a time, and full
     * photos only for the file being written.
     */
    suspend fun syncNow(allowMassDelete: Boolean = false): SyncReport = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                runTitles = records.groupTitles()
                run(allowMassDelete)
            } finally {
                runTitles = null
                photoDigests.clear()
            }
        }
    }

    private class Listed(val uri: Uri, val stamp: String?)

    /** Per entry: what changed, found in the first pass (only hashes and flags are kept, never contents). */
    private class Look {
        var fileChanged = false
        var fileGone = false
        var unreadable = false
        var localChanged = false
        var localGone = false
    }

    private suspend fun run(allowMassDelete: Boolean): SyncReport {
        val folder = status.value.folderUri?.let(Uri::parse) ?: return SyncReport()
        val codec = codec() ?: run {
            finish(StoredStatus.of(SyncStatus.CHOOSE_MODE))
            return SyncReport()
        }
        if (!Permissions.has(context, Manifest.permission.READ_CONTACTS) || !Permissions.has(context, Manifest.permission.WRITE_CONTACTS)) {
            finish(StoredStatus.of(SyncStatus.NO_PERMISSION))
            return SyncReport()
        }
        var rep = SyncReport()
        var stats = RunStats()
        val state = readState()
        val treeId = DocumentsContract.getTreeDocumentId(folder)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(folder, treeId)

        // Folder listing: names and stamps only. A listing failure aborts: an unreadable folder must never look empty.
        val files = HashMap<String, Listed>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId)
        val listed = try {
            cr.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!codec.accepts(name)) continue
                    // More files than any address book: something else is in this folder; stop rather than guess.
                    if (files.size >= MAX_FILES) throw LimitExceededException("Too many files in the sync folder")
                    val stamp = FolderSyncRules.stamp(if (c.isNull(2)) null else c.getLong(2), if (c.isNull(3)) null else c.getLong(3))
                    files[name] = Listed(DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0)), stamp)
                }
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (!listed) {
            finish(StoredStatus.of(SyncStatus.FOLDER_GONE))
            return SyncReport()
        }

        // The vCard in a file, or null when it can't be read (too large, another key, altered): left alone, not deleted.
        fun readFile(name: String, uri: Uri): ByteArray? =
            runCatching { cr.openInputStream(uri)?.use { codec.decode(name, Bounded.readBytes(it, Bounded.Caps.SYNC_FILE)) } }.getOrNull()
                .also { stats = stats.copy(filesRead = stats.filesRead + 1) }

        fun stampOf(uri: Uri): String? = runCatching {
            cr.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) FolderSyncRules.stamp(if (c.isNull(0)) null else c.getLong(0), if (c.isNull(1)) null else c.getLong(1)) else null
            }
        }.getOrNull()

        // Local contacts: keys, raw ids and change tokens only.
        var heads = records.heads()
        var byKey = heads.associateBy { it.key }
        val byRaw = HashMap<Long, ContactRecordStore.Head>().apply { heads.forEach { h -> h.rawIds.forEach { put(it, h) } } }

        // An entry whose lookup key no longer resolves is found again by the key's new form or its raw contacts, so a
        // key change (a first sync, an account move) never looks like a deletion.
        for ((name, e) in state.toMap()) {
            if (e.contactKey in byKey) continue
            val head = idFor(e.contactKey)?.let { id -> heads.firstOrNull { it.contactId == id } }
                ?: e.rawIds.firstNotNullOfOrNull { byRaw[it] }
            if (head != null) state[name] = e.copy(contactKey = head.key, contactId = head.contactId, rawIds = head.rawIds)
        }

        fun entryFor(head: ContactRecordStore.Head, fileHash: String, localHash: String, stamp: String?) =
            Entry(head.key, fileHash, localHash, HASH_V2, head.token, stamp, head.contactId, head.rawIds)

        // First pass: what changed. Contacts whose token moved are read a page at a time and hashed.
        val looks = HashMap<String, Look>()
        val toHash = LinkedHashMap<Long, MutableList<String>>()
        for ((name, e) in state) {
            val look = Look()
            looks[name] = look
            val head = byKey[e.contactKey]
            when {
                head == null -> look.localGone = true
                e.v == HASH_V2 && e.token != null && e.token == head.token -> Unit
                else -> toHash.getOrPut(head.contactId) { ArrayList(1) } += name
            }
        }
        if (toHash.isNotEmpty()) {
            for (page in toHash.keys.chunked(ContactRecordStore.BATCH)) {
                val recs = records.readAll(page, fullPhoto = false).toList()
                stats = stats.copy(contactsRead = stats.contactsRead + recs.size, largestPage = maxOf(stats.largestPage, recs.size))
                val seen = HashSet<Long>()
                for (rec in recs) {
                    val head = byKey[rec.key] ?: continue
                    seen += head.contactId
                    for (name in toHash[head.contactId].orEmpty()) {
                        val e = state.getValue(name)
                        val hash = localHash(rec)
                        val same = if (e.v == HASH_V2) hash == e.localHash else localHashV1(withFullPhotos(rec)) == e.localHash
                        // Unchanged: carry the entry over to the current hash form and token, so the next run skips it.
                        if (same) state[name] = e.copy(localHash = hash, v = HASH_V2, token = head.token, contactId = head.contactId, rawIds = head.rawIds)
                        looks.getValue(name).localChanged = !same
                    }
                }
                // Gone between the listing and the read.
                page.filter { it !in seen }.forEach { id -> toHash[id].orEmpty().forEach { looks.getValue(it).localGone = true } }
            }
        }
        for ((name, e) in state.toMap()) {
            val look = looks.getValue(name)
            val file = files[name]
            when {
                file == null -> look.fileGone = true
                e.stamp != null && file.stamp == e.stamp -> Unit
                else -> {
                    val bytes = readFile(name, file.uri)
                    if (bytes == null) {
                        look.unreadable = true // unreadable is not missing: left alone this run
                    } else if (sha(bytes) == e.fileHash) {
                        state[name] = e.copy(stamp = file.stamp)
                    } else {
                        look.fileChanged = true
                    }
                }
            }
        }

        // Guard against mass deletion and against deleting a recently edited contact.
        var deletions = 0
        var recent = 0
        val now = System.currentTimeMillis()
        for ((name, e) in state) {
            val look = looks.getValue(name)
            if (look.unreadable) continue
            when (FolderSyncRules.action(look.fileChanged, look.fileGone, look.localChanged, look.localGone)) {
                FolderSyncRules.Action.DELETE_FILE -> deletions++
                FolderSyncRules.Action.DELETE_LOCAL -> {
                    deletions++
                    val updated = byKey[e.contactKey]?.updatedAt ?: 0L
                    if (updated > 0 && now - updated < FolderSyncRules.RECENT_EDIT_MS) recent++
                }
                else -> Unit
            }
        }
        if (!allowMassDelete && FolderSyncRules.mustConfirm(deletions, state.size, recent)) {
            writeState(state) // keeps the carried-over hashes and stamps
            finish(StoredStatus.of(SyncStatus.PAUSED, deletions), deletions)
            lastRun = stats
            return SyncReport()
        }

        fun writeFile(name: String, bytes: ByteArray): String? = try {
            val uri = files[name]?.uri ?: DocumentsContract.createDocument(cr, parentDoc, codec.mime, name)!!
            cr.openOutputStream(uri, "wt")!!.use { it.write(codec.encode(name, bytes)) }
            stats = stats.copy(filesWritten = stats.filesWritten + 1)
            files[name] = Listed(uri, null)
            stampOf(uri) ?: ""
        } catch (_: Exception) {
            null
        }

        fun readOne(head: ContactRecordStore.Head): ContactRecord? =
            records.read(head.contactId, fullPhoto = false).also { if (it != null) stats = stats.copy(contactsRead = stats.contactsRead + 1) }

        fun headOf(contactId: Long): ContactRecordStore.Head? = records.heads(listOf(contactId)).firstOrNull()

        // Second pass: act on each changed entry, reading only what it needs.
        for ((name, e) in state.toMap()) {
            val look = looks.getValue(name)
            if (look.unreadable) continue
            val head = byKey[e.contactKey]
            when (FolderSyncRules.action(look.fileChanged, look.fileGone, look.localChanged, look.localGone)) {
                FolderSyncRules.Action.NONE -> Unit
                FolderSyncRules.Action.DELETE_FILE -> {
                    files[name]?.let { runCatching { DocumentsContract.deleteDocument(cr, it.uri) } }
                    state.remove(name)
                    rep = rep.copy(deletedFiles = rep.deletedFiles + 1)
                }
                FolderSyncRules.Action.WRITE_FILE -> {
                    val rec = head?.let(::readOne) ?: continue
                    val bytes = render(withFullPhotos(rec), e.contactKey)
                    val stamp = writeFile(name, bytes) ?: continue
                    state[name] = entryFor(head, sha(bytes), localHash(rec), stamp.ifEmpty { null })
                    rep = rep.copy(written = rep.written + 1)
                }
                FolderSyncRules.Action.DELETE_LOCAL -> {
                    head?.let { contacts.delete(listOf(it.contactId)) } // journaled
                    state.remove(name)
                    rep = rep.copy(deletedLocal = rep.deletedLocal + 1)
                }
                FolderSyncRules.Action.APPLY_FILE -> {
                    val file = files[name] ?: continue
                    val bytes = readFile(name, file.uri) ?: continue
                    val remote = parse(bytes) ?: continue
                    val id = head?.contactId ?: continue
                    val newId = applyRemote(id, remote) ?: continue
                    val h = headOf(newId) ?: continue
                    val updated = readOne(h) ?: continue
                    state[name] = entryFor(h, sha(bytes), localHash(updated), file.stamp)
                    rep = rep.copy(updatedFromFolder = rep.updatedFromFolder + 1)
                }
                FolderSyncRules.Action.CONFLICT -> {
                    val file = files[name] ?: continue
                    val theirs = readFile(name, file.uri) ?: continue
                    val rec = head?.let(::readOne) ?: continue
                    runCatching {
                        val aside = codec.conflictName(name, System.currentTimeMillis())
                        DocumentsContract.createDocument(cr, parentDoc, codec.mime, aside)
                            ?.let { u -> cr.openOutputStream(u, "wt")!!.use { it.write(codec.encode(aside, theirs)) } }
                    }
                    val bytes = render(withFullPhotos(rec), e.contactKey)
                    writeFile(name, bytes)?.let { stamp -> state[name] = entryFor(head, sha(bytes), localHash(rec), stamp.ifEmpty { null }) }
                    rep = rep.copy(conflicts = rep.conflicts + 1)
                }
                FolderSyncRules.Action.FORGET -> state.remove(name)
            }
        }
        writeState(state)
        heads = records.heads()
        byKey = heads.associateBy { it.key }

        // New files from other devices: link to a matching local contact (same phone or e-mail), or import. The
        // match keys of unpaired contacts are read a page at a time, and only when there is a new file.
        val newFiles = files.keys.filter { it !in state }
        if (newFiles.isNotEmpty()) {
            val mappedKeys = state.values.map { it.contactKey }.toHashSet()
            val byMatch = HashMap<String, Long>()
            for (page in heads.filter { it.key !in mappedKeys }.map { it.contactId }.chunked(ContactRecordStore.BATCH)) {
                val recs = records.readAll(page, fullPhoto = false).toList()
                stats = stats.copy(contactsRead = stats.contactsRead + recs.size, largestPage = maxOf(stats.largestPage, recs.size))
                for (r in recs) {
                    val id = byKey[r.key]?.contactId ?: continue
                    matchKeys(r).forEach { k -> byMatch.putIfAbsent(k, id) }
                }
            }
            val taken = HashSet<Long>()
            for (name in newFiles) {
                val file = files.getValue(name)
                val bytes = readFile(name, file.uri) ?: continue
                val remote = parse(bytes) ?: continue
                val match = matchKeys(remote).firstNotNullOfOrNull { k -> byMatch[k]?.takeIf { it !in taken } }
                if (match != null) {
                    val h = heads.firstOrNull { it.contactId == match } ?: continue
                    val rec = readOne(h) ?: continue
                    state[name] = entryFor(h, sha(bytes), localHash(rec), file.stamp)
                    taken += match
                    rep = rep.copy(linked = rep.linked + 1)
                } else {
                    val id = records.insert(remote, target = null) ?: continue
                    val h = headOf(id) ?: continue
                    val rec = readOne(h) ?: continue
                    state[name] = entryFor(h, sha(bytes), localHash(rec), file.stamp)
                    taken += id
                    rep = rep.copy(imported = rep.imported + 1)
                }
            }
            writeState(state)
            heads = records.heads()
        }

        // Local contacts never written yet, a page at a time; full photos only for the file being written.
        val mapped = state.values.map { it.contactKey }.toHashSet()
        val headsByKey = heads.associateBy { it.key }
        for (page in heads.filter { it.key !in mapped }.map { it.contactId }.chunked(ContactRecordStore.BATCH)) {
            val recs = records.readAll(page, fullPhoto = false).toList()
            stats = stats.copy(contactsRead = stats.contactsRead + recs.size, largestPage = maxOf(stats.largestPage, recs.size))
            for (rec in recs) {
                val h = headsByKey[rec.key] ?: continue
                val name = fileNameFor(rec.key, codec)
                val bytes = render(withFullPhotos(rec), rec.key)
                val stamp = writeFile(name, bytes) ?: continue
                state[name] = entryFor(h, sha(bytes), localHash(rec), stamp.ifEmpty { null })
                rep = rep.copy(written = rep.written + 1)
            }
            writeState(state)
        }

        writeState(state)
        lastRun = stats
        finish(StoredStatus.of(SyncStatus.REPORT, rep.written, rep.imported, rep.updatedFromFolder, rep.deletedLocal, rep.deletedFiles, rep.conflicts, rep.linked))
        contacts.refresh()
        return rep
    }

    /** A contact's phones and e-mails as match keys, for pairing a new file with a contact that has none yet. */
    private fun matchKeys(r: ContactRecord): Set<String> = r.raws.flatMap { it.rows }.mapNotNull { row ->
        when (row.mimeType) {
            Mime.PHONE -> row["data1"]?.let { PhoneIdentity.portableKey(it) }?.let { "p:$it" }
            Mime.EMAIL -> row["data1"]?.let { Duplicates.emailKey(it) }?.let { "e:$it" }
            else -> null
        }
    }.toSet()

    /**
     * Applies a remote edit to contact [id] in place (same contact id, links, call history and read-only parts),
     * journaled first. Falls back to a fresh copy only when the contact has nothing writable. Returns the contact id
     * that now holds it.
     */
    private suspend fun applyRemote(id: Long, remote: ContactRecord): Long? {
        contacts.recordChange(listOf(id), "EDIT")
        val (target, writable) = contacts.writableRaws(id)
        return if (target != null) {
            if (!records.replaceContent(id, remote, target, writable)) null else id
        } else {
            val newId = records.insert(remote, target = null) ?: return null
            runCatching { contacts.delete(listOf(id)) }
            newId
        }
    }

    private fun idFor(key: String): Long? = runCatching {
        ContactsContract.Contacts.lookupContact(cr, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key))
            ?.let { ContentUris.parseId(it) }
    }.getOrNull()

    private companion object {
        const val HASH_V2 = 2
        const val MAX_FILES = 50_000
    }
}
