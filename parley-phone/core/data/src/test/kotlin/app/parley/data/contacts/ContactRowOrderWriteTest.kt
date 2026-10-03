package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.test.core.app.ApplicationProvider
import android.content.ContentValues
import app.parley.common.people.CustomFields
import app.parley.common.record.Mime
import app.parley.data.ContactDetails
import app.parley.data.ContactRowOrder
import app.parley.data.CustomFieldItem
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactDraftJson
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.PostalItem
import app.parley.data.records.ContactRecordStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The order the editor gives a contact's numbers, emails, addresses and dates (Move up / Move down) is what the provider
 * returns afterwards, and the rows written again for it keep every other column they had.
 */
@RunWith(RobolectricTestRunner::class)
class ContactRowOrderWriteTest {
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

    private val ana = ContactDetails(
        given = "Ana", family = "Lima",
        phones = listOf(
            DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE),
            DataItem(null, "+44 20 7946 0002", Phone.TYPE_WORK),
            DataItem(null, "+44 20 7946 0003", Phone.TYPE_HOME),
        ),
        emails = listOf(DataItem(null, "ana@home.example", Email.TYPE_HOME), DataItem(null, "ana@work.example", Email.TYPE_WORK)),
        addresses = listOf(
            PostalItem(street = "1 First St", type = StructuredPostal.TYPE_HOME),
            PostalItem(street = "2 Second St", type = StructuredPostal.TYPE_WORK),
        ),
        events = listOf(EventItem(date = "1990-01-27", type = Event.TYPE_BIRTHDAY), EventItem(date = "2015-06-01", type = Event.TYPE_ANNIVERSARY)),
    )

    private fun row(value: String): Map<String, Any?> = provider.rows("data").single { it["data1"] == value }

    private fun idOf(value: String): Long = row(value)["_id"].toString().toLong()

    @Test fun aNewOrderIsReadBackAndTheMovedRowsKeepTheirOtherColumns() = runBlocking {
        val id = repo.save(null, ana, null, null, false)!!.contactId
        // What other apps and Android keep beside the editor's fields: the default number, an email's display name,
        // an address's RFC 9554 parts, a date's calendar.
        provider.exec("UPDATE data SET is_primary = 1, is_super_primary = 1 WHERE _id = ${idOf("+44 20 7946 0001")}")
        provider.exec("UPDATE data SET data4 = 'Ana at home' WHERE _id = ${idOf("ana@home.example")}")
        provider.exec("UPDATE data SET data11 = 'Floor: 2' WHERE data4 = '1 First St'")
        provider.exec("UPDATE data SET data14 = 'chinese' WHERE data1 = '1990-01-27'")
        val before = repo.editable(id)!!
        assertEquals(listOf("+44 20 7946 0001", "+44 20 7946 0002", "+44 20 7946 0003"), before.phones.map { it.value })

        val p = before.phones
        val edited = before.copy(
            phones = listOf(p[2], p[0], p[1]),
            emails = before.emails.reversed(),
            addresses = before.addresses.reversed(),
            events = before.events.reversed(),
        )
        repo.save(before, edited, null, null, false)

        val after = repo.editable(id)!!
        assertEquals(listOf("+44 20 7946 0003", "+44 20 7946 0001", "+44 20 7946 0002"), after.phones.map { it.value })
        assertEquals(listOf(Phone.TYPE_HOME, Phone.TYPE_MOBILE, Phone.TYPE_WORK), after.phones.map { it.type })
        assertEquals(listOf("ana@work.example", "ana@home.example"), after.emails.map { it.value })
        assertEquals(listOf("2 Second St", "1 First St"), after.addresses.map { it.street })
        assertEquals(listOf("2015-06-01", "1990-01-27"), after.events.map { it.date })
        // The page's read (every copy) gives the same order.
        assertEquals(after.phones.map { it.value }, repo.details(id)!!.phones.map { it.value })

        // The default number stayed the default, and the other columns came along.
        val first = row("+44 20 7946 0001")
        assertEquals("1", first["is_super_primary"].toString())
        assertEquals("1", first["is_primary"].toString())
        assertTrue(after.phones.single { it.value == "+44 20 7946 0001" }.isPrimary)
        assertEquals("Ana at home", row("ana@home.example")["data4"])
        assertEquals("Floor: 2", provider.rows("data").single { it["data4"] == "1 First St" }["data11"])
        assertEquals("chinese", row("1990-01-27")["data14"])
        assertEquals(1, provider.rows("raw_contacts").size)

        // Export reads the rows the same way, so a vCard lists them in this order too.
        val exported = ContactRecordStore(app).read(id, fullPhoto = false)!!.raws.single().rows
            .filter { it.mimeType == Phone.CONTENT_ITEM_TYPE }.map { it.values["data1"] }
        assertEquals(after.phones.map { it.value }, exported)
    }

    /** A custom field whose kind changes (Google's to Parley's) is inserted again anyway: the rows after it follow it. */
    @Test fun aCustomFieldThatChangesKindKeepsTheChosenOrder() {
        val shoe = CustomFieldItem(5, "Shoe size", "38", Mime.GOOGLE_CUSTOM_FIELD)
        val hat = CustomFieldItem(7, "Hat", "M", Mime.GOOGLE_CUSTOM_FIELD)
        val original = ContactDetails(customFields = listOf(shoe, hat))
        val edited = original.copy(customFields = listOf(shoe.copy(label = ""), hat))
        assertEquals(setOf(7L), ContactRowOrder.rewrite(original, edited, emptySet(), CustomFields.GOOGLE_ACCOUNT))
        assertEquals("the same kind: nothing moves", emptySet<Long>(), ContactRowOrder.rewrite(original, original, emptySet(), CustomFields.GOOGLE_ACCOUNT))
    }

    /** Rows are written again only when every column they keep could be read first. */
    @Test fun noRowIsWrittenAgainWithoutItsColumns() {
        val ids = setOf(3L, 4L)
        assertEquals(emptySet<Long>(), ContactRowOrder.kept(ids, null))
        assertEquals(emptySet<Long>(), ContactRowOrder.kept(ids, mapOf(3L to ContentValues())))
        assertEquals(ids, ContactRowOrder.kept(ids, mapOf(3L to ContentValues(), 4L to ContentValues())))
    }

    @Test fun anUnchangedOrderWritesNothing() = runBlocking {
        val id = repo.save(null, ana, null, null, false)!!.contactId
        val before = repo.editable(id)!!
        provider.writes.clear()
        repo.save(before, before.copy(note = "Met at the conference"), null, null, false)
        assertTrue(provider.writes.none { it.kind == "delete" })
        assertEquals(1, provider.writes.count { it.kind == "insert" })
    }

    @Test fun onlyTheRowsThatMustComeLaterAreWrittenAgain() = runBlocking {
        val id = repo.save(null, ana, null, null, false)!!.contactId
        val before = repo.editable(id)!!
        val p = before.phones
        provider.writes.clear()
        // The second number moved down below the third: only it is written again, the first two stay as they are.
        repo.save(before, before.copy(phones = listOf(p[0], p[2], p[1].copy(value = "+44 20 7946 0099"))), null, null, false)
        assertEquals(listOf("data/${p[1].id}"), provider.writes.filter { it.kind == "delete" }.map { it.path })
        assertEquals(listOf("+44 20 7946 0001", "+44 20 7946 0003", "+44 20 7946 0099"), repo.editable(id)!!.phones.map { it.value })
    }

    @Test fun aReadOnlyRowIsNeverWrittenAgain() = runBlocking {
        val id = repo.save(null, ana, null, null, false)!!.contactId
        provider.exec("UPDATE data SET is_read_only = 1 WHERE _id = ${idOf("+44 20 7946 0001")}")
        val before = repo.editable(id)!!
        provider.writes.clear()
        repo.save(before, before.copy(phones = before.phones.reversed()), null, null, false)
        assertTrue(provider.writes.none { it.kind == "delete" || it.kind == "insert" })
    }

    @Test fun privateContactsAndDraftsKeepTheOrderAsIs() {
        val reordered = ana.copy(phones = ana.phones.reversed(), emails = ana.emails.reversed())
        val sealed = ContactDetailsJson.decode(ContactDetailsJson.encode(reordered))
        assertEquals(reordered.phones.map { it.value }, sealed.phones.map { it.value })
        assertEquals(reordered.emails.map { it.value }, sealed.emails.map { it.value })
        val draft = ContactDraftJson.decode(ContactDraftJson.encode(reordered))
        assertEquals(reordered.phones.map { it.value }, draft.phones.map { it.value })
        assertEquals(reordered.addresses.map { it.street }, draft.addresses.map { it.street })
    }
}
