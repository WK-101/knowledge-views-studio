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
}
