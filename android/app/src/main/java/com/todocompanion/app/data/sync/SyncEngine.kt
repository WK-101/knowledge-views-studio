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

    data class Result(val ok: Boolean, val message: String)

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

    /** One sync pass. Returns a human-readable outcome. */
    suspend fun sync(context: Context, repo: AppRepository, folderUri: String, deviceId: String): Result {
        if (folderUri.isBlank()) return Result(false, "No sync folder chosen")
        if (BackupIO.tree(context, folderUri) == null) return Result(false, "Sync folder not accessible")

        val local = repo.snapshot()
        // Fold in every other device's snapshot found in the folder.
        var merged = local
        var peers = 0
        BackupIO.listJson(context, folderUri)
            .filter { it != fileName(deviceId) && it.startsWith("sync-") }
            .forEach { name ->
                val text = BackupIO.readText(context, folderUri, name) ?: return@forEach
                val remote = runCatching { Backup.decode(text) }.getOrNull() ?: return@forEach
                merged = merge(merged, remote); peers++
            }

        if (peers > 0) repo.applyMerged(merged)
        // Write this device's snapshot (the merged view) so peers can pick up our edits.
        val out = repo.snapshot().copy()
        val ok = BackupIO.writeText(context, folderUri, fileName(deviceId), Backup.encode(out))
        return if (!ok) Result(false, "Couldn't write to the sync folder")
        else Result(true, if (peers == 0) "Synced (this device only so far)" else "Synced with $peers other device${if (peers == 1) "" else "s"}")
    }

    /** Auto-backup: write a single timestamped, read-only copy of the full backup. */
    suspend fun backup(context: Context, repo: AppRepository, folderUri: String, stampName: String): Boolean {
        if (folderUri.isBlank()) return false
        return BackupIO.writeText(context, folderUri, stampName, repo.exportJson())
    }
}
