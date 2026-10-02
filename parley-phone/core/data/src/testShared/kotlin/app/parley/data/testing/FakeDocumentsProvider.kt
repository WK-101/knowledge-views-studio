package app.parley.data.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.robolectric.Robolectric
import java.io.File

/**
 * A folder picked with the Storage Access Framework, for Robolectric tests: one tree ([treeUri]) backed by a
 * directory, with the calls Parley's folder sync makes (list children, open, create, delete, a document's stamp).
 * It counts the files opened for reading in [reads], so a test can check that a run skipped unchanged files.
 */
class FakeDocumentsProvider : ContentProvider() {
    lateinit var dir: File
        private set

    /** Files opened for reading. */
    var reads = 0

    /** Answer listings as a cloud provider does while it still fetches the folder: EXTRA_LOADING, maybe partial. */
    var loading = false

    /** A provider that reports neither modified time nor size (some cloud and USB providers). */
    var noStamps = false

    /** Creating a document whose name is taken makes "name (1)", as real providers do. */
    var renameOnCollision = false

    override fun onCreate(): Boolean {
        dir = File(context!!.cacheDir, "fake-tree-" + System.nanoTime()).apply { mkdirs() }
        return true
    }

    val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

    private fun fileOf(docId: String): File = if (docId == ROOT) dir else File(dir, docId.removePrefix("$ROOT/"))

    private fun docUri(name: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$ROOT/$name")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val out = MatrixCursor(cols)
        fun row(f: File) = out.addRow(cols.map { c -> column(c, f) })
        if (uri.pathSegments.lastOrNull() == "children") {
            val all = dir.listFiles().orEmpty().sortedBy { it.name }
            // Still loading: only the first half is known so far.
            (if (loading) all.take(all.size / 2) else all).forEach(::row)
            if (loading) out.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
        } else {
            fileOf(DocumentsContract.getDocumentId(uri)).takeIf { it.exists() }?.let(::row)
        }
        return out
    }

    private fun column(c: String, f: File): Any? = when (c) {
        Document.COLUMN_DOCUMENT_ID -> "$ROOT/${f.name}"
        Document.COLUMN_DISPLAY_NAME -> f.name
        Document.COLUMN_LAST_MODIFIED -> if (noStamps) null else f.lastModified()
        Document.COLUMN_SIZE -> if (noStamps) null else f.length()
        Document.COLUMN_MIME_TYPE -> "text/vcard"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileOf(DocumentsContract.getDocumentId(uri))
        if (mode == "r") reads++
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_CREATE -> {
            var name = extras!!.getString(Document.COLUMN_DISPLAY_NAME)!!
            if (renameOnCollision && File(dir, name).exists()) name = "$name (1)"
            File(dir, name).createNewFile()
            Bundle().apply { putParcelable(EXTRA_URI, docUri(name)) }
        }
        METHOD_DELETE -> {
            @Suppress("DEPRECATION")
            val u = extras!!.getParcelable<Uri>(EXTRA_URI)!!
            fileOf(DocumentsContract.getDocumentId(u)).delete()
            Bundle()
        }
        else -> null
    }

    /** Writes [name] as another device's sync would, with a last-modified time clearly after the previous one. */
    fun put(name: String, text: String) {
        val f = File(dir, name)
        val before = if (f.exists()) f.lastModified() else 0L
        f.writeText(text)
        f.setLastModified(maxOf(System.currentTimeMillis(), before + 2_000))
    }

    fun names(): List<String> = dir.listFiles().orEmpty().map { it.name }.sorted()

    override fun getType(uri: Uri): String = "text/vcard"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "app.parley.test.documents"
        private const val ROOT = "root"
        private const val METHOD_CREATE = "android:createDocument"
        private const val METHOD_DELETE = "android:deleteDocument"
        private const val EXTRA_URI = "uri"

        fun install(): FakeDocumentsProvider = Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(AUTHORITY).get()
    }
}
