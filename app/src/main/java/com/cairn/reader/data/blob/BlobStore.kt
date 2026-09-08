package com.cairn.reader.data.blob

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk store for article bodies. HTML is gzipped to keep the database small and
 * pageable (the Feeder/Readeck approach). One file per item; deleting an item deletes
 * its blob.
 *
 * Every method that touches disk (and gzips/gunzips, which is CPU-heavy for a long article)
 * is `suspend` and confined to [Dispatchers.IO], so callers — sync, the reader, exporters —
 * never do file I/O or compression on the thread that invoked them (historically the main
 * thread, via `viewModelScope`).
 */
@Singleton
class BlobStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File by lazy { File(context.filesDir, "articles").apply { mkdirs() } }

    suspend fun writeArticle(itemId: String, html: String): String = withContext(Dispatchers.IO) {
        val file = File(dir, "$itemId.html.gz")
        GZIPOutputStream(file.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { it.write(html) }
        file.absolutePath
    }

    suspend fun readArticle(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext null
            runCatching {
                GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
        }
    }

    suspend fun deleteArticle(path: String?) {
        if (path.isNullOrBlank()) return
        withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
    }

    // -- Permanent offline copy: article images cached beside the body ---------

    private val mediaDir: File by lazy { File(context.filesDir, "media").apply { mkdirs() } }

    /**
     * Store one image belonging to [itemId] and return a `file://` URI the reader (and
     * list thumbnails) can load with Coil while fully offline. Files are named by item so
     * the whole set can be dropped when the offline copy is discarded.
     */
    suspend fun writeImage(itemId: String, index: Int, bytes: ByteArray, extension: String): String =
        withContext(Dispatchers.IO) {
            val file = File(mediaDir, "${itemId}_$index.$extension")
            file.outputStream().buffered().use { it.write(bytes) }
            android.net.Uri.fromFile(file).toString()
        }

    /** Remove an item's cached body and every image saved for its offline copy. */
    suspend fun deleteAllFor(itemId: String, blobPath: String?) {
        deleteArticle(blobPath)
        withContext(Dispatchers.IO) {
            runCatching { mediaDir.listFiles { f -> f.name.startsWith("${itemId}_") }?.forEach { it.delete() } }
        }
    }

    // -- Imported PDFs: stored uncompressed so PdfRenderer can page them directly ----

    private val pdfDir: File by lazy { File(context.filesDir, "pdfs").apply { mkdirs() } }

    /** Store an imported PDF verbatim and return its on-disk path (kept in the item's blobPath). */
    suspend fun writePdf(itemId: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val file = File(pdfDir, "$itemId.pdf")
        file.outputStream().buffered().use { it.write(bytes) }
        file.absolutePath
    }

    /** Total bytes on disk for cached article bodies, offline images, and imported PDFs. */
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        runCatching {
            listOf(dir, mediaDir, pdfDir).sumOf { d ->
                d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }.getOrDefault(0L)
    }

    // -- Full-archive backup support ------------------------------------------

    /** The app's private files root — the prefix embedded in stored blobPath / file:// image URIs.
     *  A full-archive restore rewrites this prefix so offline copies resolve on the new install. */
    fun filesRoot(): File = context.filesDir

    /** The blob directories, each paired with the archive-relative name it is zipped under. */
    fun archiveDirs(): List<Pair<String, File>> = listOf("articles" to dir, "media" to mediaDir, "pdfs" to pdfDir)

    /** Resolve an archive-relative blob path ("articles/<id>.html.gz") to a real file, creating
     *  the parent directory. Used when unpacking a full archive. Returns null for an unknown prefix. */
    fun resolveArchivePath(relative: String): File? {
        val slash = relative.indexOf('/')
        if (slash <= 0) return null
        val root = when (relative.substring(0, slash)) {
            "articles" -> dir; "media" -> mediaDir; "pdfs" -> pdfDir; else -> return null
        }
        val name = relative.substring(slash + 1)
        // Guard against path traversal: keep the file directly inside its root.
        if (name.isBlank() || name.contains('/') || name.contains("..")) return null
        return File(root, name)
    }

    /** After unpacking an archive from another device, rewrite the old files-root prefix inside every
     *  cached article's HTML (image `file://` URIs) so offline images resolve here. No-op if equal. */
    suspend fun rewriteArticleBase(oldBase: String, newBase: String) {
        if (oldBase.isBlank() || oldBase == newBase) return
        val files = withContext(Dispatchers.IO) { dir.listFiles()?.toList().orEmpty() }
        files.forEach { f ->
            val html = readArticle(f.absolutePath) ?: return@forEach
            if (html.contains(oldBase)) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        GZIPOutputStream(f.outputStream().buffered()).bufferedWriter(Charsets.UTF_8)
                            .use { it.write(html.replace(oldBase, newBase)) }
                    }
                }
            }
        }
    }
}
