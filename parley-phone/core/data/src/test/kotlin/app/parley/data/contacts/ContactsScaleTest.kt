package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import app.parley.data.ContactsRepository
import app.parley.data.records.ContactRecordStore
import app.parley.data.sync.FolderSync
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.testing.FakeDocumentsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * 10,000 contacts: the contact list reloads only what changed, and the folder sync pages through contacts, then
 * skips everything unchanged. Times are generous bounds for a slow CI machine, there to catch a quadratic loop.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsScaleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope, started = SharingStarted.Lazily)
        repo.beforeChange = { _, _ -> listOf(1L) }
        // Straight into the tables: a name and a number each, last changed long ago.
        provider.exec(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $COUNT) " +
                "INSERT INTO raw_contacts (_id, contact_id, last_updated) SELECT i, i, 1 FROM n",
        )
        provider.exec(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $COUNT) " +
                "INSERT INTO data (raw_contact_id, mimetype, data1, data2) SELECT i, '${StructuredName.CONTENT_ITEM_TYPE}', 'Person ' || i, 'Person' FROM n",
        )
        provider.exec(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $COUNT) " +
                "INSERT INTO data (raw_contact_id, mimetype, data1, data2) SELECT i, '${Phone.CONTENT_ITEM_TYPE}', '+1 555 ' || printf('%07d', i), 2 FROM n",
        )
    }

    @After fun tearDown() = scope.cancel()

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t) / 1_000_000
    }

    @Test fun theContactListReloadsOnlyWhatChanged() = runBlocking {
        val (all, fullMs) = timed { repo.loadNow() }
        assertEquals(COUNT, all.size)
        assertFalse(repo.lastLoad.incremental)
        assertTrue("full load took $fullMs ms", fullMs < 60_000)

        provider.exec("UPDATE data SET data1 = '+1 555 9999999' WHERE raw_contact_id = 42 AND mimetype = '${Phone.CONTENT_ITEM_TYPE}'")
        provider.exec("UPDATE raw_contacts SET version = version + 1, last_updated = 2 WHERE _id = 42")
        val (patched, patchMs) = timed { repo.loadNow() }
        assertTrue(repo.lastLoad.incremental)
        assertEquals(1, repo.lastLoad.read)
        assertEquals(COUNT, patched.size)
        assertEquals("+1 555 9999999", patched.first { it.id == 42L }.phones.single().number)
        assertTrue("incremental load ($patchMs ms) should beat the full one ($fullMs ms)", patchMs < fullMs)

        // The patched list is exactly what a full load gives.
        provider.exec("DELETE FROM raw_contacts WHERE _id = 7")
        val incremental = repo.loadNow()
        val fresh = ContactsRepository(app, scope, started = SharingStarted.Lazily).loadNow()
        assertEquals(fresh, incremental)
    }

    @Test fun folderSyncPagesThroughContactsAndThenSkipsTheUnchanged() = runBlocking {
        val folder = FakeDocumentsProvider.install()
        val sync = FolderSync(app, repo, ContactRecordStore(app))
        sync.setFolder(folder.treeUri, "Sync")

        val (first, firstMs) = timed { sync.syncNow() }
        assertEquals(COUNT, first.written)
        assertTrue("never more than one page of contacts in memory", sync.lastRun.largestPage <= ContactRecordStore.BATCH)
        assertTrue("first sync took $firstMs ms", firstMs < 300_000)

        folder.reads = 0
        val (second, secondMs) = timed { sync.syncNow() }
        assertEquals(0, second.written + second.imported + second.updatedFromFolder)
        assertEquals(0, sync.lastRun.contactsRead)
        assertEquals(0, folder.reads)
        assertTrue("an unchanged sync ($secondMs ms) should be far quicker than the first ($firstMs ms)", secondMs * 5 < firstMs)
    }

    private companion object {
        const val COUNT = 10_000
    }
}
