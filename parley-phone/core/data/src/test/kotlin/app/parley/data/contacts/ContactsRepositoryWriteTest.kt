package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.os.UserManager
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.WorkProfile
import app.parley.data.testing.FakeContactsProvider
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** [ContactsRepository]'s write paths against a SQLite-backed Contacts Provider: what reaches the provider, and what not. */
@RunWith(RobolectricTestRunner::class)
class ContactsRepositoryWriteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> listOf(42L) }
    }

    @After fun tearDown() = scope.cancel()

    private val ada = ContactDetails(
        given = "Ada", family = "Lovelace",
        phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE), DataItem(null, "   ", Phone.TYPE_WORK)),
        emails = listOf(DataItem(null, "ada@example.org", Email.TYPE_WORK)),
        note = "  Analytical engine  ",
    )

    private fun create(details: ContactDetails = ada): Long = runBlocking { repo.save(null, details, null, null, false)!!.contactId }

    private fun dataRows() = provider.rows("data")

    @Test fun aNewContactGetsOneRawContactAndOnlyItsNonBlankRows() {
        val id = create()
        assertEquals(1, provider.rows("raw_contacts").size)
        val byMime = dataRows().groupBy { it["mimetype"] }
        assertEquals(setOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE), byMime.keys)
        assertEquals("the blank phone is not written", 1, byMime.getValue(Phone.CONTENT_ITEM_TYPE).size)
        val name = byMime.getValue(StructuredName.CONTENT_ITEM_TYPE).single()
        assertEquals("Ada Lovelace", name["data1"])
        assertEquals("Ada", name["data2"])
        assertEquals("Lovelace", name["data3"])
        assertEquals("Analytical engine", byMime.getValue(Note.CONTENT_ITEM_TYPE).single()["data1"])

        val read = runBlocking { repo.details(id) }!!
        assertEquals("Ada Lovelace", read.displayName)
        assertEquals(listOf("+44 20 7946 0000"), read.phones.map { it.value })
        assertEquals(listOf("ada@example.org"), read.emails.map { it.value })
    }

    @Test fun anEditWritesOnlyTheRowsThatChanged() = runBlocking {
        val id = create()
        val before = repo.editable(id)!!
        provider.writes.clear()
        val phone = before.phones.single()
        val edited = before.copy(phones = listOf(phone.copy(value = "+44 20 7946 0001")), note = before.note + "  ")
        repo.save(before, edited, null, null, false)

        val dataWrites = provider.writes.filter { it.path.startsWith("data") }
        assertEquals(listOf("update"), dataWrites.map { it.kind })
        assertEquals("data/${phone.id}", dataWrites.single().path)
        assertEquals("+44 20 7946 0001", dataWrites.single().values[Phone.NUMBER])
        assertEquals(listOf(42L), repo.lastJournalIds)
    }

    @Test fun removedRowsAreDeletedAndNewOnesJoinTheSameRawContact() = runBlocking {
        val id = create()
        val before = repo.editable(id)!!
        val raw = before.editRawId!!
        val edited = before.copy(emails = emptyList(), phones = before.phones + DataItem(null, "+1 555 0100", Phone.TYPE_HOME), note = "")
        provider.writes.clear()
        repo.save(before, edited, null, null, false)

        assertEquals(2, provider.writes.count { it.kind == "delete" && it.path.startsWith("data/") })
        val insert = provider.writes.single { it.kind == "insert" }
        assertEquals(raw.toString(), insert.values["raw_contact_id"].toString())
        assertEquals(1, provider.rows("raw_contacts").size)
        assertTrue(dataRows().none { it["mimetype"] == Email.CONTENT_ITEM_TYPE || it["mimetype"] == Note.CONTENT_ITEM_TYPE })
        assertEquals(setOf("+44 20 7946 0000", "+1 555 0100"), repo.details(id)!!.phones.map { it.value }.toSet())
    }

    @Test fun readOnlyRowsAreNeitherChangedNorDeleted() = runBlocking {
        val id = create()
        val phoneId = dataRows().first { it["mimetype"] == Phone.CONTENT_ITEM_TYPE }["_id"].toString().toLong()
        provider.exec("UPDATE data SET is_read_only = 1 WHERE _id = $phoneId")
        val before = repo.editable(id)!!
        assertEquals(setOf(phoneId), before.readOnlyDataIds)
        provider.writes.clear()
        repo.save(before, before.copy(phones = before.phones.map { it.copy(value = "+1 000") }), null, null, false)
        repo.save(before, before.copy(phones = emptyList()), null, null, false)
        assertTrue(provider.writes.none { it.path == "data/$phoneId" })
        assertEquals("+44 20 7946 0000", dataRows().first { it["_id"] == phoneId.toString() }["data1"])
    }

    @Test fun clearingEveryFieldRemovesTheEmptyRawContact() = runBlocking {
        val id = create(ContactDetails(given = "Temp", phones = listOf(DataItem(null, "+1 555 0199", Phone.TYPE_MOBILE))))
        val before = repo.editable(id)!!
        val result = repo.save(before, before.copy(given = "", phones = emptyList()), null, null, false)
        assertNull("no other copy is left, so there is no contact to open", result)
        assertTrue(provider.rows("raw_contacts").isEmpty())
        assertTrue(dataRows().isEmpty())
    }

    @Test fun aDeleteNeverGoesAheadWithoutItsUndoCopy() = runBlocking {
        val id = create()
        repo.beforeChange = { _, _ -> throw IOException("disk full") }
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.delete(listOf(id)) } }
        assertEquals(1, provider.rows("raw_contacts").size)

        repo.beforeChange = { _, _ -> listOf(7L) }
        repo.delete(listOf(id))
        assertTrue(provider.rows("raw_contacts").isEmpty())
        assertEquals(listOf(7L), repo.lastJournalIds)
    }

    @Test fun aFailedJournalDoesNotBlockAnEdit() = runBlocking {
        val id = create()
        repo.beforeChange = { _, _ -> throw IOException("disk full") }
        val before = repo.editable(id)!!
        assertNotNull(repo.save(before, before.copy(nickname = "Countess"), null, null, false))
        assertEquals(emptyList<Long>(), repo.lastJournalIds)
    }

    @Test fun starringUpdatesTheAggregate() = runBlocking {
        val id = create()
        repo.setStarred(id, true)
        assertTrue(repo.details(id)!!.starred)
        repo.setStarred(id, false)
        assertFalse(repo.details(id)!!.starred)
    }

    @Test fun isContactUsesTheWorkProfileOnlyWhenThereIsOne() {
        create()
        resetWorkProfileCache()
        assertEquals(true, repo.isContact("020 7946 0000"))
        assertEquals(false, repo.isContact("+1 555 0123"))
        assertEquals(false, repo.isContact(""))

        provider.workNumbers += "+1 555 0123"
        assertEquals("no work profile: the enterprise lookup isn't asked", false, repo.isContact("+1 555 0123"))
        val um = app.getSystemService(UserManager::class.java)
        shadowOf(um).addProfile(0, 10, "Work", 0x20 /* UserInfo.FLAG_MANAGED_PROFILE */)
        resetWorkProfileCache()
        assertEquals(true, repo.isContact("+1 555 0123"))

        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertNull("unknown without the permission, so callers fail open", repo.isContact("+1 555 0123"))
    }

    @Test fun favouritesStayStarredWithAWorkProfile() = runBlocking {
        val id = create()
        repo.setStarred(id, true)
        assertEquals(true, repo.lookup("+44 20 7946 0000")?.starred)
        // M3: with a work profile the enterprise lookup answers (it isn't asked for the star); the star is still read.
        shadowOf(app.getSystemService(UserManager::class.java)).addProfile(0, 10, "Work", 0x20 /* UserInfo.FLAG_MANAGED_PROFILE */)
        resetWorkProfileCache()
        val found = repo.lookup("+44 20 7946 0000")!!
        assertEquals(id, found.contactId)
        assertTrue("a personal favourite found by the enterprise lookup", found.starred)
        repo.setStarred(id, false)
        assertFalse(repo.lookup("+44 20 7946 0000")!!.starred)
        // A work contact has no personal row to read: never starred.
        provider.workNumbers += "+1 555 0123"
        assertFalse(repo.lookup("+1 555 0123")!!.starred)
        resetWorkProfileCache()
    }

    /** Adds a work row to [contactId]'s raw contact straight in the provider, as Google Contacts or Outlook would. */
    private fun addWorkRow(contactId: Long, vararg cols: Pair<String, String>) {
        val raw = provider.rows("raw_contacts").first { it["contact_id"] == contactId.toString() }["_id"]
        val names = cols.joinToString("") { ", " + it.first }
        val values = cols.joinToString("") { ", '" + it.second + "'" }
        provider.exec("INSERT INTO data (raw_contact_id, mimetype$names) VALUES ($raw, '${Organization.CONTENT_ITEM_TYPE}'$values)")
    }

    private fun workRows() = dataRows().filter { it["mimetype"] == Organization.CONTENT_ITEM_TYPE }

    @Test fun aDepartmentOnlyWorkRowSurvivesASave() = runBlocking {
        val id = create()
        addWorkRow(id, Organization.DEPARTMENT to "Research", Organization.OFFICE_LOCATION to "Room 4")
        val before = repo.editable(id)!!
        assertEquals("Research", before.department)
        assertEquals("Room 4", before.officeLocation)
        repo.save(before, before.copy(nickname = "Countess"), null, null, false)
        val row = workRows().single()
        assertEquals("Research", row["data5"])
        assertEquals("Room 4", row["data9"])
    }

    @Test fun editingTheDepartmentWritesOnlyTheColumnsParleyEdits() = runBlocking {
        val id = create()
        addWorkRow(id, Organization.COMPANY to "Acme", Organization.JOB_DESCRIPTION to "Builds rockets")
        val before = repo.editable(id)!!
        provider.writes.clear()
        repo.save(before, before.copy(department = "Research"), null, null, false)
        val write = provider.writes.single { it.path == "data/${before.orgId}" }
        assertEquals(setOf(Organization.COMPANY, Organization.TITLE, Organization.DEPARTMENT), write.values.keys)
        val row = workRows().single()
        assertEquals(listOf("Acme", "Research", "Builds rockets"), listOf(row["data1"], row["data5"], row["data6"]))
        assertEquals("Research", repo.details(id)!!.department)
    }

    @Test fun aNewContactWithOnlyAnOfficeOrJobDescriptionKeepsThem() = runBlocking {
        // A private contact made visible may carry only these (a vCard ROLE saved privately).
        val id = create(ada.copy(officeLocation = "Room 4", jobDescription = "Builds rockets"))
        val row = workRows().single()
        assertEquals(listOf(null, null, "Builds rockets", "Room 4"), listOf(row["data1"], row["data4"], row["data6"], row["data9"]))
        assertEquals("Builds rockets", repo.details(id)!!.jobDescription)
    }

    @Test fun clearingCompanyAndTitleKeepsTheDepartment() = runBlocking {
        val id = create(ada.copy(company = "Acme", title = "Engineer", department = "Research"))
        val before = repo.editable(id)!!
        repo.save(before, before.copy(company = "", title = ""), null, null, false)
        val row = workRows().single()
        assertEquals(listOf(null, null, "Research"), listOf(row["data1"], row["data4"], row["data5"]))
    }

    @Test fun clearingEverythingParleyEditsKeepsARowWithAnOffice() = runBlocking {
        val id = create()
        addWorkRow(id, Organization.COMPANY to "Acme", Organization.OFFICE_LOCATION to "Room 4")
        val before = repo.editable(id)!!
        repo.save(before, before.copy(company = ""), null, null, false)
        assertEquals("Room 4", workRows().single()["data9"])
    }

    @Test fun clearingAWorkRowWithNothingElseRemovesIt() = runBlocking {
        val id = create(ada.copy(company = "Acme"))
        val before = repo.editable(id)!!
        repo.save(before, before.copy(company = ""), null, null, false)
        assertTrue(workRows().isEmpty())
    }

    private fun resetWorkProfileCache() {
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
    }
}
