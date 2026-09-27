package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.db.AppDatabase
import app.parley.data.db.ContactMetaEntity
import app.parley.data.people.ContactKeys
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What other apps and sync adapters do to contacts behind Parley's back: lookup keys that change, and deletions that
 * only mark synced raw contacts deleted until the next sync.
 */
@RunWith(RobolectricTestRunner::class)
class ContactProviderChangesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> listOf(1L) }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun create(given: String, number: String, account: AccountRef? = null): Long = runBlocking {
        repo.save(null, ContactDetails(given = given, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE))), account, null, false)!!.contactId
    }

    private fun keys() = ContactKeys(repo, db.metaDao(), { error("no call backgrounds in this test") }, db = db)

    @Test fun anOldLookupKeyStillFindsItsContact() {
        val id = create("Ada", "+44 20 7946 0000")
        val old = repo.lookupKeyOf(id)!!
        provider.changeLookupKey(id, "new.key")
        assertEquals("new.key", repo.lookupKeyOf(id))
        assertEquals(id to "new.key", repo.currentOf(old, id))
        assertEquals("without the id too", id to "new.key", repo.currentOf(old, null))
    }

    @Test fun aKeyFollowsItsRawContactIntoAnotherContact() {
        val ada = create("Ada", "+44 20 7946 0000")
        val grace = create("Grace", "+1 555 0100")
        val old = repo.lookupKeyOf(ada)!!
        provider.moveRaw(repo.rawIds(ada).single(), grace)
        assertEquals(grace to repo.lookupKeyOf(grace)!!, repo.currentOf(old, ada))
    }

    @Test fun sweepMovesNotesToTheChangedKey() = runBlocking {
        val id = create("Ada", "+44 20 7946 0000")
        val old = repo.lookupKeyOf(id)!!
        db.metaDao().setMeta(ContactMetaEntity(old, pinnedNote = "Call after 6", contactId = id))
        provider.changeLookupKey(id, "renamed.key")

        assertEquals(1, keys().sweep())
        assertNull(db.metaDao().meta(old))
        assertEquals("Call after 6", db.metaDao().meta("renamed.key")?.pinnedNote)
        assertEquals("nothing left to move", 0, keys().sweep())
    }

    @Test fun sweepLeavesNotesOfADeletedContactAlone() = runBlocking {
        val id = create("Ada", "+44 20 7946 0000")
        val old = repo.lookupKeyOf(id)!!
        db.metaDao().setMeta(ContactMetaEntity(old, pinnedNote = "Call after 6", contactId = id))
        repo.delete(listOf(id))

        assertEquals(0, keys().sweep())
        assertEquals("Call after 6", db.metaDao().meta(old)?.pinnedNote)
    }

    @Test fun aDeletedSyncedContactIsOnlyMarkedUntilTheNextSync() = runBlocking {
        val id = create("Ada", "+44 20 7946 0000", AccountRef("com.google", "ada@example.org"))
        val raw = repo.rawIds(id).single()
        repo.delete(listOf(id))

        // Still in the provider, marked deleted: every read that lists raw contacts must skip it.
        val row = provider.rows("raw_contacts").single()
        assertEquals("1", row["deleted"])
        assertTrue(repo.rawIds(id).isEmpty())
        assertTrue(repo.contactsOfRaws(listOf(raw)).isEmpty())
        assertNull(repo.details(id))
        assertEquals(false, repo.isContact("+44 20 7946 0000"))
        assertNull(repo.currentOf("lk$id", id))

        provider.purgeDeleted()
        assertTrue(provider.rows("raw_contacts").isEmpty())
    }

    @Test fun aPhoneOnlyContactGoesAtOnce() = runBlocking {
        val kept = create("Grace", "+1 555 0100", AccountRef("com.google", "grace@example.org"))
        val id = create("Ada", "+44 20 7946 0000")
        repo.delete(listOf(id))
        assertEquals(listOf(kept.toString()), provider.rows("raw_contacts").map { it["contact_id"] })
        assertNotEquals(id, kept)
    }
}
