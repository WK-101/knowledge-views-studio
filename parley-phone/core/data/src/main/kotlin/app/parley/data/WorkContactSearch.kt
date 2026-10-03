package app.parley.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Directory
import app.parley.common.people.WorkContact
import app.parley.common.people.WorkSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Searches the work profile's contacts by name and number, through the same enterprise provider URIs that caller ID
 * uses ([ContactsRepository.lookup]). Only asked when this user has a work profile; the work profile's admin decides
 * whether its contacts can be searched from here, and when it says no the provider answers with nothing (or refuses),
 * which reads as "no work results". Results live in memory only.
 */
class WorkContactSearch(
    private val context: Context,
    private val hasWorkProfile: () -> Boolean = { WorkProfile.exists(context) },
) {
    private val cr: ContentResolver get() = context.contentResolver

    /** Whether a search can return anything at all: a work profile and the contacts permission. */
    fun available(): Boolean = Permissions.has(context, Manifest.permission.READ_CONTACTS) && hasWorkProfile()

    /** Work contacts matching [query] by name or number, at most [WorkSearch.LIMIT]; empty when there are none. */
    suspend fun search(query: String): List<WorkContact> = withContext(Dispatchers.IO) { searchNow(query) }

    internal fun searchNow(query: String): List<WorkContact> {
        val q = WorkSearch.queryOf(query) ?: return emptyList()
        if (!available()) return emptyList()
        return WorkSearch.merge(byName(q), byNumber(q))
    }

    private fun byName(q: String): List<WorkContact> =
        read(filterUri(Contacts.ENTERPRISE_CONTENT_FILTER_URI, q), NAME_PROJECTION) { c ->
            WorkContact(id = c.getLong(0), lookupKey = c.getString(1), name = c.getString(2).orEmpty(), photoUri = c.getString(3))
        }

    private fun byNumber(q: String): List<WorkContact> =
        read(filterUri(Phone.ENTERPRISE_CONTENT_FILTER_URI, q), PHONE_PROJECTION) { c ->
            val number = c.getString(4)?.takeIf { it.isNotBlank() }
            WorkContact(
                id = c.getLong(0), lookupKey = c.getString(1), name = c.getString(2).orEmpty(), photoUri = c.getString(3),
                number = number,
                numberLabel = number?.let { Phone.getTypeLabel(context.resources, c.getInt(5), c.getString(6))?.toString() },
            )
        }

    private fun <T> read(uri: Uri, projection: Array<String>, row: (Cursor) -> T): List<T> = try {
        cr.query(uri, projection, null, null, null)?.use { c ->
            val out = ArrayList<T>()
            while (out.size < WorkSearch.LIMIT * 2 && c.moveToNext()) out += row(c)
            out
        }.orEmpty()
    } catch (_: SecurityException) {
        // The work profile's policy may refuse the search outright: no work results.
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    } catch (_: IllegalStateException) {
        emptyList()
    }

    companion object {
        private val NAME_PROJECTION = arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI)
        private val PHONE_PROJECTION = arrayOf(
            Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.PHOTO_THUMBNAIL_URI, Phone.NUMBER, Phone.TYPE, Phone.LABEL,
        )

        /** The enterprise filter URIs need a directory: the work profile's default one. */
        internal fun filterUri(base: Uri, q: String): Uri = base.buildUpon()
            .appendPath(q)
            .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY, Directory.ENTERPRISE_DEFAULT.toString())
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, (WorkSearch.LIMIT * 2).toString())
            .build()

        /** The work contact's lookup URI, for the system's quick contact card (it opens in the work profile). */
        fun lookupUri(contact: WorkContact): Uri? =
            contact.lookupKey?.takeIf { it.isNotBlank() }?.let { Contacts.getLookupUri(contact.id, it) }
    }
}
