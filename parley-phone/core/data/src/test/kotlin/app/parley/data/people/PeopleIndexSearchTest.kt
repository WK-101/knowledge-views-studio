package app.parley.data.people

import android.Manifest
import android.app.Application
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Contacts
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.ContactFacets
import app.parley.common.people.ContactSearch
import app.parley.common.people.Facet
import app.parley.common.people.FieldFilter
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.ContactDetails
import app.parley.data.PostalItem
import app.parley.data.EventItem
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Contacts search's address-book index over 5,000 contacts: built off the main thread from every field, rebuilt
 * when the address book says it changed, and searched in a few milliseconds per query once built. Bounds are generous
 * for a slow CI machine.
 */
@RunWith(RobolectricTestRunner::class)
class PeopleIndexSearchTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> listOf(1L) }
        fun rows(sql: String) = provider.exec("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $COUNT) $sql")
        rows("INSERT INTO raw_contacts (_id, contact_id, last_updated) SELECT i, i, 1 FROM n")
        rows(
            "INSERT INTO data (raw_contact_id, mimetype, data1, data2, data3) " +
                "SELECT i, '${StructuredName.CONTENT_ITEM_TYPE}', 'Person ' || i, 'Person', 'N' || i FROM n",
        )
        rows("INSERT INTO data (raw_contact_id, mimetype, data1, data2) SELECT i, '${Phone.CONTENT_ITEM_TYPE}', '+1 555 ' || printf('%07d', i), 2 FROM n")
        rows(
            "INSERT INTO data (raw_contact_id, mimetype, data4, data7, data10) SELECT i, '${StructuredPostal.CONTENT_ITEM_TYPE}', " +
                "'Street ' || i, CASE i % 4 WHEN 0 THEN 'Lisboa' WHEN 1 THEN 'Porto' WHEN 2 THEN 'Madrid' ELSE 'Paris' END, " +
                "CASE i % 4 WHEN 0 THEN 'PT' WHEN 1 THEN 'Portugal' WHEN 2 THEN 'Spain' ELSE 'France' END FROM n",
        )
        rows(
            "INSERT INTO data (raw_contact_id, mimetype, data1, data4) " +
                "SELECT i, '${Organization.CONTENT_ITEM_TYPE}', 'Company ' || (i % 40), 'Engineer' FROM n",
        )
        rows("INSERT INTO data (raw_contact_id, mimetype, data1) SELECT i, '${Note.CONTENT_ITEM_TYPE}', 'Met at event ' || (i % 17) FROM n")
        rows(
            "INSERT INTO data (raw_contact_id, mimetype, data1, data2) " +
                "SELECT i, '${Event.CONTENT_ITEM_TYPE}', '1980-' || printf('%02d', 1 + i % 12) || '-10', 3 FROM n",
        )
    }

    @After fun tearDown() = scope.cancel()

    @Test fun builds_off_the_main_thread_searches_fast_and_follows_changes() {
        val index = PeopleIndex(app, repo, scope)
        // This test runs on the main thread and only waits: were the index built on it, it would never finish.
        val built = runBlocking { withTimeout(TIMEOUT_MS) { index.data.first { it.loaded && it.search.size == COUNT } } }
        val docs = built.search.values.toList()

        val queries = listOf("person 4321", "lisboa", "company 7 madrid", "555 0004321", "+15550004321", "1980", "october", "event 16", "nobody")
        fun timeUs(q: String): Long {
            val t = System.nanoTime()
            val query = ContactSearch.Query(q)
            docs.count { ContactSearch.match(query, it) != null }
            return (System.nanoTime() - t) / 1_000
        }
        repeat(3) { queries.forEach(::timeUs) }
        val medians = queries.associateWith { q -> List(ROUNDS) { timeUs(q) }.sorted()[ROUNDS / 2] / 1_000.0 }
        println("Contacts search over $COUNT address-book contacts, median per query (ms): $medians")
        // Only a plainly broken search fails here (a busy CI machine is slow too); the proportional check of the
        // pipeline is ContactSearchSpeedTest's.
        assertTrue("slowest query: $medians", medians.values.max() < 1_000)

        assertEquals(ContactSearch.Field.ADDRESS, ContactSearch.match("lisboa", built.search.getValue(4)))
        assertEquals(ContactSearch.Field.DATE, ContactSearch.match("october", built.search.getValue(9)))
        assertEquals(ContactSearch.Field.NUMBER, ContactSearch.match("+1 555 000 4321", built.search.getValue(4321)))
        val portugal = FieldFilter().toggle(Facet.COUNTRY, ContactFacets.key("Portugal"))
        assertEquals("PT and Portugal are one country", COUNT / 2, docs.count { portugal.matches(it.facets, photo = false, temporary = false) })

        // A contact added elsewhere: Android says the address book changed, and the index follows.
        val added = runBlocking {
            repo.save(
                null,
                ContactDetails(
                    given = "Zélie", phones = listOf(DataItem(null, "+33 6 12 34 56 78", Phone.TYPE_MOBILE)),
                    addresses = listOf(PostalItem(city = "Lyon", country = "France")), events = listOf(EventItem(date = "--02-29")),
                ),
                null, null, false,
            )!!.contactId
        }
        assertNull(index.data.value.search[added])
        app.contentResolver.notifyChange(Contacts.CONTENT_URI, null)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (index.data.value.search[added] == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        val doc = index.data.value.search[added]
        assertEquals(ContactSearch.Field.ADDRESS, doc?.let { ContactSearch.match("lyon", it) })
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("zelie", doc!!))
        // Only the changed contact was read again; everyone else kept their entry.
        assertTrue("${index.lastUpdate} ${repo.lastLoad}", index.lastUpdate.incremental && index.lastUpdate.read in 1..5)
        assertEquals(COUNT + 1, index.data.value.search.size)
        assertEquals(ContactSearch.Field.ADDRESS, ContactSearch.match("lisboa", index.data.value.search.getValue(4)))
    }

    private companion object {
        const val COUNT = 5_000
        const val ROUNDS = 7
        const val TIMEOUT_MS = 120_000L
    }
}
