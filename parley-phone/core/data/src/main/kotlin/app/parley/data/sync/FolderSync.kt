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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
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
    /** Parley's plain .vcf files that switching to encryption couldn't remove from the folder (0: none left). */
    val plainLeft: Int = 0,
) {
    /** The last result in the current language (rendered now, not when it was stored). */
    fun resultText(res: Resources): String? {
        val s = StoredStatus.decode(lastResult) ?: return lastResult
        return when (s.kind) {
            NO_PERMISSION -> res.getString(R.string.data_sync_no_permission)
            FOLDER_GONE -> res.getString(R.string.data_sync_folder_gone)
            FOLDER_LOADING -> res.getString(R.string.data_sync_folder_loading)
            PAUSED -> res.getQuantityString(R.plurals.data_sync_paused, s.int(0), s.int(0))
            CHOOSE_MODE -> res.getString(R.string.data_sync_choose_mode)
            REPORT -> SyncReport(s.int(0), s.int(1), s.int(2), s.int(3), s.int(4), s.int(5), s.int(6)).summary(res)
            else -> null
        }
    }

    companion object {
        const val NO_PERMISSION = "no_permission"
        const val FOLDER_GONE = "folder_gone"
        const val FOLDER_LOADING = "folder_loading"
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

    /** Encrypted mode: the last version of each file deleted by a run (name → version), so a copy put back is ignored. */
    private val goneFile = File(context.filesDir, "folder_sync_gone.json")
    private val mutex = Mutex()

    /** How long to wait before listing again a folder whose provider is still loading it. */
    internal var listingRetryMs = 1_500L

    private val _status = MutableStateFlow(load())
    val status: StateFlow<SyncStatus> = _status

    private fun load() = SyncStatus(
        prefs.getString("folder", null), prefs.getString("folderName", null), prefs.getBoolean("auto", true),
        prefs.getLong("lastAt", 0), prefs.getString("lastResult", null), prefs.getInt("pendingDeletions", 0),
        mode = runCatching { SyncMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(SyncMode.UNSET),
        plainLeft = prefs.getInt("plainLeft", 0),
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
    suspend fun folderIsEncrypted(): Boolean = withContext(Dispatchers.IO) { runCatching { readHeader() != null }.getOrDefault(false) }

    private fun folderDoc(): Pair<Uri, String>? {
        val folder = status.value.folderUri?.let(Uri::parse) ?: return null
        return folder to DocumentsContract.getTreeDocumentId(folder)
    }

    private fun parentDoc(): Uri? = folderDoc()?.let { (folder, treeId) -> DocumentsContract.buildDocumentUriUsingTree(folder, treeId) }

    /** One file of the folder listing: names and stamps only. */
    private class Listed(val uri: Uri, val stamp: String?)

    /** The provider answered with a listing it is still loading (a cloud folder): a partial list must never look like deletions. */
    private class ListingIncomplete : Exception()

    /**
     * Every file in the folder (by name), or null when the folder can't be listed. A cloud provider may first answer
     * with part of the files while it fetches the rest ([DocumentsContract.EXTRA_LOADING]): that is asked again a few
     * times, then thrown as [ListingIncomplete] so the caller changes nothing.
     */
    private suspend fun listFolder(): Map<String, Listed>? {
        val (folder, treeId) = folderDoc() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        repeat(LISTING_TRIES) { attempt ->
            var loading = false
            val out: Map<String, Listed> = try {
                cr.query(children, columns, null, null, null)?.use { c ->
                    val extras = c.extras
                    if (extras?.getString(DocumentsContract.EXTRA_ERROR) != null) return null
                    loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
                    val files = HashMap<String, Listed>()
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        // More files than any address book: something else is in this folder; stop rather than guess.
                        if (files.size >= MAX_FILES) throw LimitExceededException("Too many files in the sync folder")
                        val stamp = FolderSyncRules.stamp(if (c.isNull(2)) null else c.getLong(2), if (c.isNull(3)) null else c.getLong(3))
                        files[name] = Listed(DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0)), stamp)
                    }
                    files
                }
            } catch (_: Exception) {
                null
            } ?: return null
            if (!loading) return out
            if (attempt < LISTING_TRIES - 1) delay(listingRetryMs)
        }
        throw ListingIncomplete()
    }

    /** The folder's encryption header, or null when it has none. Throws when the folder can't be listed completely. */
    private suspend fun readHeader(): ByteArray? {
        val listing = listFolder() ?: throw IOException("The sync folder can't be listed")
        val header = listing[SyncCrypto.HEADER_NAME] ?: return null
        return cr.openInputStream(header.uri)?.use { Bounded.readBytes(it, 4096, "sync header") }
    }

    /** Result of [useEncryption]. */
    enum class EncryptionSetup {
        READY,

        /** Encryption is on, but some of Parley's plain files couldn't be removed from the folder ([SyncStatus.plainLeft]). */
        PLAIN_FILES_LEFT,
        WRONG_PASSPHRASE,
        FAILED,
    }

    /**
     * Encrypts the folder's files with a key from [passphrase]: the folder's existing one when another phone set it up
     * (the same passphrase is needed), or a new one. Parley's plain .vcf files there (from this phone or another) are
     * written again encrypted and then removed; the result says whether any could not be removed.
     */
    suspend fun useEncryption(passphrase: CharArray): EncryptionSetup = mutex.withLock {
        withContext(Dispatchers.IO) {
            val (folder, treeId) = folderDoc() ?: return@withContext EncryptionSetup.FAILED
            val key = try {
                val existing = readHeader()
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
            try {
                val crypto = RecordCrypto.get(context)
                val sealed = crypto.sealBytes(key)
                // The key is only ever stored sealed by the Keystore.
                if (!crypto.isSealed(sealed)) return@withContext EncryptionSetup.FAILED
                // Before the mode changes: the plain files and the sync state still use the plain names.
                val left = if (status.value.mode != SyncMode.ENCRYPTED) movePlainFiles(SyncCodec.Encrypted(key)) else 0
                prefs.edit().putString("mode", SyncMode.ENCRYPTED.name).putString("folderKey", Base64.encodeToString(sealed, Base64.NO_WRAP))
                    .putInt("plainLeft", left).remove("lastResult").apply()
                _status.value = load()
                if (left > 0) EncryptionSetup.PLAIN_FILES_LEFT else EncryptionSetup.READY
            } catch (_: Exception) {
                EncryptionSetup.FAILED
            } finally {
                key.fill(0)
            }
        }
    }

    /**
     * Plain vCard files, after the user agreed that anyone with access to the folder can read them. A folder from
     * before encrypted sync keeps its sync state (same file names, same format), so the first run carries it over
     * instead of treating every file as new.
     */
    fun usePlain() {
        val fromEncrypted = status.value.mode == SyncMode.ENCRYPTED || readState().keys.any { !SyncCodec.Plain.accepts(it) }
        prefs.edit().putString("mode", SyncMode.PLAIN.name).remove("folderKey").remove("lastResult").remove("plainLeft").apply()
        if (fromEncrypted) {
            stateFile.delete()
            goneFile.delete()
        }
        _status.value = load()
    }

    /**
     * Moves the folder from plain vCards to encrypted files. Parley's plain files are those in the sync state and
     * those named after the hash of the UID inside them (how every phone names its files), so another phone's
     * files are found too and nobody else's .vcf is touched. Each is written again encrypted under its encrypted
     * name (unless another phone already did), then deleted; the sync state follows the new names, so the next run
     * neither re-imports nor duplicates anything. Returns how many of Parley's plain files are still there.
     */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun movePlainFiles(codec: SyncCodec.Encrypted): Int {
        val parent = parentDoc() ?: throw IOException("No sync folder")
        val listing = listFolder() ?: throw IOException("The sync folder can't be listed")
        val plain = SyncCodec.Plain
        val state = readState()
        fun encryptedName(name: String) = codec.fileName(name.removeSuffix(plain.extension))
        // Other phones' plain files only when this phone synced the folder in plain files itself (an upgrade, or plain
        // chosen before): a folder just picked may be another phone's plain sync, which isn't this phone's to rewrite.
        val synced = state.isNotEmpty() || status.value.mode == SyncMode.PLAIN
        val ours = listing.keys.filter { name ->
            plain.accepts(name) && (name in state || (synced && isParleyPlainFile(name, listing.getValue(name).uri)))
        }
        val moved = HashMap<String, Entry>()
        val now = System.currentTimeMillis()
        for (name in ours) {
            val target = encryptedName(name)
            val e = state[name]
            if (target in listing) {
                // Another phone moved it already: the next run reads and compares that file.
                if (e != null) moved[target] = e.copy(stamp = null, ver = 0)
            } else {
                val bytes = runCatching { cr.openInputStream(listing.getValue(name).uri)?.use { Bounded.readBytes(it, Bounded.Caps.SYNC_FILE) } }.getOrNull()
                    ?: continue // unreadable: kept, and counted as left below
                val version = FolderSyncRules.nextVersion(0, now)
                val uri = DocumentsContract.createDocument(cr, parent, codec.mime, target) ?: continue
                cr.openOutputStream(uri, "wt")!!.use { it.write(codec.encode(target, bytes, version)) }
                // A file edited elsewhere since the last run keeps no stamp, so the next run still applies that edit.
                if (e != null) moved[target] = e.copy(stamp = if (sha(bytes) == e.fileHash) stampOf(uri) else null, ver = version)
            }
            runCatching { DocumentsContract.deleteDocument(cr, listing.getValue(name).uri) }
        }
        // Entries whose plain file is gone already (another phone moved it first) follow the new name too.
        for ((name, e) in state) if (plain.accepts(name) && name !in listing) moved.putIfAbsent(encryptedName(name), e.copy(stamp = null, ver = 0))
        writeState(moved)
        goneFile.delete()
        // Checked, not assumed: what the folder lists now.
        val after = try {
            listFolder()
        } catch (_: ListingIncomplete) {
            null
        } ?: return ours.size
        return ours.count { it in after }
    }

    /**
     * Whether a plain .vcf file was written by Parley: its name is the hash of its UID ([fileNameFor]), which a file
     * made by another app or a person has no reason to match.
     */
    private fun isParleyPlainFile(name: String, uri: Uri): Boolean {
        val base = name.removeSuffix(SyncCodec.Plain.extension)
        if (!PARLEY_NAME.matches(base)) return false
        val uid = runCatching {
            cr.openInputStream(uri)?.use { Bounded.readBytes(it, Bounded.Caps.SYNC_FILE) }
                ?.let { Ezvcard.parse(String(it, Charsets.UTF_8)).first()?.uid?.value }
        }.getOrNull() ?: return false
        return sha(uid.toByteArray()).take(32) == base
    }

    /**
     * After a switch to encryption left plain files behind: removes those whose encrypted copy is now in the folder
     * (so nothing is lost), and records how many are still there.
     */
    private fun retryPlainRemoval(listing: Map<String, Listed>, codec: SyncCodec) {
        val plain = SyncCodec.Plain
        fun removed(name: String, file: Listed) = codec.fileName(name.removeSuffix(plain.extension)) in listing &&
            runCatching { DocumentsContract.deleteDocument(cr, file.uri) }.getOrDefault(false)
        val left = listing.count { (name, file) -> plain.accepts(name) && isParleyPlainFile(name, file.uri) && !removed(name, file) }
        prefs.edit().putInt("plainLeft", left).apply()
        _status.value = load()
    }

    fun setFolder(uri: Uri?, name: String?) {
        // A new folder starts without a mode: the user chooses encrypted (default) or plain files for it.
        prefs.edit().remove("pendingDeletions").remove("lastResult").remove("mode").remove("folderKey").remove("plainLeft").apply()
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.edit().putString("folder", uri?.toString()).putString("folderName", name).apply()
        stateFile.delete() // new folder: start fresh (first sync links matching contacts instead of duplicating)
        goneFile.delete()
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
     * without reading them, and find a contact again after its lookup key changed. [ver] is the file's version last
     * seen or written (encrypted files; 0 for plain ones), and [ownAt] the contact's last-updated time right after
     * this sync itself wrote the contact (an import or an update from the folder), so that write doesn't count as a
     * recent edit.
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
        val ver: Long = 0,
        val ownAt: Long? = null,
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
                    it.optLong("n", 0L),
                    it.optLong("o", 0L).takeIf { t -> t > 0 },
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
                    .putOpt("r", e.rawIds.takeIf { it.isNotEmpty() }?.joinToString(","))
                    .putOpt("n", e.ver.takeIf { it > 0 }).putOpt("o", e.ownAt),
            )
        }
        writeAtomically(stateFile, o.toString())
    }

    private fun readGone(): MutableMap<String, Long> = runCatching {
        val o = JSONObject(goneFile.readText())
        o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeGone(gone: Map<String, Long>) {
        if (gone.isEmpty()) goneFile.delete() else writeAtomically(goneFile, JSONObject(gone).toString())
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
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

    /**
     * The group titles as part of every contact's change token: a renamed label changes no raw contact's version,
     * yet it changes the CATEGORIES of every file of its members.
     */
    private fun titlesTag(): String =
        sha((runTitles ?: records.groupTitles()).toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray()).take(16)

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
     * Runs one sync. Stops without changing anything when the folder can't be listed (or only partly, while its
     * provider loads) or contacts can't be read. A run that would delete many contacts or files at once (a folder
     * that was emptied or swapped, a sync app that hasn't finished) waits for the user as a whole; the deletion of a
     * contact edited here recently waits alone while the rest syncs. The user confirms with [allowMassDelete].
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

    /** Per entry: what changed, found in the first pass (only hashes and flags are kept, never contents). */
    private class Look {
        var fileChanged = false
        var fileGone = false
        var unreadable = false
        var localChanged = false
        var localGone = false

        /** The file is older than the version this phone last saw: an old copy put back, which the contact overwrites. */
        var rolledBack = false

        fun action() = FolderSyncRules.action(fileChanged, fileGone, localChanged || (rolledBack && !localGone), localGone)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private suspend fun run(allowMassDelete: Boolean): SyncReport {
        if (status.value.folderUri == null) return SyncReport()
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
        val gone = readGone()
        val parentDoc = parentDoc() ?: return SyncReport()
        val now = System.currentTimeMillis()

        // Folder listing: names and stamps only. A listing failure aborts: an unreadable folder must never look empty,
        // and one still loading must never look half empty.
        val listing = try {
            listFolder()
        } catch (_: ListingIncomplete) {
            finish(StoredStatus.of(SyncStatus.FOLDER_LOADING))
            return SyncReport()
        }
        if (listing == null) {
            finish(StoredStatus.of(SyncStatus.FOLDER_GONE))
            return SyncReport()
        }
        if (status.value.plainLeft > 0 && codec.versioned) retryPlainRemoval(listing, codec)
        val files = HashMap<String, Listed>()
        listing.forEach { (name, file) -> if (codec.accepts(name)) files[name] = file }

        // The vCard in a file, or null when it can't be read (too large, another key, altered): left alone, not deleted.
        fun readFile(name: String, uri: Uri): SyncCodec.Decoded? =
            runCatching { cr.openInputStream(uri)?.use { codec.decode(name, Bounded.readBytes(it, Bounded.Caps.SYNC_FILE)) } }.getOrNull()
                .also { stats = stats.copy(filesRead = stats.filesRead + 1) }

        // Local contacts: keys, raw ids and change tokens only.
        var heads = records.heads()
        val byKey = HashMap(heads.associateBy { it.key })
        val byId = HashMap(heads.associateBy { it.contactId })
        val byRaw = HashMap<Long, ContactRecordStore.Head>().apply { heads.forEach { h -> h.rawIds.forEach { put(it, h) } } }
        val titles = titlesTag()
        fun tokenOf(head: ContactRecordStore.Head) = "${head.token}#$titles"

        // An entry whose lookup key no longer resolves is found again by the key's new form or its raw contacts, so a
        // key change (a first sync, an account move) never looks like a deletion.
        for ((name, e) in state.toMap()) {
            if (e.contactKey in byKey) continue
            val head = idFor(e.contactKey)?.let { byId[it] } ?: e.rawIds.firstNotNullOfOrNull { byRaw[it] }
            if (head != null) state[name] = e.copy(contactKey = head.key, contactId = head.contactId, rawIds = head.rawIds)
        }

        fun entryFor(head: ContactRecordStore.Head, fileHash: String, localHash: String, stamp: String?, ver: Long, ownAt: Long?) =
            Entry(head.key, fileHash, localHash, HASH_V2, tokenOf(head), stamp, head.contactId, head.rawIds, ver, ownAt)

        // First pass: what changed. Contacts whose token moved are read a page at a time and hashed.
        val looks = HashMap<String, Look>()
        val toHash = LinkedHashMap<Long, MutableList<String>>()
        for ((name, e) in state) {
            val look = Look()
            looks[name] = look
            val head = byKey[e.contactKey]
            when {
                head == null -> look.localGone = true
                e.v == HASH_V2 && e.token != null && e.token == tokenOf(head) -> Unit
                else -> toHash.getOrPut(head.contactId) { ArrayList(1) } += name
            }
        }
        if (toHash.isNotEmpty()) {
            var fresh: List<ContactRecordStore.Head>? = null
            for (page in toHash.keys.chunked(ContactRecordStore.BATCH)) {
                val recs = records.readAllById(page, fullPhoto = false).toList()
                stats = stats.copy(contactsRead = stats.contactsRead + recs.size, largestPage = maxOf(stats.largestPage, recs.size))
                val seen = HashSet<Long>()
                // Paired by contact id: a lookup key may change between the listing and this read, an id within it can't.
                for ((id, rec) in recs) {
                    val head = byId[id] ?: continue
                    seen += id
                    for (name in toHash[id].orEmpty()) {
                        val e = state.getValue(name)
                        val hash = localHash(rec)
                        val same = if (e.v == HASH_V2) hash == e.localHash else localHashV1(withFullPhotos(rec)) == e.localHash
                        // Unchanged: carry the entry over to the current hash form and token, so the next run skips it.
                        if (same) state[name] = e.copy(localHash = hash, v = HASH_V2, token = tokenOf(head), contactId = head.contactId, rawIds = head.rawIds)
                        looks.getValue(name).localChanged = !same
                    }
                }
                // Not under its id any more: linked or unlinked between the listing and the read. Found again by its key or
                // raw contacts it counts as changed (its file is written again); only a contact found nowhere is gone.
                for (id in page) {
                    if (id in seen) continue
                    val all = fresh ?: records.heads().also { fresh = it }
                    for (name in toHash[id].orEmpty()) {
                        val e = state.getValue(name)
                        val h = idFor(e.contactKey)?.let { i -> all.firstOrNull { it.contactId == i } }
                            ?: e.rawIds.firstNotNullOfOrNull { r -> all.firstOrNull { r in it.rawIds } }
                        if (h == null) {
                            looks.getValue(name).localGone = true
                            continue
                        }
                        byKey[h.key] = h
                        byId[h.contactId] = h
                        state[name] = e.copy(contactKey = h.key, contactId = h.contactId, rawIds = h.rawIds, token = null)
                        looks.getValue(name).localChanged = true
                    }
                }
            }
        }
        for ((name, e) in state.toMap()) {
            val look = looks.getValue(name)
            val file = files[name]
            when {
                file == null -> look.fileGone = true
                e.stamp != null && file.stamp == e.stamp -> Unit
                else -> {
                    val d = readFile(name, file.uri)
                    when {
                        d == null -> look.unreadable = true // unreadable is not missing: left alone this run
                        FolderSyncRules.isRollback(d.version, e.ver) -> look.rolledBack = true
                        sha(d.vcard) == e.fileHash -> state[name] = e.copy(stamp = file.stamp, ver = maxOf(e.ver, d.version))
                        else -> look.fileChanged = true
                    }
                }
            }
        }

        // Guard against mass deletion (the whole run waits) and against deleting a contact edited here recently (that
        // deletion waits, the rest goes ahead).
        var deletions = 0
        val recent = HashSet<String>()
        for ((name, e) in state) {
            val look = looks.getValue(name)
            if (look.unreadable) continue
            when (look.action()) {
                FolderSyncRules.Action.DELETE_FILE -> deletions++
                FolderSyncRules.Action.DELETE_LOCAL -> {
                    deletions++
                    val head = byKey[e.contactKey]
                    if (head != null && FolderSyncRules.recentlyEdited(head.updatedAt, e.ownAt, now)) recent += name
                }
                else -> Unit
            }
        }
        if (!allowMassDelete && FolderSyncRules.isMassDeletion(deletions, state.size)) {
            writeState(state) // keeps the carried-over hashes and stamps
            finish(StoredStatus.of(SyncStatus.PAUSED, deletions), deletions)
            lastRun = stats
            return SyncReport()
        }
        val held = if (allowMassDelete) emptySet() else recent

        fun writeFile(name: String, bytes: ByteArray, version: Long): String? = try {
            val uri = files[name]?.uri ?: DocumentsContract.createDocument(cr, parentDoc, codec.mime, name)!!
            cr.openOutputStream(uri, "wt")!!.use { it.write(codec.encode(name, bytes, version)) }
            stats = stats.copy(filesWritten = stats.filesWritten + 1)
            files[name] = Listed(uri, null)
            stampOf(uri) ?: ""
        } catch (_: Exception) {
            null
        }

        fun readOne(head: ContactRecordStore.Head): ContactRecord? =
            records.read(head.contactId, fullPhoto = false).also { if (it != null) stats = stats.copy(contactsRead = stats.contactsRead + 1) }

        fun headOf(contactId: Long): ContactRecordStore.Head? = records.heads(listOf(contactId)).firstOrNull()

        fun version(lastSeen: Long) = if (codec.versioned) FolderSyncRules.nextVersion(lastSeen, now) else 0L

        // Remembers a deleted file's last version, so a copy of it put back isn't imported again.
        fun rememberGone(name: String, e: Entry) {
            if (codec.versioned) gone[name] = maxOf(gone[name] ?: 0L, e.ver)
        }

        // Second pass: act on each changed entry, reading only what it needs.
        for ((name, e) in state.toMap()) {
            val look = looks.getValue(name)
            if (look.unreadable) continue
            val head = byKey[e.contactKey]
            when (look.action()) {
                FolderSyncRules.Action.NONE -> Unit
                FolderSyncRules.Action.DELETE_FILE -> {
                    files[name]?.let { runCatching { DocumentsContract.deleteDocument(cr, it.uri) } }
                    state.remove(name)
                    rememberGone(name, e)
                    rep = rep.copy(deletedFiles = rep.deletedFiles + 1)
                }
                FolderSyncRules.Action.WRITE_FILE -> {
                    val rec = head?.let(::readOne) ?: continue
                    val bytes = render(withFullPhotos(rec), e.contactKey)
                    val ver = version(e.ver)
                    val stamp = writeFile(name, bytes, ver) ?: continue
                    state[name] = entryFor(head, sha(bytes), localHash(rec), stamp.ifEmpty { null }, ver, e.ownAt)
                    rep = rep.copy(written = rep.written + 1)
                }
                FolderSyncRules.Action.DELETE_LOCAL -> {
                    if (name in held) continue // waits for the user; the entry stays, so nothing else touches it
                    head?.let { contacts.delete(listOf(it.contactId)) } // journaled
                    state.remove(name)
                    rememberGone(name, e)
                    rep = rep.copy(deletedLocal = rep.deletedLocal + 1)
                }
                FolderSyncRules.Action.APPLY_FILE -> {
                    val file = files[name] ?: continue
                    val d = readFile(name, file.uri) ?: continue
                    if (FolderSyncRules.isRollback(d.version, e.ver)) continue
                    val remote = parse(d.vcard) ?: continue
                    val id = head?.contactId ?: continue
                    val newId = applyRemote(id, remote) ?: continue
                    val h = headOf(newId) ?: continue
                    val updated = readOne(h) ?: continue
                    state[name] = entryFor(h, sha(d.vcard), localHash(updated), file.stamp, d.version, h.updatedAt)
                    rep = rep.copy(updatedFromFolder = rep.updatedFromFolder + 1)
                }
                FolderSyncRules.Action.CONFLICT -> {
                    val file = files[name] ?: continue
                    val theirs = readFile(name, file.uri) ?: continue
                    val rec = head?.let(::readOne) ?: continue
                    runCatching {
                        val aside = codec.conflictName(name, System.currentTimeMillis())
                        DocumentsContract.createDocument(cr, parentDoc, codec.mime, aside)
                            ?.let { u -> cr.openOutputStream(u, "wt")!!.use { it.write(codec.encode(aside, theirs.vcard, theirs.version)) } }
                    }
                    val bytes = render(withFullPhotos(rec), e.contactKey)
                    val ver = version(maxOf(e.ver, theirs.version))
                    writeFile(name, bytes, ver)?.let { stamp -> state[name] = entryFor(head, sha(bytes), localHash(rec), stamp.ifEmpty { null }, ver, e.ownAt) }
                    rep = rep.copy(conflicts = rep.conflicts + 1)
                }
                FolderSyncRules.Action.FORGET -> {
                    state.remove(name)
                    if (look.fileGone) rememberGone(name, e)
                }
            }
        }
        writeState(state)
        heads = records.heads()
        val keyed = heads.associateBy { it.key }
        val idOf = heads.associateBy { it.contactId }

        // New files from other devices: link to a matching local contact (this phone's own file by its UID, else the
        // same phone or e-mail, else the same name when neither side has either), or import. The match keys of unpaired
        // contacts are read a page at a time, and only when there is a new file. A deleted contact's file put back is
        // recognised by its version and ignored.
        val newFiles = files.keys.filter { it !in state }
        if (newFiles.isNotEmpty()) {
            val mappedKeys = state.values.map { it.contactKey }.toHashSet()
            val unpaired = heads.filter { it.key !in mappedKeys }
            val unpairedIds = unpaired.map { it.contactId }.toHashSet()
            val byMatch = HashMap<String, Long>()
            val byName = HashMap<String, MutableList<Long>>()
            for (page in unpaired.map { it.contactId }.chunked(ContactRecordStore.BATCH)) {
                val recs = records.readAllById(page, fullPhoto = false).toList()
                stats = stats.copy(contactsRead = stats.contactsRead + recs.size, largestPage = maxOf(stats.largestPage, recs.size))
                for ((id, r) in recs) {
                    val keys = matchKeys(r)
                    keys.forEach { k -> byMatch.putIfAbsent(k, id) }
                    if (keys.isEmpty()) nameKey(r)?.let { byName.getOrPut(it) { ArrayList(1) } += id }
                }
            }
            val taken = HashSet<Long>()
            fun free(id: Long?) = id?.takeIf { it in unpairedIds && it !in taken }
            for (name in newFiles) {
                val file = files.getValue(name)
                val d = readFile(name, file.uri) ?: continue
                if (FolderSyncRules.isResurrection(d.version, gone[name])) continue
                val remote = parse(d.vcard) ?: continue
                val keys = matchKeys(remote)
                val match = free(keyed[remote.key]?.contactId) ?: free(remote.key.takeIf { it.isNotEmpty() }?.let(::idFor))
                    ?: keys.firstNotNullOfOrNull { k -> free(byMatch[k]) }
                    ?: if (keys.isEmpty()) nameKey(remote)?.let { byName[it]?.singleOrNull() }?.let(::free) else null
                if (match != null) {
                    val h = idOf[match] ?: continue
                    val rec = readOne(h) ?: continue
                    state[name] = entryFor(h, sha(d.vcard), localHash(rec), file.stamp, d.version, null)
                    taken += match
                    rep = rep.copy(linked = rep.linked + 1)
                } else {
                    val id = records.insert(remote, target = null) ?: continue
                    val h = headOf(id) ?: continue
                    val rec = readOne(h) ?: continue
                    state[name] = entryFor(h, sha(d.vcard), localHash(rec), file.stamp, d.version, h.updatedAt)
                    taken += id
                    rep = rep.copy(imported = rep.imported + 1)
                }
                gone.remove(name)
            }
            writeState(state)
            heads = records.heads()
        }

        // Local contacts never written yet, a page at a time; full photos only for the file being written.
        val mapped = state.values.map { it.contactKey }.toHashSet()
        val headsById = heads.associateBy { it.contactId }
        for (page in heads.filter { it.key !in mapped }.map { it.contactId }.chunked(ContactRecordStore.BATCH)) {
            val recs = records.readAllById(page, fullPhoto = false).toList()
            stats = stats.copy(contactsRead = stats.contactsRead + recs.size, largestPage = maxOf(stats.largestPage, recs.size))
            for ((id, rec) in recs) {
                val h = headsById[id] ?: continue
                val name = fileNameFor(h.key, codec)
                val bytes = render(withFullPhotos(rec), h.key)
                val ver = version(gone[name] ?: 0L)
                val stamp = writeFile(name, bytes, ver) ?: continue
                state[name] = entryFor(h, sha(bytes), localHash(rec), stamp.ifEmpty { null }, ver, null)
                gone.remove(name)
                rep = rep.copy(written = rep.written + 1)
            }
            writeState(state)
        }

        writeState(state)
        writeGone(gone)
        lastRun = stats
        if (held.isNotEmpty()) {
            finish(StoredStatus.of(SyncStatus.PAUSED, held.size), held.size)
        } else {
            finish(StoredStatus.of(SyncStatus.REPORT, rep.written, rep.imported, rep.updatedFromFolder, rep.deletedLocal, rep.deletedFiles, rep.conflicts, rep.linked))
        }
        contacts.refresh()
        return rep
    }

    private fun stampOf(uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) FolderSyncRules.stamp(if (c.isNull(0)) null else c.getLong(0), if (c.isNull(1)) null else c.getLong(1)) else null
        }
    }.getOrNull()

    /** A contact's phones and e-mails as match keys, for pairing a new file with a contact that has none yet. */
    private fun matchKeys(r: ContactRecord): Set<String> = r.raws.flatMap { it.rows }.mapNotNull { row ->
        when (row.mimeType) {
            Mime.PHONE -> row["data1"]?.let { PhoneIdentity.portableKey(it) }?.let { "p:$it" }
            Mime.EMAIL -> row["data1"]?.let { Duplicates.emailKey(it) }?.let { "e:$it" }
            else -> null
        }
    }.toSet()

    /** The name a contact without phones and e-mails is paired by, when exactly one such contact has it. */
    private fun nameKey(r: ContactRecord): String? = r.displayName.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ").takeIf { it.isNotEmpty() }

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
        const val LISTING_TRIES = 3
        val PARLEY_NAME = Regex("[0-9a-f]{32}")
        val WHITESPACE = Regex("\\s+")
    }
}
