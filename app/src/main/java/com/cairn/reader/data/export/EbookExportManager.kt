package com.cairn.reader.data.export

import android.content.Context
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.domain.export.EpubExporter
import com.cairn.reader.domain.export.HtmlSnapshotExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces shareable e-book and web-page files from saved articles — a single-article EPUB, the whole
 * library as one EPUB (ideal for Send to Kindle / Kobo), and a self-contained full-page HTML snapshot
 * for archival. Files are written into a FileProvider-visible cache dir and handed back for sharing.
 * Fully on-device.
 */
@Singleton
class EbookExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemDao: ItemDao,
    private val blobStore: BlobStore,
) {
    private val exportsDir: File by lazy { File(context.cacheDir, "exports").apply { mkdirs() } }

    /** EPUB for a single article, or null if the item is gone. */
    suspend fun epubForItem(itemId: String): File? {
        val e = itemDao.getItem(itemId) ?: return null
        if (e.type == "PDF") return null
        val chapter = chapterFor(e)
        val file = File(exportsDir, safeName(e.title) + ".epub")
        file.outputStream().buffered().use { EpubExporter.write(it, e.title.ifBlank { "Article" }, listOf(chapter), e.author) }
        return file
    }

    /** One EPUB containing the whole curated library, newest first. Null if the library is empty. */
    suspend fun epubForLibrary(): File? {
        val items = itemDao.libraryItemsForExport().filter { it.type != "PDF" }
        if (items.isEmpty()) return null
        val chapters = items.map { chapterFor(it) }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val file = File(exportsDir, "Cairn Library $stamp.epub")
        file.outputStream().buffered().use { EpubExporter.write(it, "Cairn Library", chapters) }
        return file
    }

    /** A self-contained HTML snapshot of a single article, or null if the item is gone. */
    suspend fun htmlSnapshotForItem(itemId: String): File? {
        val e = itemDao.getItem(itemId) ?: return null
        if (e.type == "PDF") return null
        val html = blobStore.readArticle(e.blobPath)
        val doc = HtmlSnapshotExporter.snapshot(
            HtmlSnapshotExporter.Meta(
                title = e.title, url = e.url, author = e.author, siteName = e.siteName,
                publishedAt = e.publishedAt, savedAt = e.savedAt,
            ),
            html,
        )
        val file = File(exportsDir, safeName(e.title) + ".html")
        file.writeText(doc, Charsets.UTF_8)
        return file
    }

    private fun chapterFor(e: ItemEntity): EpubExporter.Chapter =
        EpubExporter.Chapter(
            title = e.title,
            author = e.author,
            html = if (e.type == "PDF") null else blobStore.readArticle(e.blobPath),
            url = e.url,
        )

    private fun safeName(title: String): String =
        title.trim().replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Article" }
}
