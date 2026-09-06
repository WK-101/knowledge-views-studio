package com.cairn.reader.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.db.HighlightDao
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.db.TagDao
import com.cairn.reader.domain.export.MarkdownExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports saved articles as portable Markdown — one file for sharing, or the whole curated library
 * into a user-picked folder (an Obsidian/Logseq vault). Fully on-device: it reads the cached body,
 * tags, and highlights already stored locally and writes plain files the user owns forever.
 */
@Singleton
class MarkdownExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemDao: ItemDao,
    private val highlightDao: HighlightDao,
    private val tagDao: TagDao,
    private val blobStore: BlobStore,
) {
    data class Result(val written: Int, val failed: Int)

    /** Markdown for one article, ready to share — or null if the item is gone. */
    suspend fun documentFor(itemId: String): MarkdownExporter.Doc? {
        val e = itemDao.getItem(itemId) ?: return null
        return build(e)
    }

    /** Export the whole curated library to a SAF folder tree as one `.md` file per article. */
    suspend fun exportVault(treeUri: Uri): Result {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return Result(0, 0)
        // A dated subfolder keeps re-exports from colliding and the vault tidy.
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val folderName = "Cairn $stamp"
        val folder = tree.findFile(folderName)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(folderName) ?: tree

        val items = itemDao.libraryItemsForExport()
        var ok = 0
        var fail = 0
        val used = HashSet<String>()
        items.forEach { e ->
            val doc = runCatching { build(e) }.getOrNull()
            if (doc == null) { fail++; return@forEach }
            val name = uniqueName(doc.filename, used)
            val wrote = runCatching {
                val file = folder.createFile("text/markdown", name) ?: return@runCatching false
                context.contentResolver.openOutputStream(file.uri)?.use {
                    it.write(doc.content.toByteArray(Charsets.UTF_8))
                }
                true
            }.getOrDefault(false)
            if (wrote) ok++ else fail++
        }
        return Result(ok, fail)
    }

    private suspend fun build(e: ItemEntity): MarkdownExporter.Doc {
        val tags = runCatching { tagDao.tagsForItem(e.id).map { it.name } }.getOrDefault(emptyList())
        val html = if (e.type == "PDF") null else blobStore.readArticle(e.blobPath)
        val highlights = runCatching { highlightDao.forItemWithArticle(e.id) }.getOrDefault(emptyList())
            .map { MarkdownExporter.Highlight(it.quote, it.note, it.createdAt) }
        val meta = MarkdownExporter.Meta(
            title = e.title,
            url = e.url,
            author = e.author,
            siteName = e.siteName,
            publishedAt = e.publishedAt,
            savedAt = e.savedAt,
            tags = tags,
        )
        return MarkdownExporter.document(meta, html, highlights)
    }

    /** Disambiguate a filename that repeats within one export ("Title (2).md"). */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val cand = "$base ($i)$ext"
            if (used.add(cand)) return cand
            i++
        }
    }
}
