package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Directory
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.WorkContact
import app.parley.common.people.WorkSearch
import app.parley.data.WorkContactSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Work-profile contacts in search: read through the enterprise filter URIs, only when there is a work profile. */
@RunWith(RobolectricTestRunner::class)
class WorkContactSearchTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: WorkProvider
    private var workProfile = true
    private val search by lazy { WorkContactSearch(app) { workProfile } }
    private val base = WorkSearch.ENTERPRISE_ID_BASE

    /** A contacts provider that only knows the work profile's search URIs, and remembers what was asked. */
    class WorkProvider : ContentProvider() {
        val asked = ArrayList<Uri>()
        var refuse = false
        var names: List<Array<Any?>> = emptyList()
        var phones: List<Array<Any?>> = emptyList()

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            asked += uri
            if (refuse) throw SecurityException("cross-profile search is off")
            val out = MatrixCursor(projection ?: emptyArray())
            val path = uri.path.orEmpty()
            when {
                path.startsWith("/data/phones/filter_enterprise") -> phones.forEach { out.addRow(it) }
                path.startsWith("/contacts/filter_enterprise") -> names.forEach { out.addRow(it) }
            }
            return out
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        provider = Robolectric.buildContentProvider(WorkProvider::class.java).create(ContactsContract.AUTHORITY).get()
    }

    @Test fun findsWorkContactsByNameAndNumberThroughTheWorkDirectory() {
        provider.names = listOf(arrayOf(base + 1, "c-ana", "Ana Work", null), arrayOf(5L, "p", "Personal", null))
        provider.phones = listOf(
            arrayOf(base + 1, "c-ana", "Ana Work", null, "+1 555 0100", Phone.TYPE_WORK, null),
            arrayOf(base + 2, "c-bo", "Bo", null, "+1 555 0200", Phone.TYPE_MOBILE, null),
        )
        val found = search.searchNow("  an ")
        assertEquals(listOf(base + 1, base + 2), found.map { it.id })
        assertEquals("+1 555 0100", found[0].number)
        assertTrue(provider.asked.isNotEmpty())
        provider.asked.forEach { uri ->
            assertEquals(Directory.ENTERPRISE_DEFAULT.toString(), uri.getQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY))
            assertEquals("an", uri.lastPathSegment)
        }
    }

    @Test fun nothingIsAskedWithoutAWorkProfileOrPermission() {
        workProfile = false
        assertEquals(emptyList<WorkContact>(), search.searchNow("ana"))
        workProfile = true
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(emptyList<WorkContact>(), search.searchNow("ana"))
        assertTrue(provider.asked.isEmpty())
    }

    @Test fun aRefusedSearchReadsAsNoWorkResults() {
        provider.refuse = true
        assertEquals(emptyList<WorkContact>(), search.searchNow("ana"))
        assertEquals(emptyList<WorkContact>(), search.searchNow(" "))
    }

    @Test fun theQuickContactLinkUsesTheEnterpriseLookupKey() {
        val uri = WorkContactSearch.lookupUri(WorkContact(base + 7, "c-key", "Cy"))!!
        assertEquals(listOf("contacts", "lookup", "c-key", (base + 7).toString()), uri.pathSegments)
        assertEquals(null, WorkContactSearch.lookupUri(WorkContact(base + 7, null, "Cy")))
    }
}
