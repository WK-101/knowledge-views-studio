package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.OperationApplicationException
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.Batches
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.GroupInfo
import app.parley.data.applyInBatches
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Large writes reach the contacts provider in batches it accepts: the fake refuses more than 500 operations between
 * yield points, as AOSP's ContactsProvider does, and records every batch's size.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderBatchesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope, started = SharingStarted.Lazily)
        repo.beforeChange = { _, _ -> listOf(1L) }
        provider.exec(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $COUNT) " +
                "INSERT INTO raw_contacts (_id, contact_id, last_updated) SELECT i, i, 1 FROM n",
        )
        provider.exec(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $COUNT) " +
                "INSERT INTO data (raw_contact_id, mimetype, data1) SELECT i, '${StructuredName.CONTENT_ITEM_TYPE}', 'Person ' || i FROM n",
        )
    }

    @After fun tearDown() = scope.cancel()

    private fun liveContacts() = provider.rows("raw_contacts", "deleted = 0").size

    @Test fun theFakeRefusesWhatTheRealProviderRefuses() {
        val ops = (1L..600L).map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it)).build() }
        try {
            app.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops))
            fail("600 operations without a yield point must be refused")
        } catch (_: OperationApplicationException) {
        }
        assertEquals(COUNT, liveContacts())
    }

    @Test fun anyNumberOfOperationsGoInAcceptedBatches() {
        val n = 1_234
        val results = app.contentResolver.applyInBatches(
            (1L..n).map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it)) },
        )
        assertEquals(n, results.size)
        assertEquals(listOf(400, 400, 400, 34), provider.batchSizes)
        assertTrue(provider.batchSizes.all { it <= Batches.MAX_OPS })
        assertEquals(COUNT - n, liveContacts())
    }

    @Test fun deletingMoreThanFiveHundredContactsDeletesThemAll() = runBlocking {
        repo.delete((1L..1_100L).toList())
        assertEquals(COUNT - 1_100, liveContacts())
        assertTrue(provider.batchSizes.toString(), provider.batchSizes.size >= 3 && provider.batchSizes.all { it <= Batches.MAX_OPS })
    }

    @Test fun addingManyContactsToALabelGoesInBatches() = runBlocking {
        val group = repo.createGroup("Neighbours", AccountRef(null, null)) ?: error("no group")
        provider.batchSizes.clear()
        val skipped = repo.addToGroup((1L..900L).toList(), GroupInfo(group, "Neighbours", AccountRef(null, null)))
        assertEquals(0, skipped)
        assertEquals(900, provider.rows("data", "mimetype = '${ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE}'").size)
        assertEquals(listOf(400, 400, 100), provider.batchSizes)
    }

    private companion object {
        const val COUNT = 1_500
    }
}
