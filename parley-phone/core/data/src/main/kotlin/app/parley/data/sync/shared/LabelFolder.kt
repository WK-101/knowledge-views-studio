package app.parley.data.sync.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.parley.common.security.Bounded
import app.parley.common.sync.FolderSyncRules
import app.parley.common.sync.shared.SharedLabelFiles
import kotlinx.coroutines.delay

/** A shared label's folder as its sync uses it: names and stamps, then whole small files. */
interface LabelFolder {
    /** The provider is still loading the folder (a cloud folder): a partial listing must never look like deletions. */
    class Incomplete : Exception()

    /**
     * Every file by name with its stamp (modified time and size; null when the provider doesn't say), or null when the
     * folder can't be listed. Throws [Incomplete] while the provider is still loading it.
     */
    suspend fun list(): Map<String, String?>?

    /** A file's bytes, or null when it can't be read or is larger than a label file can be. */
    fun read(name: String): ByteArray?

    /** Writes [name] whole (creating it), and returns its new stamp ("" when the provider gives none); null on failure. */
    fun write(name: String, bytes: ByteArray): String?

    fun delete(name: String): Boolean
}

/**
 * A folder picked with the Storage Access Framework, like the folder sync's: a listing the provider marks as loading is
 * asked again a few times, then given up on ([LabelFolder.Incomplete]), so a run changes nothing.
 */
class SafLabelFolder(private val context: Context, private val tree: Uri) : LabelFolder {
    private val cr get() = context.contentResolver
    private val uris = HashMap<String, Uri>()

    internal var retryMs = 1_500L

    override suspend fun list(): Map<String, String?>? {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        repeat(TRIES) { attempt ->
            var loading = false
            val out = try {
                cr.query(children, columns, null, null, null)?.use { c ->
                    val extras = c.extras
                    if (extras?.getString(DocumentsContract.EXTRA_ERROR) != null) return null
                    loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
                    val files = HashMap<String, String?>()
                    uris.clear()
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (files.size >= MAX_FILES) return null
                        files[name] = FolderSyncRules.stamp(if (c.isNull(2)) null else c.getLong(2), if (c.isNull(3)) null else c.getLong(3))
                        uris[name] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                    files
                }
            } catch (_: Exception) {
                null
            } ?: return null
            if (!loading) return out
            if (attempt < TRIES - 1) delay(retryMs)
        }
        throw LabelFolder.Incomplete()
    }

    override fun read(name: String): ByteArray? = runCatching {
        cr.openInputStream(uris[name] ?: return null)?.use { Bounded.readBytes(it, SharedLabelFiles.MAX_FILE_BYTES, "label file") }
    }.getOrNull()

    override fun write(name: String, bytes: ByteArray): String? = try {
        val uri = uris[name] ?: run {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(cr, parent, "application/octet-stream", name)
        } ?: error("Can't create $name")
        cr.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        uris[name] = uri
        stampOf(uri) ?: ""
    } catch (_: Exception) {
        null
    }

    override fun delete(name: String): Boolean {
        val uri = uris[name] ?: return false
        return runCatching { DocumentsContract.deleteDocument(cr, uri) }.getOrDefault(false).also { if (it) uris.remove(name) }
    }

    private fun stampOf(uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) FolderSyncRules.stamp(if (c.isNull(0)) null else c.getLong(0), if (c.isNull(1)) null else c.getLong(1)) else null
        }
    }.getOrNull()

    companion object {
        private const val TRIES = 3
        private const val MAX_FILES = 20_000
        private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** Keeps access to a folder the user picked across restarts. */
        fun keep(context: Context, tree: Uri) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, READ_WRITE) }
        }

        fun release(context: Context, tree: Uri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, READ_WRITE) }
        }
    }
}
