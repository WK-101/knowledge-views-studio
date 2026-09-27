package app.parley.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.util.Log
import app.parley.common.storage.PersistentStore
import app.parley.common.storage.PersistentStores
import app.parley.common.storage.StoreKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore

/**
 * "Delete all Parley data": removes every store in [PersistentStores] (databases, DataStores, preferences, files and
 * Keystore keys) and the cache. Android's contacts and call log are left alone unless asked, and then only the call
 * log and the contacts stored on the phone itself (contacts in synced accounts would be deleted on their server too).
 *
 * The in-memory state of this process still holds the old data, so the caller must restart the app right after.
 */
class DataWipe(private val context: Context, private val c: DataContainer) {

    data class Options(
        /** Also delete every call in Android's call log. */
        val callLog: Boolean = false,
        /** Also delete the contacts stored only on this phone (not those in Google or other synced accounts). */
        val phoneContacts: Boolean = false,
    )

    data class Result(val failed: List<String>)

    suspend fun wipe(o: Options): Result = withContext(Dispatchers.IO + NonCancellable) {
        val failed = ArrayList<String>()
        fun step(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Wipe step $name failed", e)
                failed += name
            }
        }
        if (o.callLog) step("call log") { context.contentResolver.delete(android.provider.CallLog.Calls.CONTENT_URI, null, null) }
        if (o.phoneContacts) step("phone contacts") { deletePhoneContacts() }
        // Databases: closed first, so their files (and journals) can go.
        step("databases") {
            runCatching { c.db.close() }
            runCatching { c.history.closeForWipe() }
            PersistentStores.of(StoreKind.ROOM_TABLE).mapNotNull { it.location }.distinct().forEach { name ->
                if (!context.deleteDatabase(name) && context.getDatabasePath(name).exists()) failed += name
            }
        }
        for (s in PersistentStores.all) {
            when (s.kind) {
                StoreKind.PREFS -> step(s.name) { context.deleteSharedPreferences(s.name) }
                StoreKind.DATASTORE -> step(s.name) { File(context.filesDir, "datastore/${s.name}.preferences_pb").delete() }
                StoreKind.FILES -> step(s.name) { fileOf(s)?.deleteRecursively() }
                StoreKind.KEYSTORE -> step(s.name) { deleteKeys() }
                StoreKind.ROOM_TABLE -> Unit
            }
        }
        step("cache") { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        Result(failed)
    }

    private fun fileOf(s: PersistentStore): File? = when (s.location) {
        PersistentStore.FILES -> File(context.filesDir, s.name)
        PersistentStore.NO_BACKUP_FILES -> File(context.noBackupFilesDir, s.name)
        PersistentStore.DEVICE_PROTECTED_FILES -> File(context.createDeviceProtectedStorageContext().filesDir, s.name)
        else -> null
    }

    /** Parley's Keystore keys (the Keystore only lists this app's own). */
    private fun deleteKeys() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.aliases().toList().forEach { runCatching { ks.deleteEntry(it) } }
    }

    /** Raw contacts in phone-only accounts (no account, Android 15's local account, OEM phone accounts). */
    private fun deletePhoneContacts() {
        val local = DeviceAccounts.localAccount(context)
        val ids = ArrayList<Long>()
        context.contentResolver.safeQuery(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED}=0")?.use { cur ->
            while (cur.moveToNext()) {
                if (DeviceAccounts.isLocal(AccountRef(cur.getString(1), cur.getString(2)), local)) ids += cur.getLong(0)
            }
        }
        ids.chunked(200).forEach { chunk ->
            val ops = chunk.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, it)).build() }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
        }
    }

    private companion object {
        const val TAG = "DataWipe"
    }
}
