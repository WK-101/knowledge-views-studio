package app.parley.lists

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves the packs to Parley, read-only. Protected by the signature permission `app.parley.permission.READ_LISTS`
 * (see the manifest), so only apps signed with the same key can call it.
 *
 * - `content://app.parley.lists.packs/packs`: one row per pack ([COLUMNS]);
 * - `content://app.parley.lists.packs/packs/<id>`: the `.parleylist` file, opened read-only.
 */
class PacksProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun repo() = ListsRepo.get(context!!)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val segs = uri.pathSegments
        if (segs.firstOrNull() != "packs") return null
        val repo = repo()
        val publisher = repo.fingerprint()
        val c = MatrixCursor(COLUMNS)
        repo.state.value.packs
            .filter { segs.size < 2 || it.id == segs[1] }
            .filter { repo.packFile(it.id)?.exists() == true }
            .sortedBy { it.name.lowercase() }
            .forEach { p ->
                c.addRow(arrayOf<Any?>(p.id, p.name, p.version, p.entries, p.ranges, p.sizeBytes, p.updatedAt, p.fingerprint, p.source, p.licence, p.origin, publisher))
            }
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Lists are read-only")
        val segs = uri.pathSegments
        if (segs.size != 2 || segs[0] != "packs") throw FileNotFoundException("Unknown list")
        val f = repo().packFile(segs[1])?.takeIf { it.exists() } ?: throw FileNotFoundException("Unknown list")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = if (uri.pathSegments.size == 2) "application/octet-stream" else "vnd.android.cursor.dir/vnd.parley.list"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")

    companion object {
        /** Must match Parley's reader (app.parley.blocking.ListsUpdaterClient). */
        val COLUMNS = arrayOf("id", "name", "version", "entries", "ranges", "size", "updated", "fingerprint", "source", "licence", "origin", "publisher")
    }
}
