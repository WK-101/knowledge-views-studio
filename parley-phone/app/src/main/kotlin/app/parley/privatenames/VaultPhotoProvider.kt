package app.parley.privatenames

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.parley.ParleyApp
import java.io.FileNotFoundException

/**
 * I6: serves private contacts' photos, decrypted in memory, to Parley itself (lists, the contact page and the call
 * screen load photos by URI). Not exported: no other app can open these URIs, and nothing decrypted is written to
 * storage. `content://<package>.vaultphotos/<vault id>/<version>`.
 */
class VaultPhotoProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        val app = context?.applicationContext as? ParleyApp ?: throw FileNotFoundException(uri.toString())
        // Never waits for the app to finish starting (the shared non-blocking helper): no photo until then.
        val bytes = app.containerOrNull?.vault?.photoBytes(id) ?: throw FileNotFoundException(uri.toString())
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) } }
        }.apply { name = "vault-photo"; isDaemon = true }.start()
        return read
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
