package app.parley.data.sync

import org.junit.Assert.assertFalse
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.common.backup.RecordJson
import app.parley.common.backup.SyncCrypto
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
        // These tests read and write plain vCards; encrypted files are covered by encryptedFilesAreSealedAndBoundToTheFolderKey.
        sync.usePlain()
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

    @Test fun aFolderWithoutAChosenModeWaitsForTheChoice() = runBlocking {
        add("Ada", "+44 20 7946 0000")
        sync.setFolder(folder.treeUri, "Sync")
        assertEquals(0, sync.syncNow().written)
        assertEquals(SyncStatus.CHOOSE_MODE, kind())
        assertTrue(folder.names().isEmpty())
    }

    @Test fun encryptedFilesAreSealedAndBoundToTheFolderKey() = runBlocking {
        FakeAndroidKeyStore.install()
        add("Ada", "+44 20 7946 0000")
        sync.setFolder(folder.treeUri, "Sync")
        assertEquals(FolderSync.EncryptionSetup.READY, sync.useEncryption("harbour lantern quiet mosaic".toCharArray()))
        assertEquals(1, sync.syncNow().written)
        val card = folder.names().single { it.endsWith(SyncCrypto.EXTENSION) }
        assertFalse(File(folder.dir, card).readText(Charsets.ISO_8859_1).contains("Ada"))
        assertTrue(SyncCrypto.HEADER_NAME in folder.names())

        // Another phone with the same passphrase gets the same key and adds a contact; a plain .vcf is ignored.
        val header = File(folder.dir, SyncCrypto.HEADER_NAME).readBytes()
        val key = SyncCrypto.unlock(header, "harbour lantern quiet mosaic".toCharArray())!!
        val grace = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Grace Hopper\r\nN:Hopper;Grace;;;\r\nTEL:+1 555 0100\r\nEND:VCARD\r\n"
        File(folder.dir, "g.parleycard").writeBytes(SyncCrypto.seal(key, "g.parleycard", grace.toByteArray(), 1))
        folder.put("planted.vcf", grace.replace("Grace", "Mallory"))
        // A sealed file renamed to another name doesn't open.
        File(folder.dir, "swapped.parleycard").writeBytes(SyncCrypto.seal(key, "other.parleycard", grace.toByteArray(), 1))
        val rep = sync.syncNow()
        assertEquals(1, rep.imported)
        val names = repo.loadNow().map { it.displayName }
        assertTrue(names.contains("Grace Hopper"))
        assertFalse(names.any { it.contains("Mallory") })
        // A wrong passphrase for this folder is refused.
        sync.setFolder(folder.treeUri, "Sync")
        assertEquals(FolderSync.EncryptionSetup.WRONG_PASSPHRASE, sync.useEncryption("not the passphrase".toCharArray()))
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

    private val pass = "harbour lantern quiet mosaic"

    /** The folder as a version before encrypted sync left it: plain files, a sync state, and no mode chosen. */
    private fun asUpgradedFolder(): FolderSync {
        app.getSharedPreferences("folder_sync", android.content.Context.MODE_PRIVATE).edit().remove("mode").commit()
        return FolderSync(app, repo, ContactRecordStore(app)).also { assertEquals(SyncMode.UNSET, it.status.value.mode) }
    }

    private fun nameOnly(given: String): Long =
        runBlocking { repo.save(null, ContactDetails(given = given, note = "No number"), null, null, false)!!.contactId }

    private fun sealedKey(): ByteArray = SyncCrypto.unlock(File(folder.dir, SyncCrypto.HEADER_NAME).readBytes(), pass.toCharArray())!!

    private fun rewrite(name: String, bytes: ByteArray) {
        val f = File(folder.dir, name)
        val before = f.lastModified()
        f.writeBytes(bytes)
        f.setLastModified(maxOf(System.currentTimeMillis(), before + 2_000))
    }

    @Test fun encryptingAnUpgradedFolderMovesEveryPlainFileParleyWroteAndNothingElse() = runBlocking {
        FakeAndroidKeyStore.install()
        add("Ada", "+44 20 7946 0000")
        nameOnly("Grace")
        sync.syncNow()
        assertEquals(2, folder.names().count { it.endsWith(".vcf") })
        // Another phone's plain file this phone hasn't seen yet, and a vCard that isn't Parley's.
        val uid = "other-phone-key"
        val otherName = RecordJson.sha256Hex(uid.toByteArray()).take(32) + ".vcf"
        folder.put(otherName, "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:$uid\r\nFN:Alan Turing\r\nN:Turing;Alan;;;\r\nEND:VCARD\r\n")
        folder.put("my-own-card.vcf", "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Not Parley\r\nEND:VCARD\r\n")

        val upgraded = asUpgradedFolder()
        assertEquals(FolderSync.EncryptionSetup.READY, upgraded.useEncryption(pass.toCharArray()))
        assertEquals(listOf("my-own-card.vcf"), folder.names().filter { it.endsWith(".vcf") })
        assertEquals(0, upgraded.status.value.plainLeft)
        assertFalse(folder.names().filter { it.endsWith(SyncCrypto.EXTENSION) }.any { File(folder.dir, it).readText(Charsets.ISO_8859_1).contains("Grace") })

        // The state followed the new names: nothing of this phone's is imported again or written twice.
        val rep = upgraded.syncNow()
        assertEquals(0, rep.written + rep.linked + rep.updatedFromFolder)
        assertEquals(1, rep.imported) // the other phone's contact
        assertEquals(listOf("Ada", "Alan Turing", "Grace"), repo.loadNow().map { it.displayName }.sorted())
    }

    @Test fun choosingPlainForAnUpgradedFolderKeepsItsSyncState() = runBlocking {
        add("Ada", "+44 20 7946 0000")
        nameOnly("Grace")
        sync.syncNow()
        val upgraded = asUpgradedFolder()
        upgraded.usePlain()
        val rep = upgraded.syncNow()
        assertEquals(0, rep.written + rep.imported + rep.linked)
        assertEquals(2, repo.loadNow().size)
    }

    @Test fun withoutASyncStateThisPhonesOwnFilesAreLinkedByTheirUidEvenWithoutANumber() = runBlocking {
        add("Ada", "+44 20 7946 0000")
        nameOnly("Grace")
        sync.syncNow()
        File(app.filesDir, "folder_sync_state.json").delete()
        val rep = sync.syncNow()
        assertEquals(2, rep.linked)
        assertEquals(0, rep.imported + rep.written)
        assertEquals(2, repo.loadNow().size)
    }

    @Test fun anOlderEncryptedFilePutBackIsIgnoredAndOverwritten() = runBlocking {
        FakeAndroidKeyStore.install()
        val ada = add("Ada", "+44 20 7946 0000")
        sync.setFolder(folder.treeUri, "Sync")
        sync.useEncryption(pass.toCharArray())
        sync.syncNow()
        val name = folder.names().single { it.endsWith(SyncCrypto.EXTENSION) }
        val old = File(folder.dir, name).readBytes()
        val before = repo.editable(ada)!!
        repo.save(before, before.copy(note = "Engine"), null, null, false)
        assertEquals(1, sync.syncNow().written)

        rewrite(name, old)
        val rep = sync.syncNow()
        assertEquals(0, rep.updatedFromFolder)
        assertEquals("Engine", repo.details(ada)!!.note)
        // The folder holds the current version again.
        val opened = SyncCrypto.openVersioned(sealedKey(), name, File(folder.dir, name).readBytes())!!
        assertTrue(String(opened.vcard).contains("Engine"))
    }

    @Test fun aDeletedContactsFilePutBackIsNotImportedAgain() = runBlocking {
        FakeAndroidKeyStore.install()
        add("Ada", "+44 20 7946 0000")
        val grace = add("Grace", "+1 555 0100")
        sync.setFolder(folder.treeUri, "Sync")
        sync.useEncryption(pass.toCharArray())
        sync.syncNow()
        val before = folder.names().filter { it.endsWith(SyncCrypto.EXTENSION) }.associateWith { File(folder.dir, it).readBytes() }
        repo.delete(listOf(grace))
        assertEquals(1, sync.syncNow().deletedFiles)
        val (name, bytes) = before.entries.single { it.key !in folder.names() }

        rewrite(name, bytes)
        val rep = sync.syncNow()
        assertEquals(0, rep.imported + rep.linked)
        assertFalse(repo.loadNow().any { it.displayName == "Grace" })
    }

    @Test fun aFolderStillLoadingIsNotSyncedAndNothingIsDeleted() = runBlocking {
        listOf("Ada", "Grace", "Alan", "Edsger").forEachIndexed { i, n -> add(n, "+1 555 010$i") }
        sync.syncNow()
        provider.exec("UPDATE raw_contacts SET last_updated = 1")
        sync.listingRetryMs = 1
        folder.loading = true
        val rep = sync.syncNow()
        assertEquals(SyncStatus.FOLDER_LOADING, kind())
        assertEquals(0, rep.deletedLocal)
        assertEquals(4, repo.loadNow().size)
        folder.loading = false
        assertEquals(0, sync.syncNow().deletedLocal)
    }

    @Test fun aContactTheSyncImportedIsNotHeldAsRecentlyEditedAndAHeldDeletionDoesntStopTheRest() = runBlocking {
        val ada = add("Ada", "+44 20 7946 0000")
        sync.syncNow()
        folder.put("other.vcf", "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Grace Hopper\r\nN:Hopper;Grace;;;\r\nTEL:+1 555 0100\r\nEND:VCARD\r\n")
        assertEquals(1, sync.syncNow().imported)

        // Both files deleted elsewhere, and a new one: Grace (imported yesterday by the sync) goes; Ada (edited here) waits.
        File(folder.dir, "other.vcf").delete()
        File(folder.dir, folder.names().single()).delete()
        folder.put("third.vcf", "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Alan Turing\r\nN:Turing;Alan;;;\r\nTEL:+1 555 0199\r\nEND:VCARD\r\n")
        val rep = sync.syncNow()
        assertEquals(1, rep.deletedLocal)
        assertEquals(1, rep.imported)
        assertEquals(SyncStatus.PAUSED, kind())
        assertEquals(1, sync.status.value.pendingDeletions)
        assertNotNull(repo.details(ada))
        assertFalse(repo.loadNow().any { it.displayName == "Grace Hopper" })
    }

    @Test fun renamingALabelRewritesItsMembersFiles() = runBlocking {
        val ada = add("Ada", "+44 20 7946 0000")
        val cr = app.contentResolver
        val group = cr.insert(
            android.provider.ContactsContract.Groups.CONTENT_URI,
            android.content.ContentValues().apply { put("title", "Friends"); put("group_visible", 1) },
        )!!.lastPathSegment!!.toLong()
        cr.insert(
            android.provider.ContactsContract.Data.CONTENT_URI,
            android.content.ContentValues().apply {
                put("raw_contact_id", repo.rawIds(ada).single())
                put("mimetype", android.provider.ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                put("data1", group)
            },
        )
        sync.syncNow()
        val name = folder.names().single()
        assertTrue(File(folder.dir, name).readText().contains("Friends"))

        provider.exec("UPDATE groups SET title = 'Pals' WHERE _id = $group") // no raw contact's version moves
        assertEquals(1, sync.syncNow().written)
        assertTrue(File(folder.dir, name).readText().contains("Pals"))
    }
}
