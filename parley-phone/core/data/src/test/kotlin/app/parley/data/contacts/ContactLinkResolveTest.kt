package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.core.app.ApplicationProvider
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Links other apps hand over ("Edit contact", "View contact") resolve to the contact they mean, also when they name
 * a raw contact or a data row whose id is not the contact's id.
 */
@RunWith(RobolectricTestRunner::class)
class ContactLinkResolveTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> listOf(1L) }
    }

    @After fun tearDown() = scope.cancel()

    private fun create(given: String, number: String): Long = runBlocking {
        repo.save(null, ContactDetails(given = given, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE))), null, null, false)!!.contactId
    }

    private fun rawIdsOf(contactId: Long): List<Long> =
        provider.rows("raw_contacts", "contact_id = $contactId").map { it["_id"].toString().toLong() }

    /** Ada gets a second copy, made as a contact of its own and then joined to her: its raw id is no contact's id. */
    private fun adaWithSecondCopy(): Triple<Long, Long, Long> {
        val ada = create("Ada", "+44 20 7946 0000")
        val grace = create("Grace", "+1 202 555 0100")
        val extra = create("Ada", "+44 20 7946 0001")
        val extraRaw = rawIdsOf(extra).single()
        provider.moveRaw(extraRaw, ada)
        return Triple(ada, grace, extraRaw)
    }

    @Test fun aRawContactLinkOpensTheContactThatHoldsIt() {
        val (ada, _, extraRaw) = adaWithSecondCopy()
        assertNotEquals(ada, extraRaw)
        assertEquals(ada, repo.resolveContactId(ContentUris.withAppendedId(RawContacts.CONTENT_URI, extraRaw)))
    }

    @Test fun aRawContactIdThatIsAlsoAnotherContactsIdStillOpensItsOwnContact() {
        val ada = create("Ada", "+44 20 7946 0000")
        val grace = create("Grace", "+1 202 555 0100")
        // Grace's copy joins Ada, and Linus's contact takes the id Grace had: raw_contacts/<graceRaw> is now one of
        // Ada's copies, while contacts/<graceRaw> is Linus. The link must open Ada.
        val graceRaw = rawIdsOf(grace).single()
        val linus = create("Linus", "+358 40 123 4567")
        provider.moveRaw(graceRaw, ada)
        provider.exec("UPDATE raw_contacts SET contact_id = $graceRaw WHERE _id = ${rawIdsOf(linus).single()}")
        assertEquals(graceRaw, repo.resolveContactId(ContentUris.withAppendedId(Contacts.CONTENT_URI, graceRaw)))
        assertEquals(ada, repo.resolveContactId(ContentUris.withAppendedId(RawContacts.CONTENT_URI, graceRaw)))
    }

    @Test fun aDeletedRawContactResolvesToNothing() {
        val (_, _, extraRaw) = adaWithSecondCopy()
        provider.exec("UPDATE raw_contacts SET deleted = 1 WHERE _id = $extraRaw")
        assertNull(repo.resolveContactId(ContentUris.withAppendedId(RawContacts.CONTENT_URI, extraRaw)))
    }

    @Test fun aDataRowLinkOpensItsContact() {
        val (ada, _, extraRaw) = adaWithSecondCopy()
        val dataId = provider.rows("data", "raw_contact_id = $extraRaw").first()["_id"].toString().toLong()
        assertEquals(ada, repo.resolveContactId(ContentUris.withAppendedId(Data.CONTENT_URI, dataId)))
    }

    @Test fun contactLinksStillResolve() {
        val (ada, grace, _) = adaWithSecondCopy()
        assertEquals(grace, repo.resolveContactId(ContentUris.withAppendedId(Contacts.CONTENT_URI, grace)))
        assertEquals(ada, repo.resolveContactId(Contacts.getLookupUri(ada, "lk$ada")))
    }
}
