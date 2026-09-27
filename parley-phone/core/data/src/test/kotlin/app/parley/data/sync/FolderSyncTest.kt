package app.parley.data.sync

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.StoredStatus
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.records.ContactRecordStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.testing.FakeDocumentsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The folder sync against a fake Contacts Provider and a fake SAF folder: what it reads, writes and deletes. */
@RunWith(RobolectricTestRunner::class)
class FolderSyncTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var folder: FakeDocumentsProvider
    private lateinit var repo: ContactsRepository
    private lateinit var sync: FolderSync

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        folder = FakeDocumentsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> listOf(1L) }
        sync = FolderSync(app, repo, ContactRecordStore(app))
        sync.setFolder(folder.treeUri, "Sync")
    }

    @After fun tearDown() = scope.cancel()

    private fun add(given: String, number: String): Long = runBlocking {
        repo.save(null, ContactDetails(given = given, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE))), null, null, false)!!.contactId
    }

    private fun kind() = StoredStatus.decode(sync.status.value.lastResult)?.kind

    private fun stateText() = File(app.filesDir, "folder_sync_state.json").readText()

    @Test fun aSecondRunWithNothingChangedReadsNoContactAndNoFile() = runBlocking {
        add("Ada", "+44 20 7946 0000")
        add("Grace", "+1 555 0100")
        val first = sync.syncNow()
        assertEquals(2, first.written)
        assertEquals(2, folder.names().size)

        folder.reads = 0
        val second = sync.syncNow()
        assertEquals(0, second.written + second.imported + second.updatedFromFolder)
        assertEquals(0, sync.lastRun.contactsRead)
        assertEquals(0, sync.lastRun.filesRead)
        assertEquals(0, folder.reads)
    }

    @Test fun aLocalEditRewritesOnlyThatContactsFile() = runBlocking {
        val ada = add("Ada", "+44 20 7946 0000")
        add("Grace", "+1 555 0100")
        sync.syncNow()
        val before = repo.editable(ada)!!
        repo.save(before, before.copy(note = "Engine"), null, null, false)

        val rep = sync.syncNow()
        assertEquals(1, rep.written)
        assertEquals(1, sync.lastRun.filesWritten)
        assertTrue(sync.lastRun.contactsRead <= 2) // hashed once, read once to write
        assertTrue(folder.names().map { File(folder.dir, it).readText() }.any { "Engine" in it })
    }

    @Test fun aFileEditedElsewhereUpdatesTheContact() = runBlocking {
        val ada = add("Ada", "+44 20 7946 0000")
        sync.syncNow()
        val name = folder.names().single()
        folder.put(name, File(folder.dir, name).readText().replace("+44 20 7946 0000", "+44 20 7946 1111"))

        val rep = sync.syncNow()
        assertEquals(1, rep.updatedFromFolder)
        assertEquals("+44 20 7946 1111", repo.details(ada)!!.phones.single().value)
        // That is now the synced state: nothing more to do.
        val again = sync.syncNow()
        assertEquals(0, again.updatedFromFolder + again.written)
    }

    @Test fun aNewFileFromAnotherDeviceIsImported() = runBlocking {
        add("Ada", "+44 20 7946 0000")
        sync.syncNow()
        folder.put("other.vcf", "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Grace Hopper\r\nN:Hopper;Grace;;;\r\nTEL:+1 555 0100\r\nEND:VCARD\r\n")
        val rep = sync.syncNow()
        assertEquals(1, rep.imported)
        assertTrue(repo.loadNow().any { it.displayName == "Grace Hopper" })
    }

    @Test fun aLookupKeyThatNoLongerResolvesIsAKeyChangeNotADeletion() = runBlocking {
        val ada = add("Ada", "+44 20 7946 0000")
        sync.syncNow()
        // A new key with no trace of the old one (an account move): only the raw contact ids still match.
        provider.exec("INSERT OR REPLACE INTO lookup_override (contact_id, lookup) VALUES ($ada, 'moved-key')")
        val rep = sync.syncNow()
        assertEquals(0, rep.deletedFiles)
        assertEquals(1, folder.names().size)
        assertNotNull(repo.details(ada))
        assertTrue(stateText().contains("moved-key"))
    }

    @Test fun aFileDeletedElsewhereWaitsForConfirmationWhenTheContactWasJustEdited() = runBlocking {
        val ada = add("Ada", "+44 20 7946 0000")
        sync.syncNow()
        File(folder.dir, folder.names().single()).delete()

        val paused = sync.syncNow()
        assertEquals(0, paused.deletedLocal)
        assertEquals(SyncStatus.PAUSED, kind())
        assertEquals(1, sync.status.value.pendingDeletions)
        assertNotNull(repo.details(ada))

        val confirmed = sync.syncNow(allowMassDelete = true)
        assertEquals(1, confirmed.deletedLocal)
        assertNull(repo.details(ada))
    }

    @Test fun anOldContactsFileDeletedElsewhereDeletesTheContact() = runBlocking {
        val ada = add("Ada", "+44 20 7946 0000")
        listOf("Grace", "Alan", "Edsger", "Barbara").forEachIndexed { i, n -> add(n, "+1 555 010$i") }
        sync.syncNow()
        provider.exec("UPDATE raw_contacts SET last_updated = 1")
        val s = stateText()
        val name = folder.names().first { n -> s.substringAfter("\"$n\"").substringBefore("}").contains("\"c\":$ada") }
        File(folder.dir, name).delete()
        val rep = sync.syncNow()
        assertEquals(1, rep.deletedLocal)
        assertNull(repo.details(ada))
    }

    @Test fun stateFromAnOlderVersionIsCarriedOverWithoutRewritingFiles() = runBlocking {
        add("Ada", "+44 20 7946 0000")
        add("Grace", "+1 555 0100")
        sync.syncNow()
        // What older versions stored: key, file hash and local hash only (no photos here, so the hashes agree).
        val old = JSONObject(stateText())
        old.keys().forEach { k -> old.getJSONObject(k).apply { remove("v"); remove("t"); remove("s"); remove("c"); remove("r") } }
        File(app.filesDir, "folder_sync_state.json").writeText(old.toString())

        val carried = sync.syncNow()
        assertEquals(0, carried.written + carried.updatedFromFolder + carried.conflicts)
        assertEquals(2, sync.lastRun.contactsRead)
        sync.syncNow()
        assertEquals(0, sync.lastRun.contactsRead)
        assertEquals(0, sync.lastRun.filesRead)
    }
}
