package com.obliviate.app.core.clean

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.obliviate.app.core.deleteContents
import com.obliviate.app.core.dirSize
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Storage insight + junk removal.
 *
 * - Cache clearing works with no permission (this app's own caches).
 * - The full-storage junk scan requires "All files access" and is gated behind an
 *   explicit user opt-in; it only targets clearly-disposable files and empty
 *   folders so it can never remove real user documents.
 */
object JunkCleaner {

    data class StorageStat(val totalBytes: Long, val freeBytes: Long) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
        val usedFraction: Float get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes
    }

    data class JunkItem(val path: String, val sizeBytes: Long, val kind: String)

    // ---- Own-app cache (no permission needed) --------------------------------

    fun cacheSize(context: Context): Long {
        var size = dirSize(context.cacheDir)
        context.externalCacheDir?.let { size += dirSize(it) }
        return size
    }

    /** Clears this app's caches; returns bytes freed (approx). */
    fun clearCache(context: Context): Long {
        val before = cacheSize(context)
        deleteContents(context.cacheDir)
        context.externalCacheDir?.let { deleteContents(it) }
        return before
    }

    // ---- Storage breakdown ---------------------------------------------------

    fun internalStat(): StorageStat = statOf(Environment.getDataDirectory())

    fun sharedStat(): StorageStat = statOf(Environment.getExternalStorageDirectory())

    private fun statOf(path: File): StorageStat = try {
        val s = StatFs(path.absolutePath)
        StorageStat(s.totalBytes, s.availableBytes)
    } catch (e: Exception) {
        StorageStat(0, 0)
    }

    // ---- Full-storage junk scan (needs All files access) ---------------------

    private val random = SecureRandom()

    private val JUNK_EXTENSIONS = setOf(
        "tmp", "temp", "log", "crdownload", "part", "partial", "bak", "old"
    )
    private val JUNK_NAMES = setOf(
        "thumbs.db", ".ds_store", ".thumbnails"
    )
    private const val MAX_JUNK_ITEMS = 5000

    /**
     * Conservatively scans the shared storage volume for disposable files and
     * empty directories. Returns a bounded list of candidates for user review.
     */
    suspend fun scanJunk(onCount: (Int) -> Unit): List<JunkItem> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val found = ArrayList<JunkItem>()
        var counter = 0

        suspend fun walk(dir: File) {
            coroutineContext.ensureActive()
            val children = dir.listFiles() ?: return
            for (child in children) {
                coroutineContext.ensureActive()
                if (found.size >= MAX_JUNK_ITEMS) return
                if (child.isDirectory) {
                    // Thumbnail caches hold recoverable copies of your photos.
                    if (child.name.equals(".thumbnails", ignoreCase = true)) {
                        child.listFiles()?.forEach { tf ->
                            if (found.size < MAX_JUNK_ITEMS && tf.isFile) {
                                found += JunkItem(tf.absolutePath, tf.length(), "Thumbnail cache")
                            }
                        }
                    }
                    walk(child)
                    // Empty directory (after recursion) is a junk candidate.
                    if (child.listFiles()?.isEmpty() == true) {
                        found += JunkItem(child.absolutePath, 0, "Empty folder")
                    }
                } else {
                    val name = child.name.lowercase(Locale.US)
                    val ext = child.extension.lowercase(Locale.US)
                    if (ext in JUNK_EXTENSIONS || name in JUNK_NAMES) {
                        found += JunkItem(child.absolutePath, child.length(), "Temp/junk file")
                    }
                }
                counter++
                if (counter % 200 == 0) onCount(found.size)
            }
        }

        walk(root)
        onCount(found.size)
        return found
    }

    /** Overwrites (best-effort) and deletes the given junk items; returns bytes freed. */
    fun deleteJunk(items: List<JunkItem>): Long {
        var freed = 0L
        for (item in items) {
            val f = File(item.path)
            val size = if (f.isFile) f.length() else 0L
            if (overwriteAndDelete(f)) freed += size
        }
        return freed
    }

    /** Overwrites a file's bytes with random data before deleting it. */
    private fun overwriteAndDelete(f: File): Boolean {
        try {
            val len = if (f.isFile) f.length() else 0L
            if (len > 0) {
                RandomAccessFile(f, "rw").use { raf ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (written < len) {
                        random.nextBytes(buf)
                        val n = minOf(buf.size.toLong(), len - written).toInt()
                        raf.write(buf, 0, n)
                        written += n
                    }
                    raf.fd.sync()
                }
            }
        } catch (e: Exception) {
            // If overwrite fails (permission/locked), still attempt to delete below.
        }
        return f.delete()
    }
}
