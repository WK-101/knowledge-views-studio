package com.todocompanion.app.data.sync

import android.content.Context
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.domain.port.Backup
import com.todocompanion.app.domain.port.BackupFile

/**
 * Account-free, server-free sync over a shared folder. Each device writes its own snapshot file
 * (`sync-<deviceId>.json`); a sync reads every snapshot in the folder, merges them with the local
 * data, applies the result locally, and writes this device's snapshot back. Because each device
 * only ever writes its *own* file, there are no write conflicts — devices converge as they take
 * turns syncing. Merge policy is last-write-wins: tasks by their own `updatedAt`, everything else
 * by the snapshot's export time.
 */
object SyncEngine {

    data class Result(val ok: Boolean, val message: String, val updated: Int = 0, val added: Int = 0, val peers: Int = 0)

    private fun fileName(deviceId: String) = "sync-$deviceId.json"

    /** Merge [b] into [a]. Tasks reconcile by updatedAt; structural rows by the newer snapshot. */
    fun merge(a: BackupFile, b: BackupFile): BackupFile {
        val bNewer = b.exportedAt >= a.exportedAt
        fun <T> unionById(l: List<T>, r: List<T>, id: (T) -> String, newer: (T, T) -> T): List<T> {
            val m = LinkedHashMap<String, T>(l.size + r.size)
            l.forEach { m[id(it)] = it }
            r.forEach { e -> val k = id(e); val cur = m[k]; m[k] = if (cur == null) e else newer(cur, e) }
            return m.values.toList()
        }
        // Structural rows have no per-row timestamp, so the more recently exported snapshot wins.
        fun <T> pref(x: T, y: T): T = if (bNewer) y else x
        return a.copy(
            exportedAt = maxOf(a.exportedAt, b.exportedAt),
            tasks = unionById(a.tasks, b.tasks, { it.id }) { x, y -> if (y.updatedAt >= x.updatedAt) y else x },
            workspaces = unionById(a.workspaces, b.workspaces, { it.id }, ::pref),
            filters = unionById(a.filters, b.filters, { it.id }, ::pref),
            habits = unionById(a.habits, b.habits, { it.id }, ::pref),
            habitCheckins = unionById(a.habitCheckins, b.habitCheckins, { "${it.habitId}|${it.epochDay}" }) { x, y -> if (y.count >= x.count) y else x },
            focusSessions = unionById(a.focusSessions, b.focusSessions, { it.id }, ::pref),
            folders = unionById(a.folders, b.folders, { it.id }, ::pref),
            lists = unionById(a.lists, b.lists, { it.id }, ::pref),
            checklist = unionById(a.checklist, b.checklist, { it.id }, ::pref),
            tags = unionById(a.tags, b.tags, { it.id }, ::pref),
            taskTags = unionById(a.taskTags, b.taskTags, { "${it.taskId}|${it.tagId}" }, ::pref),
            contexts = unionById(a.contexts, b.contexts, { it.id }, ::pref),
            taskContexts = unionById(a.taskContexts, b.taskContexts, { "${it.taskId}|${it.contextId}" }, ::pref),
            reminders = unionById(a.reminders, b.reminders, { it.id }, ::pref),
            dependencies = unionById(a.dependencies, b.dependencies, { "${it.taskId}|${it.dependsOnTaskId}" }, ::pref),
            attachments = unionById(a.attachments, b.attachments, { it.id }, ::pref),
            flags = unionById(a.flags, b.flags, { it.id }, ::pref),
            templates = unionById(a.templates, b.templates, { it.id }, ::pref),
            countdowns = unionById(a.countdowns, b.countdowns, { it.id }, ::pref),
            activities = unionById(a.activities, b.activities, { it.id }, ::pref),
            // Settings never sync — they hold device-specific folder URIs and the device id.
            settings = a.settings,
        )
    }

    /** One sync pass. Encrypts written files when [passphrase] is set; reports what changed (G2). */
    suspend fun sync(context: Context, repo: AppRepository, folderUri: String, deviceId: String, passphrase: String = ""): Result {
        if (folderUri.isBlank()) return Result(false, "No sync folder chosen")
        if (BackupIO.tree(context, folderUri) == null) return Result(false, "Sync folder not accessible")

        val local = repo.snapshot()
        val localById = local.tasks.associateBy { it.id }
        var merged = local
        var peers = 0
        var wrongPass = false
        BackupIO.listJson(context, folderUri)
            .filter { it != fileName(deviceId) && it.startsWith("sync-") }
            .forEach { name ->
                val raw = BackupIO.readText(context, folderUri, name) ?: return@forEach
                val text = Crypto.decrypt(raw, passphrase)
                if (text == null) { wrongPass = true; return@forEach }   // encrypted with a different key
                val remote = runCatching { Backup.decode(text) }.getOrNull() ?: return@forEach
                merged = merge(merged, remote); peers++
            }

        // What changed from peers: tasks that are new locally or whose version was replaced (G2).
        var updated = 0; var added = 0
        merged.tasks.forEach { m ->
            val was = localById[m.id]
            if (was == null) added++ else if (was.updatedAt != m.updatedAt) updated++
        }

        if (peers > 0) repo.applyMerged(merged)
        val out = repo.snapshot()
        val payload = Crypto.encrypt(Backup.encode(out), passphrase)
        val ok = BackupIO.writeText(context, folderUri, fileName(deviceId), payload)
        if (!ok) return Result(false, "Couldn't write to the sync folder")
        val changed = updated + added
        val msg = buildString {
            append(if (peers == 0) "Synced — this device only so far" else "Synced with $peers device${if (peers == 1) "" else "s"}")
            if (changed > 0) append(" · $changed task${if (changed == 1) "" else "s"} updated")
            if (wrongPass) append(" · a peer file couldn't be decrypted (check the passphrase)")
        }
        return Result(true, msg, updated, added, peers)
    }

    /** Auto-backup: write a timestamped copy of the full backup, encrypted when a passphrase is set. */
    suspend fun backup(context: Context, repo: AppRepository, folderUri: String, stampName: String, passphrase: String = ""): Boolean {
        if (folderUri.isBlank()) return false
        val payload = Crypto.encrypt(repo.exportJson(), passphrase)
        return BackupIO.writeText(context, folderUri, stampName, payload)
    }
}
