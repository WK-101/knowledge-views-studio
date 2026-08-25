package com.todocompanion.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves a file to a user-visible location WITHOUT the Storage Access Framework, so exports still work
 * on devices that have no system document picker (com.android.documentsui absent/disabled — common on
 * de-Googled / offline ROMs, which are exactly this app's audience). Fully offline, no INTERNET, and
 * no storage permission: API 29+ writes to the public Downloads collection via MediaStore; older
 * devices fall back to the app's own external files dir (always writable without a permission).
 */
object FileExport {
    /** Returns a human-facing location (e.g. "Downloads/todo-companion-backup.json") or null on failure. */
    fun saveToDownloads(context: Context, displayName: String, mime: String, bytes: ByteArray): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(item)?.use { it.write(bytes) } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(item, values, null, null)
            "Downloads/$displayName"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(dir, displayName)
            file.writeBytes(bytes)
            file.absolutePath
        }
    }.getOrNull()
}
