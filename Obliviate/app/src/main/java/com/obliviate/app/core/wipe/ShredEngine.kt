package com.obliviate.app.core.wipe

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.ensureActive
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * Overwrites and then deletes user-selected files chosen through the Storage
 * Access Framework (no broad storage permission required). Each file's bytes are
 * overwritten in place for the chosen number of passes, fsync'd, and the document
 * is deleted via [DocumentsContract]. If the provider does not allow deletion we
 * fall back to truncating the file to zero length and report it.
 */
object ShredEngine {

    private val random = SecureRandom()
    private const val BUFFER_BYTES = 1 * 1024 * 1024

    data class ShredItem(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
    )

    data class ShredResult(
        val name: String,
        val sizeBytes: Long,
        val overwritten: Boolean,
        val deleted: Boolean,
        val message: String,
    )

    /** Resolves display name + size for a SAF document uri. */
    fun describe(context: Context, uri: Uri): ShredItem {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        return ShredItem(uri, name, size)
    }

    suspend fun shred(
        context: Context,
        items: List<ShredItem>,
        method: WipeMethod,
        onProgress: (index: Int, total: Int, name: String) -> Unit,
    ): List<ShredResult> {
        val resolver = context.contentResolver
        val results = ArrayList<ShredResult>(items.size)

        items.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            onProgress(index, items.size, item.name)

            val size = if (item.sizeBytes >= 0) item.sizeBytes else querySize(context, item.uri)
            var overwritten = false
            var message = ""

            if (size > 0) {
                try {
                    val passes = method.passes
                    for (pass in 1..passes) {
                        val zero = method == WipeMethod.ZERO ||
                            (method == WipeMethod.DOD && pass == 2)
                        overwriteInPlace(context, item.uri, size, zero)
                    }
                    overwritten = true
                } catch (e: Exception) {
                    message = "Overwrite failed: ${e.message}"
                }
            } else {
                message = "Size unknown — deleted without overwrite"
            }

            // Try to delete the document itself.
            val deleted = try {
                DocumentsContract.deleteDocument(resolver, item.uri)
            } catch (e: Exception) {
                false
            }

            if (!deleted) {
                // Fall back: truncate to zero length so no content remains.
                val truncated = runCatching {
                    resolver.openOutputStream(item.uri, "wt")?.use { }
                    true
                }.getOrDefault(false)
                message = if (truncated) {
                    "Overwritten & emptied (system did not allow deletion — remove the empty file manually)"
                } else {
                    (message + " · could not delete").trim(' ', '·')
                }
            } else if (message.isEmpty()) {
                message = "Overwritten and deleted"
            }

            results += ShredResult(item.name, size.coerceAtLeast(0), overwritten, deleted, message)
        }
        return results
    }

    private fun querySize(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L

    private suspend fun overwriteInPlace(context: Context, uri: Uri, size: Long, zero: Boolean) {
        val buffer = ByteArray(BUFFER_BYTES)
        if (zero) buffer.fill(0) else random.nextBytes(buffer)
        // "rw" keeps the length; we overwrite [size] bytes from the start.
        // AutoCloseOutputStream owns the pfd, so closing the stream closes the
        // underlying file descriptor exactly once (no double-close).
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Cannot open file for writing")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { fos ->
            var written = 0L
            while (written < size) {
                coroutineContext.ensureActive()
                val n = minOf(buffer.size.toLong(), size - written).toInt()
                if (!zero) random.nextBytes(buffer)
                fos.write(buffer, 0, n)
                written += n
            }
            fos.flush()
            pfd.fileDescriptor.sync()
        }
    }
}
