package app.parley.data.people

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.ContactSearch
import app.parley.common.record.Mime
import app.parley.data.ContactsRepository
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The index reads again only the contacts that changed, and "changed" isn't only what the contact list shows: a label,
 * company, nickname, note, address or any other searched field changed elsewhere leaves the summary equal, and must
 * still reach the index.
 */
@RunWith(RobolectricTestRunner::class)
class PeopleIndexRefreshTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        fun rows(sql: String) = provider.exec("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $COUNT) $sql")
        rows("INSERT INTO raw_contacts (_id, contact_id, last_updated) SELECT i, i, 1 FROM n")
        rows("INSERT INTO data (raw_contact_id, mimetype, data1) SELECT i, '${StructuredName.CONTENT_ITEM_TYPE}', 'Person ' || i FROM n")
        rows("INSERT INTO data (raw_contact_id, mimetype, data1, data2) SELECT i, '${Phone.CONTENT_ITEM_TYPE}', '+1 555 ' || printf('%07d', i), 2 FROM n")
        rows("INSERT INTO data (raw_contact_id, mimetype, data1) SELECT i, '${Organization.CONTENT_ITEM_TYPE}', 'Acme' FROM n")
        provider.exec("INSERT INTO groups (_id, title) VALUES (7, 'Choir')")
    }

    @After fun tearDown() = scope.cancel()

    private fun insert(contact: Long, mime: String, vararg values: Pair<String, Any>) {
        app.contentResolver.insert(
            Data.CONTENT_URI,
            ContentValues().apply {
                put(Data.RAW_CONTACT_ID, contact)
                put(Data.MIMETYPE, mime)
                values.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
            },
        )
    }

    /** Tells the index the address book changed and waits until [ready] holds for it. */
    private fun changedUntil(what: String, index: PeopleIndex, ready: (PeopleIndexData) -> Boolean) {
        app.contentResolver.notifyChange(Contacts.CONTENT_URI, null)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!ready(index.data.value) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("$what reached the index", ready(index.data.value))
        // One contact's rows read again, not the whole address book.
        assertTrue("$what: ${index.lastUpdate}", index.lastUpdate.incremental && index.lastUpdate.read in 1..2)
    }

    private fun PeopleIndexData.finds(id: Long, query: String) = search[id]?.let { ContactSearch.match(query, it) } != null

    @Test fun every_kind_of_field_change_reaches_the_index() {
        val index = PeopleIndex(app, repo, scope)
        runBlocking { withTimeout(TIMEOUT_MS) { index.data.first { it.loaded && it.search.size == COUNT } } }

        insert(3, Mime.GROUP, Data.DATA1 to 7)
        changedUntil("a label", index) { "Choir" in it.extras[3]?.labels.orEmpty() && it.labelCounts["Choir"] == 1 }

        app.contentResolver.update(
            Data.CONTENT_URI, ContentValues().apply { put(Data.DATA1, "Globex") }, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf("4", Organization.CONTENT_ITEM_TYPE),
        )
        changedUntil("a company", index) { it.extras[4]?.company == "Globex" && it.finds(4, "globex") }

        insert(5, Mime.NICKNAME, Data.DATA1 to "Sunny")
        changedUntil("a nickname", index) { it.extras[5]?.nickname == "Sunny" && it.finds(5, "sunny") }

        insert(6, Mime.NOTE, Data.DATA1 to "Plays the cello")
        changedUntil("a note", index) { it.finds(6, "cello") }

        insert(7, Mime.POSTAL, Data.DATA4 to "1 Rue Haute", Data.DATA7 to "Grenoble", Data.DATA10 to "France")
        changedUntil("an address", index) { it.finds(7, "grenoble") }

        insert(8, Mime.WEBSITE, Data.DATA1 to "https://quillwork.example")
        changedUntil("a website", index) { it.finds(8, "quillwork") }

        insert(9, Mime.RELATION, Data.DATA1 to "Marguerite", Data.DATA2 to 14)
        changedUntil("a relation", index) { it.finds(9, "marguerite") }

        insert(10, Mime.IM, Data.DATA1 to "zebulon@chat.example", Data.DATA5 to 7)
        changedUntil("a messenger handle", index) { it.finds(10, "zebulon") }

        insert(11, Mime.EMAIL, Data.DATA1 to "only.in.search@example.org")
        changedUntil("an e-mail", index) { it.finds(11, "only.in.search") }

        insert(12, Mime.EVENT, Data.DATA1 to "2020-05-01", Data.DATA2 to 0, Data.DATA3 to "Died")
        changedUntil("a date of death", index) { it.extras[12]?.deceased == true }
    }

    private companion object {
        const val COUNT = 40
        const val TIMEOUT_MS = 60_000L
    }
}
