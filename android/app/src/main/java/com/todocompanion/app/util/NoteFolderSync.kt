package com.todocompanion.app.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.todocompanion.app.data.entity.NoteEntity

/**
 * Wave K — read/write a folder of `.md` files (one per note) in a user-picked SAF tree. Pure file
 * I/O: the caller supplies the notes to write (with their resolved tag names) and receives back the
 * parsed notes to upsert. Reuses the same DocumentFile mechanics as the backup/sync folder; opens no
 * network socket and needs no storage permission (the tree grant is the user's own explicit pick).
 */
object NoteFolderSync {

    private const val MIME = "text/markdown"

    private fun tree(context: Context, folderUri: String): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) }.getOrNull()?.takeIf { it.isDirectory }

    /** Write one `.md` per note into the folder (overwriting a same-named file). Returns how many were written. */
    fun exportAll(context: Context, folderUri: String, notes: List<Pair<NoteEntity, List<String>>>): Int {
        val dir = tree(context, folderUri) ?: return 0
        val taken = mutableSetOf<String>()
        var written = 0
        for ((note, tagNames) in notes) {
            val name = NoteMarkdownFile.fileName(note, taken)
            val ok = runCatching {
                val existing = dir.findFile(name)
                val file = existing ?: dir.createFile(MIME, name) ?: return@runCatching false
                context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                    it.write(NoteMarkdownFile.serialize(note, tagNames).toByteArray())
                }
                true
            }.getOrDefault(false)
            if (ok) written++
        }
        return written
    }

    /** Read and parse every `.md` / `.markdown` file in the folder. */
    fun importAll(context: Context, folderUri: String): List<NoteMarkdownFile.Parsed> {
        val dir = tree(context, folderUri) ?: return emptyList()
        val files = runCatching {
            dir.listFiles().filter { f ->
                f.isFile && (f.name?.endsWith(".md", true) == true || f.name?.endsWith(".markdown", true) == true)
            }
        }.getOrDefault(emptyList())
        return files.mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            val text = runCatching {
                context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            runCatching { NoteMarkdownFile.parse(name, text) }.getOrNull()
        }
    }

    // ── Wave N · the living mirror: per-file I/O + a hidden .kairo/ config folder ──

    /** Parse every `.md`, keeping each file's name (the mirror matches by front-matter id, not name). */
    fun importWithNames(context: Context, folderUri: String): List<Pair<String, NoteMarkdownFile.Parsed>> {
        val dir = tree(context, folderUri) ?: return emptyList()
        val files = runCatching {
            dir.listFiles().filter { f ->
                f.isFile && (f.name?.endsWith(".md", true) == true || f.name?.endsWith(".markdown", true) == true)
            }
        }.getOrDefault(emptyList())
        return files.mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            val text = runCatching {
                context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            runCatching { name to NoteMarkdownFile.parse(name, text) }.getOrNull()
        }
    }

    /** Overwrite (or create) one `.md` in the folder with [text]. */
    fun writeOne(context: Context, folderUri: String, name: String, text: String): Boolean {
        val dir = tree(context, folderUri) ?: return false
        return runCatching {
            val file = dir.findFile(name) ?: dir.createFile(MIME, name) ?: return@runCatching false
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray()) }
            true
        }.getOrDefault(false)
    }

    /** Read a config file at `<folder>/<sub>/<name>` (the self-describing `.kairo/` folder). */
    fun readConfig(context: Context, folderUri: String, sub: String, name: String): String? {
        val dir = tree(context, folderUri) ?: return null
        val subDir = dir.findFile(sub)?.takeIf { it.isDirectory } ?: return null
        val file = subDir.findFile(name)?.takeIf { it.isFile } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    /** Write a config file at `<folder>/<sub>/<name>`, creating the sub-folder if needed. */
    fun writeConfig(context: Context, folderUri: String, sub: String, name: String, text: String): Boolean {
        val dir = tree(context, folderUri) ?: return false
        return runCatching {
            val subDir = dir.findFile(sub)?.takeIf { it.isDirectory } ?: dir.createDirectory(sub) ?: return@runCatching false
            val file = subDir.findFile(name) ?: subDir.createFile("application/json", name) ?: return@runCatching false
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray()) }
            true
        }.getOrDefault(false)
    }
}
