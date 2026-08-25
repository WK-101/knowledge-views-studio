package com.todocompanion.app.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Reads and writes plain-text files inside a user-chosen folder (a SAF tree URI). Fully local —
 * the folder can be on the device or on a sync-capable drive the user already trusts (Drive,
 * Dropbox, Syncthing). The app never opens a network socket; it just writes files.
 */
object BackupIO {

    private const val MIME = "application/json"

    fun tree(context: Context, folderUri: String): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) }.getOrNull()?.takeIf { it.isDirectory }

    /** Overwrite (or create) [name] in the folder with [text]. Returns success. */
    fun writeText(context: Context, folderUri: String, name: String, text: String): Boolean {
        val dir = tree(context, folderUri) ?: return false
        return runCatching {
            val existing = dir.findFile(name)
            val file = existing ?: dir.createFile(MIME, name) ?: return false
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray()) }
            true
        }.getOrDefault(false)
    }

    fun readText(context: Context, folderUri: String, name: String): String? {
        val dir = tree(context, folderUri) ?: return null
        val file = dir.findFile(name)?.takeIf { it.isFile } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    /** All *.json file names in the folder (used to gather every device's sync snapshot). */
    fun listJson(context: Context, folderUri: String): List<String> {
        val dir = tree(context, folderUri) ?: return emptyList()
        return runCatching { dir.listFiles().mapNotNull { it.name }.filter { it.endsWith(".json", ignoreCase = true) } }
            .getOrDefault(emptyList())
    }
}
