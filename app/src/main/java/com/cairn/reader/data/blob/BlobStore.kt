package com.cairn.reader.data.blob

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk store for article bodies. HTML is gzipped to keep the database small and
 * pageable (the Feeder/Readeck approach). One file per item; deleting an item deletes
 * its blob.
 */
@Singleton
class BlobStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File by lazy { File(context.filesDir, "articles").apply { mkdirs() } }

    fun writeArticle(itemId: String, html: String): String {
        val file = File(dir, "$itemId.html.gz")
        GZIPOutputStream(file.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { it.write(html) }
        return file.absolutePath
    }

    fun readArticle(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    fun deleteArticle(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    // -- Permanent offline copy: article images cached beside the body ---------

    private val mediaDir: File by lazy { File(context.filesDir, "media").apply { mkdirs() } }

    /**
     * Store one image belonging to [itemId] and return a `file://` URI the reader (and
     * list thumbnails) can load with Coil while fully offline. Files are named by item so
     * the whole set can be dropped when the offline copy is discarded.
     */
    fun writeImage(itemId: String, index: Int, bytes: ByteArray, extension: String): String {
        val file = File(mediaDir, "${itemId}_$index.$extension")
        file.outputStream().buffered().use { it.write(bytes) }
        return android.net.Uri.fromFile(file).toString()
    }

    /** Remove an item's cached body and every image saved for its offline copy. */
    fun deleteAllFor(itemId: String, blobPath: String?) {
        deleteArticle(blobPath)
        runCatching { mediaDir.listFiles { f -> f.name.startsWith("${itemId}_") }?.forEach { it.delete() } }
    }

    // -- Imported PDFs: stored uncompressed so PdfRenderer can page them directly ----

    private val pdfDir: File by lazy { File(context.filesDir, "pdfs").apply { mkdirs() } }

    /** Store an imported PDF verbatim and return its on-disk path (kept in the item's blobPath). */
    fun writePdf(itemId: String, bytes: ByteArray): String {
        val file = File(pdfDir, "$itemId.pdf")
        file.outputStream().buffered().use { it.write(bytes) }
        return file.absolutePath
    }

    /** Total bytes on disk for cached article bodies, offline images, and imported PDFs. */
    fun storageBytes(): Long = runCatching {
        listOf(dir, mediaDir, pdfDir).sumOf { d ->
            d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }.getOrDefault(0L)
}
