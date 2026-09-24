package app.parley.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.parley.common.Duplicates
import app.parley.common.backup.RecordJson
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

data class SyncStatus(
    val folderUri: String? = null,
    val folderName: String? = null,
    val auto: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastResult: String? = null,
    /** Deletions a paused run is waiting for the user to confirm. */
    val pendingDeletions: Int = 0,
)

data class SyncReport(
    val written: Int = 0,
    val imported: Int = 0,
    val updatedFromFolder: Int = 0,
    val deletedLocal: Int = 0,
    val deletedFiles: Int = 0,
    val conflicts: Int = 0,
    val linked: Int = 0,
) {
    fun summary(): String = buildList {
        if (imported > 0) add("$imported new from the folder")
        if (updatedFromFolder > 0) add("$updatedFromFolder updated from the folder")
        if (written > 0) add("$written written to the folder")
        if (deletedLocal > 0) add("$deletedLocal removed (deleted on another device)")
        if (deletedFiles > 0) add("$deletedFiles files removed")
        if (linked > 0) add("$linked matched to existing contacts")
        if (conflicts > 0) add("$conflicts conflicts kept as .conflict files")
    }.ifEmpty { listOf("Everything is in sync") }.joinToString(" · ")
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
    )

    fun setFolder(uri: Uri?, name: String?) {
        prefs.edit().remove("pendingDeletions").remove("lastResult").apply()
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.edit().putString("folder", uri?.toString()).putString("folderName", name).apply()
        stateFile.delete() // new folder: start fresh (first sync links matching contacts instead of duplicating)
        _status.value = load()
    }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        _status.value = load()
    }

    private data class Entry(val contactKey: String, val fileHash: String, val localHash: String)

    private fun readState(): MutableMap<String, Entry> = runCatching {
        val o = JSONObject(stateFile.readText())
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Entry(it.getString("k"), it.getString("f"), it.getString("l")) } }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    /** Written to a temporary file and renamed, so a crash never leaves a half-written state (which would look like "everything new"). */
    private fun writeState(s: Map<String, Entry>) {
        val o = JSONObject()
        s.forEach { (name, e) -> o.put(name, JSONObject().put("k", e.contactKey).put("f", e.fileHash).put("l", e.localHash)) }
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
    }

    private fun sha(bytes: ByteArray) = RecordJson.sha256Hex(bytes)

    private fun localHash(r: ContactRecord) = sha(RecordJson.encode(VCardMapper.canonical(r.withoutMessengers())).toByteArray())

    private fun render(r: ContactRecord, uid: String): ByteArray {
        val card = VCardMapper.toVCard(r.withoutMessengers(), records.groupTitles())
        card.uid = ezvcard.property.Uid(uid)
        return Ezvcard.write(card).version(VCardVersion.V4_0).prodId(false).go().toByteArray()
    }

    private fun parse(bytes: ByteArray): ContactRecord? = runCatching {
        Ezvcard.parse(String(bytes, Charsets.UTF_8)).first()?.let { VCardMapper.fromVCard(it) }
    }.getOrNull()

    /** Hashed, so names are short, filesystem-safe and never collide after sanitising. */
    private fun fileNameFor(key: String) = sha(key.toByteArray()).take(32) + ".vcf"

    private fun finish(message: String, pending: Int = 0) {
        prefs.edit().putLong("lastAt", System.currentTimeMillis()).putString("lastResult", message).putInt("pendingDeletions", pending).apply()
        _status.value = load()
    }

    /**
     * Runs one sync. Stops without changing anything when the folder can't be listed or contacts can't be read,
     * and pauses when a run would delete many contacts or files at once (a folder that was emptied or swapped,
     * a sync app that hasn't finished): the user confirms with [allowMassDelete].
     */
    suspend fun syncNow(allowMassDelete: Boolean = false): SyncReport = mutex.withLock {
        withContext(Dispatchers.IO) {
            val folder = status.value.folderUri?.let(Uri::parse) ?: return@withContext SyncReport()
            if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS) || !Permissions.has(context, android.Manifest.permission.WRITE_CONTACTS)) {
                finish("Not synced: Parley needs access to contacts")
                return@withContext SyncReport()
            }
            var rep = SyncReport()
            val state = readState()
            val treeId = DocumentsContract.getTreeDocumentId(folder)
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(folder, treeId)

            // Folder files. A listing failure aborts: an unreadable folder must never look like an empty one.
            data class F(val uri: Uri, val bytes: ByteArray?)
            val files = HashMap<String, F>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId)
            val listed = try {
                cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (!name.endsWith(".vcf") || name.contains(".conflict")) continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))
                        // Unreadable (bytes == null) is not the same as missing: such files are left alone this run.
                        files[name] = F(uri, runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull())
                    }
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
            if (!listed) {
                finish("Not synced: the folder can't be opened. Choose it again if it moved.")
                return@withContext SyncReport()
            }

            // Local contacts; entries whose lookup key changed (after a merge or account sync) are re-resolved.
            val local = records.readAll(fullPhoto = true).associateBy { it.key }.toMutableMap()
            for ((name, e) in state.toMap()) {
                if (e.contactKey in local) continue
                val id = idFor(e.contactKey) ?: continue
                val rec = records.read(id, fullPhoto = true) ?: continue
                local[rec.key] = rec
                state[name] = e.copy(contactKey = rec.key)
            }

            // Mass-deletion guard.
            var deletions = 0
            for ((name, e) in state) {
                val file = files[name]
                val rec = local[e.contactKey]
                if (file == null && rec != null && localHash(rec) == e.localHash) deletions++
                if (rec == null && file?.bytes != null && sha(file.bytes) == e.fileHash) deletions++
            }
            if (!allowMassDelete && deletions > 3 && deletions * 4 > state.size) {
                finish("Paused: this sync would delete $deletions contacts or files. Check the folder, then confirm.", deletions)
                return@withContext SyncReport()
            }

            fun writeFile(name: String, bytes: ByteArray): Boolean = try {
                val uri = files[name]?.uri ?: DocumentsContract.createDocument(cr, parentDoc, "text/vcard", name)!!
                cr.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                true
            } catch (_: Exception) {
                false
            }

            // 1. Known entries: three-way merge.
            for ((name, e) in state.toMap()) {
                val file = files[name]
                if (file != null && file.bytes == null) continue
                val rec = local[e.contactKey]
                val fileChanged = file == null || sha(file.bytes!!) != e.fileHash
                val localChanged = rec == null || localHash(rec) != e.localHash
                when {
                    !fileChanged && !localChanged -> Unit
                    !fileChanged && localChanged -> if (rec == null) {
                        file?.let { runCatching { DocumentsContract.deleteDocument(cr, it.uri) } }
                        state.remove(name); rep = rep.copy(deletedFiles = rep.deletedFiles + 1)
                    } else {
                        val bytes = render(rec, e.contactKey)
                        if (writeFile(name, bytes)) { state[name] = Entry(rec.key, sha(bytes), localHash(rec)); rep = rep.copy(written = rep.written + 1) }
                    }
                    fileChanged && !localChanged -> if (file == null) {
                        rec?.let { idFor(it.key)?.let { id -> contacts.delete(listOf(id)) } } // journaled
                        rec?.let { local.remove(it.key) }
                        state.remove(name); rep = rep.copy(deletedLocal = rep.deletedLocal + 1)
                    } else {
                        val bytes = file.bytes!!
                        val remote = parse(bytes) ?: continue
                        val updated = if (rec == null) null else applyRemote(rec, remote)
                        if (updated != null) {
                            local.remove(rec!!.key)
                            local[updated.key] = updated
                            state[name] = Entry(updated.key, sha(bytes), localHash(updated))
                            rep = rep.copy(updatedFromFolder = rep.updatedFromFolder + 1)
                        }
                    }
                    else -> { // both changed
                        if (file != null && rec != null) {
                            runCatching { DocumentsContract.createDocument(cr, parentDoc, "text/vcard", name.removeSuffix(".vcf") + ".conflict-" + System.currentTimeMillis() + ".vcf")?.let { u -> cr.openOutputStream(u, "wt")!!.use { it.write(file.bytes!!) } } }
                            val bytes = render(rec, e.contactKey)
                            if (writeFile(name, bytes)) state[name] = Entry(rec.key, sha(bytes), localHash(rec))
                            rep = rep.copy(conflicts = rep.conflicts + 1)
                        } else {
                            // Deleted here and edited there (or the reverse): keep the surviving version.
                            state.remove(name)
                        }
                    }
                }
            }
            writeState(state)

            // 2. New files from other devices: link to a matching local contact (same phone or e-mail), or import.
            fun keysOf(r: ContactRecord): Set<String> = r.raws.flatMap { it.rows }.mapNotNull { row ->
                when (row.mimeType) {
                    Mime.PHONE -> row["data1"]?.let { Duplicates.phoneKey(it) }?.let { "p:$it" }
                    Mime.EMAIL -> row["data1"]?.let { Duplicates.emailKey(it) }?.let { "e:$it" }
                    else -> null
                }
            }.toSet()
            val mappedKeys = state.values.map { it.contactKey }.toHashSet()
            val byKey = HashMap<String, ContactRecord>()
            local.values.filter { it.key !in mappedKeys }.forEach { r -> keysOf(r).forEach { k -> byKey.putIfAbsent(k, r) } }
            for ((name, file) in files) {
                if (name in state) continue
                val bytes = file.bytes ?: continue
                val remote = parse(bytes) ?: continue
                val match = keysOf(remote).firstNotNullOfOrNull { k -> byKey[k]?.takeIf { it.key !in mappedKeys } }
                if (match != null) {
                    state[name] = Entry(match.key, sha(bytes), localHash(match))
                    mappedKeys += match.key
                    rep = rep.copy(linked = rep.linked + 1)
                } else {
                    val id = records.insert(remote, target = null) ?: continue
                    records.read(id, fullPhoto = true)?.let { nr -> state[name] = Entry(nr.key, sha(bytes), localHash(nr)); local[nr.key] = nr; mappedKeys += nr.key }
                    rep = rep.copy(imported = rep.imported + 1)
                }
            }
            writeState(state)

            // 3. Local contacts never written yet.
            val mapped = state.values.map { it.contactKey }.toHashSet()
            for (rec in local.values) {
                if (rec.key in mapped) continue
                val name = fileNameFor(rec.key)
                val bytes = render(rec, rec.key)
                if (writeFile(name, bytes)) { state[name] = Entry(rec.key, sha(bytes), localHash(rec)); rep = rep.copy(written = rep.written + 1) }
            }

            writeState(state)
            finish(rep.summary())
            contacts.refresh()
            rep
        }
    }

    /**
     * Applies a remote edit to [rec] in place (same contact id, links, call history and read-only parts), journaled
     * first. Falls back to a fresh copy only when the contact has nothing writable. Returns the contact as stored now.
     */
    private suspend fun applyRemote(rec: ContactRecord, remote: ContactRecord): ContactRecord? {
        val id = idFor(rec.key) ?: return null
        contacts.recordChange(listOf(id), "EDIT")
        val (target, writable) = contacts.writableRaws(id)
        return if (target != null) {
            if (!records.replaceContent(id, remote, target, writable)) return null
            records.read(id, fullPhoto = true)
        } else {
            val newId = records.insert(remote, target = null) ?: return null
            runCatching { contacts.delete(listOf(id)) }
            records.read(newId, fullPhoto = true)
        }
    }

    private fun idFor(key: String): Long? = runCatching {
        android.provider.ContactsContract.Contacts.lookupContact(cr, Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_LOOKUP_URI, key))
            ?.let { android.content.ContentUris.parseId(it) }
    }.getOrNull()
}
