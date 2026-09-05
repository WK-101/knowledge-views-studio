package com.cairn.reader.data.blob

import android.content.Context
import com.cairn.reader.data.db.CairnDatabase
import com.cairn.reader.data.db.ItemDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes a full breakdown of on-disk usage and reclaims space. This is where the "0 articles
 * readable offline but N MB used" puzzle is answered: besides the counted offline copies, disk is
 * held by cached images, imported PDFs, the database (which doesn't shrink on its own until
 * VACUUMed), the image cache, and — the usual culprit — orphaned blobs left behind by items that
 * were removed. Optimize deletes the orphans, compacts the database, and clears the image cache.
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val itemDao: ItemDao,
    private val database: CairnDatabase,
) {
    data class Breakdown(
        val articleBytes: Long, val articleCount: Int,
        val imageBytes: Long, val imageCount: Int,
        val pdfBytes: Long, val pdfCount: Int,
        val databaseBytes: Long,
        val imageCacheBytes: Long,
        val orphanBytes: Long, val orphanCount: Int,
    ) {
        val total: Long get() = articleBytes + imageBytes + pdfBytes + databaseBytes + imageCacheBytes
        /** Space a one-tap Optimize could reclaim: orphaned blobs + the image cache. */
        val reclaimable: Long get() = orphanBytes + imageCacheBytes
    }

    data class OptimizeResult(val filesDeleted: Int, val bytesFreed: Long)

    private val articlesDir get() = File(context.filesDir, "articles")
    private val mediaDir get() = File(context.filesDir, "media")
    private val pdfsDir get() = File(context.filesDir, "pdfs")
    private val imageCacheDir get() = File(context.cacheDir, "image_cache")

    private fun File.sizeAndCount(): Pair<Long, Int> {
        if (!exists()) return 0L to 0
        var bytes = 0L; var count = 0
        walkTopDown().filter { it.isFile }.forEach { bytes += it.length(); count++ }
        return bytes to count
    }

    /** Item id encoded in a blob filename ("<id>.html.gz", "<id>.pdf", "<id>_<n>.<ext>"). */
    private fun itemIdOf(dir: File, f: File): String = when (dir) {
        mediaDir -> f.name.substringBeforeLast('_')            // images: <id>_<index>.<ext>
        pdfsDir -> f.name.removeSuffix(".pdf")
        else -> f.name.removeSuffix(".html.gz")                 // articles
    }

    suspend fun breakdown(): Breakdown = withContext(Dispatchers.IO) {
        val (aBytes, aCount) = articlesDir.sizeAndCount()
        val (iBytes, iCount) = mediaDir.sizeAndCount()
        val (pBytes, pCount) = pdfsDir.sizeAndCount()
        val (cacheBytes, _) = imageCacheDir.sizeAndCount()
        val dbBytes = listOf("cairn.db", "cairn.db-wal", "cairn.db-shm")
            .sumOf { runCatching { context.getDatabasePath(it).length() }.getOrDefault(0L) }

        val valid = itemDao.allItems().mapTo(HashSet()) { it.id }
        var orphanBytes = 0L; var orphanCount = 0
        listOf(articlesDir, mediaDir, pdfsDir).forEach { d ->
            if (!d.exists()) return@forEach
            d.listFiles()?.filter { it.isFile }?.forEach { f ->
                if (itemIdOf(d, f) !in valid) { orphanBytes += f.length(); orphanCount++ }
            }
        }
        Breakdown(aBytes, aCount, iBytes, iCount, pBytes, pCount, dbBytes, cacheBytes, orphanBytes, orphanCount)
    }

    /** Delete orphaned blobs, compact the database (VACUUM), and clear the image cache. */
    suspend fun optimize(): OptimizeResult = withContext(Dispatchers.IO) {
        var files = 0; var freed = 0L
        val valid = itemDao.allItems().mapTo(HashSet()) { it.id }
        listOf(articlesDir, mediaDir, pdfsDir).forEach { d ->
            if (!d.exists()) return@forEach
            d.listFiles()?.filter { it.isFile }?.forEach { f ->
                if (itemIdOf(d, f) !in valid) {
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) { files++; freed += len }
                }
            }
        }
        // Clear the image cache (Coil recreates it lazily).
        if (imageCacheDir.exists()) {
            imageCacheDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) { files++; freed += len }
            }
        }
        // Compact the database so freed pages are returned to the filesystem.
        runCatching { database.openHelper.writableDatabase.execSQL("VACUUM") }
        OptimizeResult(files, freed)
    }
}
