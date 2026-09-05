package com.todocompanion.app.data.backup

import android.content.Context
import android.net.Uri
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.sync.Crypto
import com.todocompanion.app.data.sync.Importers
import com.todocompanion.app.data.sync.SyncEngine
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.reminders.AlarmScheduler
import com.todocompanion.app.util.FileExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * R84 — folder backup / account-free sync and every restore-and-import path, split out of AppViewModel.
 * This is the most data-sensitive corner of the app (a restore overwrites the whole store), so the
 * logic is moved verbatim from the ViewModel — same crypto, same JSON-vs-external routing, same
 * messages — only the ambient references are parameterised: `settings()` for the snapshot,
 * `saveSettings()` to persist, `listsSnapshot()` for the current lists, and `displayNameOf()` for a
 * content URI's display name. The ViewModel keeps its thin `viewModelScope.launch { … }` wrappers and
 * the trivial sync-folder settings setters. `rescheduleAll` after a restore keeps alarms consistent.
 */
class RestoreManager(
    private val context: Context,
    private val repo: AppRepository,
    private val settings: () -> AppSettings,
    private val saveSettings: suspend (AppSettings) -> Unit,
    private val listsSnapshot: () -> List<ListEntity>,
    private val displayNameOf: (Uri) -> String?,
) {
    private suspend fun ensureDeviceId(): String {
        val cur = settings().deviceId
        if (cur.isNotBlank()) return cur
        val id = UUID.randomUUID().toString().take(8)
        saveSettings(settings().copy(deviceId = id))
        return id
    }

    suspend fun runSyncNow(onDone: (Boolean, String) -> Unit) {
        val folder = settings().syncFolder
        if (folder.isBlank()) { onDone(false, "Choose a sync folder first"); return }
        val dev = ensureDeviceId()
        val r = SyncEngine.sync(context, repo, folder, dev, settings().syncPassphrase)
        if (r.ok) {
            saveSettings(settings().copy(lastSyncAt = System.currentTimeMillis(), deviceId = dev, lastSyncSummary = r.message))
            AlarmScheduler.rescheduleAll(context, repo)
        }
        onDone(r.ok, r.message)
    }

    suspend fun runBackupNow(onDone: (Boolean) -> Unit) {
        val folder = settings().autoBackupFolder.ifBlank { settings().syncFolder }
        if (folder.isBlank()) { onDone(false); return }
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val ok = SyncEngine.backup(context, repo, folder, "todo-backup-$stamp.json", settings().syncPassphrase)
        // Stamp the last-backup time so the Momentum data-safety card reflects manual backups too.
        if (ok) saveSettings(settings().copy(lastBackupAt = System.currentTimeMillis()))
        onDone(ok)
    }

    /** Import tasks from a Todoist/TickTick CSV or MLO OPML/.mlobak file. */
    suspend fun importExternal(uri: Uri, onDone: (Boolean, String) -> Unit) {
        val text = readImportText(uri = uri)
        importExternalText(text, onDone)
    }

    /** Read an import file as text, unzipping/decoding MLO `.mlobak` (ZIP / UTF-16 XML). */
    private suspend fun readImportText(uri: Uri? = null, file: java.io.File? = null): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            when {
                uri != null -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                file != null -> file.readBytes()
                else -> null
            }
        }.getOrNull() ?: return@withContext null
        Importers.bytesToText(bytes)
    }

    /** Core of external (Todoist/TickTick CSV, MLO OPML) import — reused by the in-app restore browser. */
    private suspend fun importExternalText(text: String?, onDone: (Boolean, String) -> Unit) {
        val ok = runCatching {
            if (text == null) return@runCatching null
            val parsed = Importers.parse(text) ?: return@runCatching null
            val listIds = HashMap<String, String>()
            val existing = listsSnapshot().associateBy { it.name.lowercase() }
            parsed.rows.forEach { row ->
                val listId = listIds.getOrPut(row.list) {
                    existing[row.list.lowercase()]?.id ?: repo.createList(row.list)
                }
                val id = repo.createTask(listId, row.title, importance = row.importance, urgency = row.urgency, dueDate = row.dueMillis)
                if (row.note.isNotBlank() || row.completed) repo.getTask(id)?.let { t ->
                    repo.saveTask(t.copy(note = row.note, completed = row.completed, completedAt = if (row.completed) System.currentTimeMillis() else null))
                }
                if (row.tags.isNotEmpty()) {
                    val ws = settings().activeWorkspaceId
                    val tagExisting = repo.getTagsOnce().filter { it.workspaceId == ws }.associateBy { it.name.lowercase() }
                    val ids = row.tags.map { name -> tagExisting[name.lowercase()]?.id ?: UUID.randomUUID().toString().also { repo.upsertTag(TagEntity(it, name, workspaceId = ws)) } }
                    repo.setTaskTags(id, ids.distinct())
                }
            }
            parsed.source to parsed.rows.size
        }.getOrNull()
        if (ok == null) onDone(false, "Couldn't read that file — export a Todoist/TickTick CSV or MLO OPML")
        else onDone(true, "Imported ${ok.second} tasks from ${ok.first}")
    }

    /** [broad] = true lists any JSON/CSV a user dropped into the import inbox, not just our own backups. */
    suspend fun loadSavedBackups(broad: Boolean = false): List<FileExport.SavedFile> =
        withContext(Dispatchers.IO) { FileExport.listSaved(context, broad) }

    /** The folder a user copies a backup into to import it with no picker and no permission. */
    fun importInboxHint(): String = FileExport.importInboxHint(context)

    /**
     * Import from pasted text — the last-resort channel that needs no file, picker or storage access.
     * JSON restores the whole store; anything else routes through the Todoist/TickTick/MLO parser.
     */
    suspend fun importPastedText(text: String, onDone: (Boolean, String) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) { onDone(false, "Nothing to import — paste a backup first"); return }
        if (t.startsWith("{") || t.startsWith("[")) {
            val ok = runCatching {
                val plain = Crypto.decrypt(t, settings().syncPassphrase) ?: t
                repo.importJsonReplace(plain); AlarmScheduler.rescheduleAll(context, repo); true
            }.getOrDefault(false)
            onDone(ok, if (ok) "Restored from pasted backup" else "That text isn't a valid ToDo Companion backup")
        } else importExternalText(t, onDone)
    }

    suspend fun restoreSaved(s: FileExport.SavedFile, onDone: (Boolean, String) -> Unit) {
        val text = readImportText(uri = s.uri, file = s.file)
        if (text == null) { onDone(false, "Couldn't read that file"); return }
        if (s.name.endsWith(".json", ignoreCase = true)) {
            val ok = runCatching {
                val plain = Crypto.decrypt(text, settings().syncPassphrase) ?: text
                repo.importJsonReplace(plain); AlarmScheduler.rescheduleAll(context, repo); true
            }.getOrDefault(false)
            onDone(ok, if (ok) "Restored from ${s.name}" else "Restore failed — is this a ToDo Companion backup?")
        } else importExternalText(text, onDone)
    }

    /**
     * E9: import a backup a file manager handed us via "Open with" / "Share". Reuses the restore
     * pipeline; sniffs JSON vs external (CSV/OPML) when the filename carries no usable extension.
     */
    suspend fun importFromIntent(uri: Uri, merge: Boolean = false, onDone: (Boolean, String) -> Unit) {
        val name = (displayNameOf(uri) ?: uri.lastPathSegment ?: "").lowercase()
        val ext = name.substringAfterLast('.', "")
        val text = readImportText(uri = uri)
        if (text.isNullOrBlank()) { onDone(false, "Couldn't read that file. Try 'Share → ToDo Companion' from your file manager."); return }
        val external = Importers.parse(text)
        val externalExt = ext in setOf("mlobak", "ml", "mlt", "opml", "csv", "tsv", "xml")
        val jsonExt = ext == "json" || ext == "todobackup"
        val looksJson = jsonExt || (ext.isEmpty() && text.trimStart().firstOrNull()?.let { it == '{' || it == '[' } == true)
        when {
            externalExt -> {
                if (external != null) importExternalText(text, onDone)
                else onDone(false, "Couldn't read that ${ext.uppercase()} file. In MyLifeOrganized, use Menu ▸ Backup/Export ▸ OPML and share the .opml — some .mlobak archives are password-protected or in a format we can't open.")
            }
            external != null && !looksJson -> importExternalText(text, onDone)
            looksJson -> {
                val ok = runCatching {
                    val plain = Crypto.decrypt(text, settings().syncPassphrase) ?: text
                    if (merge) repo.importJsonMerge(plain) else repo.importJsonReplace(plain)
                    AlarmScheduler.rescheduleAll(context, repo); true
                }.getOrDefault(false)
                val verb = if (merge) "Merged" else "Restored"
                if (ok) onDone(true, "$verb ${name.ifBlank { "your backup" }}")
                else if (external != null) importExternalText(text, onDone)
                else onDone(false, "That file isn't a valid ToDo Companion backup")
            }
            else -> importExternalText(text, onDone)
        }
    }

    suspend fun importFrom(uri: Uri, onDone: (Boolean) -> Unit) {
        val ok = runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching false
            val text = Crypto.decrypt(raw, settings().syncPassphrase) ?: return@runCatching false
            repo.importJsonReplace(text)
            AlarmScheduler.rescheduleAll(context, repo)
            true
        }.getOrDefault(false)
        onDone(ok)
    }
}
