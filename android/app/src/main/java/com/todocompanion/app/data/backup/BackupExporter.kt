package com.todocompanion.app.data.backup

import android.content.Context
import android.net.Uri
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.domain.port.Ics
import com.todocompanion.app.util.FileExport
import java.time.Duration
import java.time.ZoneId

/**
 * All file-level backup I/O — writing an export to a user-chosen `Uri` (or the public Downloads
 * folder) and reading a habit CSV / `.ics` back in.
 *
 * R75 — split out of the 4,987-line AppViewModel so the god-object shrinks and this logic becomes
 * independently unit-testable. It holds NO UI state: just the repository, a `Context` for the
 * `ContentResolver`, and a provider for the active time zone. The ViewModel keeps only the thin
 * `viewModelScope.launch { … }` wrappers and any UI glue (toasts, settings stamps, widget refreshes).
 */
class BackupExporter(
    private val context: Context,
    private val repo: AppRepository,
    private val zoneProvider: () -> ZoneId,
) {
    private val resolver get() = context.contentResolver

    suspend fun exportJson(uri: Uri): Boolean = runCatching {
        val json = repo.exportJson()
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
    }.isSuccess

    suspend fun exportMarkdown(uri: Uri, includeCompleted: Boolean): Boolean = runCatching {
        val md = repo.exportMarkdown(includeCompleted)
        resolver.openOutputStream(uri)?.use { it.write(md.toByteArray()) }
    }.isSuccess

    suspend fun exportCsv(uri: Uri, includeCompleted: Boolean): Boolean = runCatching {
        val csv = repo.exportCsv(includeCompleted)
        resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
    }.isSuccess

    suspend fun exportIcs(uri: Uri, includeCompleted: Boolean): Boolean = runCatching {
        val ics = repo.exportIcs(includeCompleted)
        resolver.openOutputStream(uri)?.use { it.write(ics.toByteArray()) }
    }.isSuccess

    suspend fun exportHabitsCsv(uri: Uri): Boolean = runCatching {
        val csv = repo.exportHabitsCsv()
        resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
    }.isSuccess

    /**
     * SAF-free export fallback: write the chosen export straight into the public Downloads folder
     * (or the app's files dir on older devices). Returns a user-facing location like
     * "Downloads/todo-companion-backup.json", or null on failure / an unknown [kind].
     */
    suspend fun downloadExport(kind: String): String? = runCatching {
        val (content, name, mime) = when (kind) {
            "json" -> Triple(repo.exportJson(), "todo-companion-backup.json", "application/json")
            "md" -> Triple(repo.exportMarkdown(true), "todo-companion.md", "text/markdown")
            "csv" -> Triple(repo.exportCsv(true), "todo-companion.csv", "text/csv")
            "ics" -> Triple(repo.exportIcs(false), "todo-companion.ics", "text/calendar")
            "habits" -> Triple(repo.exportHabitsCsv(), "todo-companion-habits.csv", "text/csv")
            else -> return@runCatching null
        }
        FileExport.saveToDownloads(context, name, mime, content.toByteArray())
    }.getOrNull()

    /** Read a habit-CSV [uri] and import it. Returns check-ins imported, 0 if none, or -1 if unreadable. */
    suspend fun importHabitsCsv(uri: Uri): Int = runCatching {
        val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching -1
        repo.importHabitsCsv(text)
    }.getOrDefault(-1)

    /**
     * CU3 — read an `.ics` calendar [uri] and add each event as a task (the other half of the 2-way
     * bridge). Returns tasks created, 0 if the file had no events, or -1 if it couldn't be read.
     */
    suspend fun importIcsAsTasks(uri: Uri): Int {
        val text = runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            ?: return -1
        val events = Ics.parse(text)
        if (events.isEmpty()) return 0
        val zone = zoneProvider()
        var n = 0
        events.forEach { e ->
            val due = e.start.atZone(zone).toInstant().toEpochMilli()
            val id = repo.createTask(ListEntity.INBOX_ID, e.summary.ifBlank { "Event" }, dueDate = due)
            val durMin = if (!e.allDay && e.end != null) Duration.between(e.start, e.end).toMinutes().toInt().coerceIn(0, 1440) else null
            if (e.allDay || durMin != null) repo.getTask(id)?.let { repo.saveTask(it.copy(isAllDay = e.allDay, durationMin = durMin)) }
            n++
        }
        return n
    }
}
