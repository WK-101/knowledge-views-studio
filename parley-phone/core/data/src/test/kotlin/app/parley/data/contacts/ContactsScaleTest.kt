package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import app.parley.data.ContactsRepository
import app.parley.data.records.ContactRecordStore
import app.parley.data.sync.FolderSync
import app.parley.data.sync.FolderSync.RunStats
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
 * skips everything unchanged. "Only what changed" is checked by counting the contacts and files read, never by
 * comparing two timings: a loaded machine (a parallel build, a GC pause) can make the small run slower than the big
 * one. The times are only a ceiling, far above any machine, against a hang or a quadratic loop.
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
        assertEquals(COUNT, repo.lastLoad.read)
        assertTrue("full load took $fullMs ms", fullMs < FULL_LOAD_CEILING_MS)

        provider.exec("UPDATE data SET data1 = '+1 555 9999999' WHERE raw_contact_id = 42 AND mimetype = '${Phone.CONTENT_ITEM_TYPE}'")
        provider.exec("UPDATE raw_contacts SET version = version + 1, last_updated = 2 WHERE _id = 42")
        val patched = repo.loadNow()
        // One contact read again instead of all of them.
        assertTrue(repo.lastLoad.incremental)
        assertEquals(1, repo.lastLoad.read)
        assertEquals(COUNT, patched.size)
        assertEquals("+1 555 9999999", patched.first { it.id == 42L }.phones.single().number)

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
        sync.usePlain()

        val (first, firstMs) = timed { sync.syncNow() }
        assertEquals(COUNT, first.written)
        assertEquals(COUNT, sync.lastRun.contactsRead)
        assertEquals(COUNT, sync.lastRun.filesWritten)
        assertTrue("never more than one page of contacts in memory", sync.lastRun.largestPage <= ContactRecordStore.BATCH)
        assertTrue("first sync took $firstMs ms", firstMs < FIRST_SYNC_CEILING_MS)

        folder.reads = 0
        val second = sync.syncNow()
        // Nothing changed: not one contact or file is read, and nothing is written.
        assertEquals(0, second.written + second.imported + second.updatedFromFolder)
        assertEquals(RunStats(), sync.lastRun)
        assertEquals(0, folder.reads)
    }

    private companion object {
        const val COUNT = 10_000
        const val FULL_LOAD_CEILING_MS = 120_000L
        const val FIRST_SYNC_CEILING_MS = 600_000L
    }
}
