package com.todocompanion.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves a file to a reachable location WITHOUT the Storage Access Framework, so exports still work on
 * devices that have no system document picker (com.android.documentsui absent/disabled — common on
 * de-Googled / offline ROMs, this app's audience). Fully offline, no INTERNET, no storage permission.
 *
 * It cascades through progressively more reliable targets and returns the first that succeeds, so an
 * export never silently fails: public Downloads (MediaStore, API 29+) → the app's external files dir →
 * the app's internal files dir (always writable). Only returns null if every target throws.
 */
object FileExport {
    /** Returns a human-facing location (e.g. "Downloads/todo-companion-backup.json"), or null if all targets fail. */
    fun saveToDownloads(context: Context, displayName: String, mime: String, bytes: ByteArray): String? {
        // 1) Public Downloads via MediaStore (visible to file managers, no permission) — API 29+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                resolver.openOutputStream(item)?.use { it.write(bytes) } ?: error("no output stream")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(item, values, null, null)
                return "Downloads/$displayName"
            }
        }
        // 2) Legacy public Downloads dir (older devices, best-effort — may need permission on some).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dir != null) { dir.mkdirs(); val f = File(dir, displayName); f.writeBytes(bytes); return "Downloads/$displayName" }
            }
        }
        // 3) App's external files dir — always writable, no permission (Android/data/<pkg>/files/exports).
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val f = File(dir, displayName); f.writeBytes(bytes)
            return f.absolutePath
        }
        // 4) App's internal files dir — guaranteed to work.
        runCatching {
            val dir = File(context.filesDir, "exports").apply { mkdirs() }
            val f = File(dir, displayName); f.writeBytes(bytes)
            return f.absolutePath
        }
        return null
    }

    /** A backup/export file the app can restore from without a system picker. */
    data class SavedFile(val name: String, val location: String, val whenMillis: Long, val uri: android.net.Uri?, val file: File?)

    /**
     * The app-owned "drop inbox" the user copies a backup INTO to import it with no system picker and
     * no storage permission. `Android/data/<pkg>/files/import` is reachable over USB / any file manager,
     * and the app can always read its own external-files dir on every API level — the one universal,
     * permissionless channel for a *user-supplied* file. mkdirs() so it exists to be found.
     */
    fun importInboxDir(context: Context): File? = runCatching {
        File(context.getExternalFilesDir(null), "import").apply { mkdirs() }
    }.getOrNull()

    /** A human-readable hint of where to drop a file for import (external path preferred). */
    fun importInboxHint(context: Context): String =
        (importInboxDir(context)?.absolutePath ?: File(context.filesDir, "import").absolutePath)

    /**
     * Find restorable files without a system picker. Newest first.
     *
     * [broad] = false (default): only files this app itself produced (strict name filter) — the
     * "Restore from a saved backup" browser. [broad] = true: any JSON/CSV/etc the user may have
     * dropped into the import inbox or that lives in the app's dirs / public Downloads — the
     * "Import a file on device" browser, so a `Todoist_2026.csv` or `my-backup.json` is listed too.
     */
    fun listSaved(context: Context, broad: Boolean = false): List<SavedFile> {
        val out = ArrayList<SavedFile>()
        val exts = setOf("json", "csv", "md", "ics", "opml", "txt")
        fun matches(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in exts) return false
            if (broad) return true
            return name.contains("todo-companion") || name.contains("backup") || name.endsWith(".json")
        }
        // MediaStore Downloads (API 29+) — app-created entries are always readable here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val namec = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val datec = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val name = c.getString(namec) ?: continue
                    if (!matches(name)) continue
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idc))
                    out += SavedFile(name, "Downloads", c.getLong(datec) * 1000L, uri, null)
                }
            }
        }
        // App-owned dirs: the import inbox (user drops files here) + export dirs (round-trip).
        listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "import") },
            File(context.filesDir, "import"),
            context.getExternalFilesDir(null)?.let { File(it, "exports") },
            File(context.filesDir, "exports"),
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
        ).forEach { dir ->
            runCatching {
                dir.mkdirs()
                val loc = if (dir.name == "import") "Import inbox" else "App storage"
                dir.listFiles()?.filter { it.isFile && matches(it.name) }?.forEach { f -> out += SavedFile(f.name, loc, f.lastModified(), null, f) }
            }
        }
        return out.distinctBy { (it.uri?.toString() ?: it.file?.absolutePath) }.sortedByDescending { it.whenMillis }
    }
}
