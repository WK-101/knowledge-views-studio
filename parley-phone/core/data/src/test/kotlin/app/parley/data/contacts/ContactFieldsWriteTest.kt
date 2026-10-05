package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.content.SyncAdapterType
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AltCalendar
import app.parley.common.people.AddressParts
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactDraftJson
import app.parley.data.ContactsRepository
import app.parley.data.CustomFieldItem
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.PostalItem
import app.parley.data.RecordDetails
import app.parley.data.records.ContactRecordStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

/** Custom fields, RFC 9554's name and address parts, the language, phonetic middle name and date calendars on save. */
@RunWith(RobolectricTestRunner::class)
class ContactFieldsWriteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository
    private val google = AccountRef("com.google", "ana@example.org")

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> emptyList() }
        // Google's contacts sync adapter uploads, so its raw contacts can be edited.
        ShadowContentResolver.setSyncAdapterTypes(arrayOf(SyncAdapterType(ContactsContract.AUTHORITY, google.type, true, true)))
    }

    @After fun tearDown() {
        ShadowContentResolver.setSyncAdapterTypes(emptyArray())
        scope.cancel()
    }

    private val ana = ContactDetails(
        given = "Ana", family = "García", phoneticMiddle = "Mah-ree-ah", secondSurname = "López", generation = "Jr.", languages = listOf("Spanish"),
        phones = listOf(DataItem(null, "+34 600 000 000", Phone.TYPE_MOBILE)),
        customFields = listOf(CustomFieldItem(label = "Shoe size", value = "38"), CustomFieldItem(label = "  ", value = " ")),
        events = listOf(EventItem(date = "1990-01-27", type = Event.TYPE_BIRTHDAY, calendar = AltCalendar.CHINESE.key)),
    )

    private fun rows(mime: String) = provider.rows("data").filter { it["mimetype"] == mime }

    @Test fun everyNewFieldIsWrittenAndReadBack() = runBlocking {
        val id = repo.save(null, ana, AccountRef(null, null), null, false)!!.contactId
        assertEquals("Mah-ree-ah", rows(StructuredName.CONTENT_ITEM_TYPE).single()["data8"])
        assertEquals("López" to "Jr.", rows(Mime.NAME_PARTS).single().let { it["data1"] to it["data2"] })
        assertEquals("a language's name is kept as its tag", "es", rows(Mime.LANGUAGE).single()["data1"])
        val field = rows(Mime.CUSTOM_FIELD).single()
        assertEquals("Shoe size" to "38", field["data1"] to field["data2"])
        assertEquals(AltCalendar.CHINESE.key, rows(Event.CONTENT_ITEM_TYPE).single()[AltCalendar.COLUMN])

        val back = repo.editable(id)!!
        assertEquals("Mah-ree-ah", back.phoneticMiddle)
        assertEquals("López", back.secondSurname)
        assertEquals("Jr.", back.generation)
        assertEquals(listOf("es"), back.languages)
        assertEquals(listOf("Shoe size" to "38"), back.customFields.map { it.label to it.value })
        assertEquals(AltCalendar.CHINESE.key, back.events.single().calendar)
    }

    @Test fun aGoogleAccountGetsGooglesCustomFieldSoItSyncs() = runBlocking {
        val id = repo.save(null, ana, google, null, false)!!.contactId
        assertTrue(rows(Mime.CUSTOM_FIELD).isEmpty())
        assertEquals("Shoe size", rows(Mime.GOOGLE_CUSTOM_FIELD).single()["data1"])
        val back = repo.editable(id)!!
        assertEquals(Mime.GOOGLE_CUSTOM_FIELD, back.customFields.single().mime)
        // An edit keeps the row's kind; a new field in the same account takes Google's too.
        provider.writes.clear()
        val edited = back.copy(customFields = back.customFields.map { it.copy(value = "39") } + CustomFieldItem(label = "Locker", value = "A12"))
        repo.save(back, edited, null, null, false)
        assertEquals(listOf("39", "A12"), rows(Mime.GOOGLE_CUSTOM_FIELD).map { it["data2"] })
        assertEquals(listOf("update", "insert"), provider.writes.filter { it.path.startsWith("data") }.map { it.kind })
    }

    @Test fun halfACustomFieldIsNeverWrittenAsGooglesField() = runBlocking {
        val half = ana.copy(customFields = listOf(CustomFieldItem(label = "Shoe size", value = "38"), CustomFieldItem(label = "", value = "Gate code 1234")))
        val id = repo.save(null, half, google, null, false)!!.contactId
        assertEquals(listOf("Shoe size"), rows(Mime.GOOGLE_CUSTOM_FIELD).map { it["data1"] })
        assertEquals(listOf("Gate code 1234"), rows(Mime.CUSTOM_FIELD).map { it["data2"] })
        // Clearing the label of Google's field moves it to Parley's kind; Google's never holds half a field.
        val back = repo.editable(id)!!
        val edited = back.copy(customFields = back.customFields.map { if (it.label == "Shoe size") it.copy(label = "") else it })
        repo.save(back, edited, null, null, false)
        assertTrue(rows(Mime.GOOGLE_CUSTOM_FIELD).isEmpty())
        assertEquals(setOf("38", "Gate code 1234"), rows(Mime.CUSTOM_FIELD).map { it["data2"] }.toSet())
    }

    @Test fun anotherAppsCalendarValueSurvivesAnEditOfTheDate() = runBlocking {
        val persian = ana.copy(events = listOf(EventItem(date = "1990-01-27", type = Event.TYPE_BIRTHDAY, calendar = "persian")))
        val id = repo.save(null, persian, AccountRef(null, null), null, false)!!.contactId
        val back = repo.editable(id)!!
        assertEquals("persian", back.events.single().calendar)
        repo.save(back, back.copy(events = back.events.map { it.copy(date = "1990-01-28") }), null, null, false)
        val row = rows(Event.CONTENT_ITEM_TYPE).single()
        assertEquals("1990-01-28", row["data1"])
        assertEquals("persian", row[AltCalendar.COLUMN])
    }

    @Test fun anUntouchedContactWritesNothingAndRemovalsDelete() = runBlocking {
        val id = repo.save(null, ana, AccountRef(null, null), null, false)!!.contactId
        val back = repo.editable(id)!!
        provider.writes.clear()
        repo.save(back, back, null, null, false)
        assertTrue(provider.writes.none { it.path.startsWith("data") })
        val cleared = back.copy(
            customFields = emptyList(), secondSurname = "", generation = "", languages = emptyList(), events = back.events.map { it.copy(calendar = null) },
        )
        repo.save(back, cleared, null, null, false)
        assertTrue(rows(Mime.CUSTOM_FIELD).isEmpty())
        assertTrue(rows(Mime.NAME_PARTS).isEmpty())
        assertTrue(rows(Mime.LANGUAGE).isEmpty())
        assertNull(rows(Event.CONTENT_ITEM_TYPE).single()[AltCalendar.COLUMN])
    }

    @Test fun addressPartsStayWhenTheAddressIsEdited() = runBlocking {
        val parts = AddressParts.encode(mapOf(AddressParts.Part.FLOOR to "3"))!!
        val record = ContactRecord(
            key = "k", displayName = "Ana",
            raws = listOf(
                RawRecord(
                    null, null,
                    rows = listOf(
                        DataRow(Mime.NAME, mapOf(Col.D1 to "Ana", Col.D2 to "Ana")),
                        DataRow(Mime.POSTAL, mapOf(Col.D4 to "1 Main St", Col.D7 to "Porto", Col.D2 to "1", AddressParts.COLUMN to parts)),
                        DataRow(Mime.CUSTOM_FIELD, mapOf(Col.D1 to "Badge", Col.D2 to "7")),
                    ),
                ),
            ),
        )
        val id = ContactRecordStore(app).insertAll(listOf(record), target = google).single().contactId!!
        assertEquals("a record's custom field takes the account's kind", "Badge", rows(Mime.GOOGLE_CUSTOM_FIELD).single()["data1"])
        val back = repo.editable(id)!!
        assertEquals(parts, back.addresses.single().parts)
        repo.save(back, back.copy(addresses = back.addresses.map { it.copy(street = "2 Main St") }), null, null, false)
        val row = rows(StructuredPostal.CONTENT_ITEM_TYPE).single()
        assertEquals("2 Main St", row["data4"])
        assertEquals(parts, row[AddressParts.COLUMN])
    }

    @Test fun privateContactsAndDraftsKeepEveryNewField() {
        val d = ana.copy(
            customFields = listOf(CustomFieldItem(5, "Shoe size", "38", Mime.GOOGLE_CUSTOM_FIELD)),
            addresses = listOf(PostalItem(street = "1 Main St", parts = "floor=3")),
        )
        val sealed = ContactDetailsJson.decode(ContactDetailsJson.encode(d))
        assertEquals(listOf("Shoe size" to "38"), sealed.customFields.map { it.label to it.value })
        assertEquals(
            listOf(d.phoneticMiddle, d.secondSurname, d.generation, d.languages),
            listOf(sealed.phoneticMiddle, sealed.secondSurname, sealed.generation, sealed.languages),
        )
        assertEquals("floor=3", sealed.addresses.single().parts)
        assertEquals(AltCalendar.CHINESE.key, sealed.events.single().calendar)
        val draft = ContactDraftJson.decode(ContactDraftJson.encode(d))
        assertEquals(d.customFields, draft.customFields)
        assertEquals(d.events, draft.events)
        assertEquals(d.addresses, draft.addresses)
        assertEquals(d.copy(), draft.copy(displayName = d.displayName))
    }

    @Test fun aCardOpensInTheEditorWithEveryNewField() {
        val record = ContactRecord(
            key = "", displayName = "Ana García",
            raws = listOf(
                RawRecord(
                    null, null,
                    rows = listOf(
                        DataRow(Mime.NAME, mapOf(Col.D1 to "Ana García", Col.D2 to "Ana", Col.D3 to "García", Col.D8 to "Lu")),
                        DataRow(Mime.NAME_PARTS, mapOf(Col.D1 to "López")),
                        DataRow(Mime.LANGUAGE, mapOf(Col.D1 to "es")),
                        DataRow(Mime.CUSTOM_FIELD, mapOf(Col.D1 to "Shoe size", Col.D2 to "38")),
                        DataRow(Mime.EVENT, mapOf(Col.D1 to "1990-01-27", Col.D2 to "3", AltCalendar.COLUMN to "chinese")),
                    ),
                ),
            ),
        )
        val d = RecordDetails.toDetails(record)
        assertEquals("Lu", d.phoneticMiddle)
        assertEquals("López", d.secondSurname)
        assertEquals(listOf("es"), d.languages)
        assertEquals("38", d.customFields.single().value)
        assertEquals("chinese", d.events.single().calendar)
        assertTrue("nothing the editor can't show", !RecordDetails.hasHiddenFields(record))
    }
}
