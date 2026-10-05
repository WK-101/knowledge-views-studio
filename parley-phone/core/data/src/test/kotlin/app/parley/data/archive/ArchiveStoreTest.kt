package app.parley.data.archive

import android.Manifest
import android.app.Application
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.Unlock
import app.parley.common.circle.InteractionType
import app.parley.common.people.Archive
import app.parley.common.people.ContactRef
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.backup.RestoreOptions
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Archived contacts (docs/CONTACT_MODEL.md, "Archived"): out of the address book and back without losing anything,
 * what Parley keeps about the person following them both ways, their calls still named, and a backup bringing them back.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer
    private val passphrase = "correct horse battery staple"
    private val file by lazy { File(app.cacheDir, "archive-test.parleybackup") }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        File(app.filesDir, "archive").deleteRecursively()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    /** Ada, a phone contact in a label, with a note for calls and a logged moment. */
    private suspend fun ada(): Pair<Long, String> {
        val id = c.contacts.save(
            null,
            ContactDetails(given = "Ada", family = "Lovelace", company = "Engines", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))),
            null, null, false,
        )!!.contactId!!
        val key = c.contacts.lookupKeyOf(id)!!
        c.meta.setMeta(ContactMetaEntity(key, pinnedNote = "Ask about the engine", contactId = id))
        c.circle.interactions.log(key, id, InteractionType.MEET, null, 1_000L, "Coffee in town", "m:coffee")
        val group = c.contacts.createGroup("Friends", AccountRef(null, null))
        if (group != null) c.contacts.addToGroup(listOf(id), c.contacts.groups().first { it.id == group })
        return id to key
    }

    @Test fun archive_and_unarchive_keep_everything() = runBlocking {
        val (id, key) = ada()
        val labelled = c.contacts.details(id)!!.groupIds
        assertTrue("Ada is in a label to begin with", labelled.isNotEmpty())

        // ---- Archive: out of the address book, kept whole, what Parley keeps re-keyed.
        val done = c.archive.archive(id)
        assertNotNull(done)
        assertNull("gone from the address book (lists, pickers, widgets, other apps)", c.contacts.details(id))
        val card = c.archive.all().single()
        assertEquals("Ada Lovelace", card.name)
        assertEquals("Engines", card.company)
        assertEquals(key, card.originalKey)
        val archivedKey = ContactRef.archivedKey(card.id)
        assertEquals("Ask about the engine", c.meta.meta(archivedKey)?.pinnedNote)
        assertEquals("Coffee in town", c.circle.interactions.interactionsFor(archivedKey).single().note)
        assertNull(c.meta.meta(key))
        // A key sweep never resolves an archived contact's key through the address book.
        c.contactKeys.sweep()
        assertEquals("Ask about the engine", c.meta.meta(archivedKey)?.pinnedNote)
        // Nothing readable at rest.
        File(app.filesDir, "archive").listFiles()!!.forEach { f ->
            val raw = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(f.name, raw.contains("Lovelace") || raw.contains("7946"))
        }

        // ---- Unarchive: back in the address book, as it was.
        assertEquals(Archive.Target.Original, c.archive.target(card.id))
        val back = c.archive.unarchive(card.id) as ArchiveStore.Unarchived.Done
        val restored = c.contacts.details(back.contactId)!!
        assertEquals("Ada Lovelace", restored.composedName)
        assertEquals("Engines", restored.company)
        assertEquals("+44 20 7946 0000", restored.phones.single().value)
        assertEquals("labels come back", labelled.size, restored.groupIds.size)
        val newKey = c.contacts.lookupKeyOf(back.contactId)!!
        assertEquals("Ask about the engine", c.meta.meta(newKey)?.pinnedNote)
        assertEquals("Coffee in town", c.circle.interactions.interactionsFor(newKey).single().note)
        assertNull("nothing left under the archived key", c.meta.meta(archivedKey))
        assertTrue(c.archive.all().isEmpty())
        assertTrue(File(app.filesDir, "archive").listFiles().orEmpty().isEmpty())
    }

    @Test fun an_archived_number_is_still_named_on_calls() = runBlocking {
        val (id, _) = ada()
        c.archive.archive(id)
        // However the call writes the number.
        assertEquals("Ada Lovelace", c.archive.lookup("+442079460000", "GB")?.name)
        assertEquals("Ada Lovelace", c.archive.lookup("020 7946 0000", "GB")?.name)
        assertNull(c.archive.lookup("020 7946 0001", "GB"))
        // A new store (the app started for a call) reads the cards from their files.
        val fresh = ArchiveStore(c)
        assertEquals("Ada Lovelace", fresh.lookup("020 7946 0000", "GB")?.name)
        // Screening counts it as a saved contact: never an "unknown caller".
        assertTrue(c.screener.archivedCaller("+442079460000", "GB"))
    }

    @Test fun a_backup_brings_archived_contacts_back() = runBlocking {
        val (id, _) = ada()
        c.archive.archive(id)
        c.backup.setupKeys(passphrase.toCharArray())
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.message, out.ok)
        assertEquals("every backed-up store of the registry has a section", emptyList<String>(), out.failedSections)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Lovelace"))

        // A new phone: nothing archived, nothing in the address book.
        c.scope.cancel()
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
        c.db.close()
        File(app.filesDir, "archive").deleteRecursively()
        c = DataContainer(app)
        assertTrue(c.archive.all().isEmpty())

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        val card = c.archive.all().single()
        assertEquals("Ada Lovelace", card.name)
        assertEquals(listOf("+44 20 7946 0000"), card.numbers)
        // Its whole record came back too, so Unarchive works on the new phone.
        assertEquals("Ada Lovelace", c.archive.readRecord(card.id)?.displayName)
        // Restored twice, it stays one.
        c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals(1, c.archive.all().size)
    }

    @Test fun a_backup_brings_back_what_parley_keeps_about_archived_contacts() = runBlocking {
        val (id, key) = ada()
        val note = "Ask about the engine\n[ ] Bring the plans"
        c.meta.setMeta(ContactMetaEntity(key, pinnedNote = note, reachOutDays = 14, contactId = id))
        c.archive.archive(id)
        val oldKey = c.archive.all().single().parleyKey
        c.backup.setupKeys(passphrase.toCharArray())
        assertTrue(c.backup.backupNow(scheduled = false, target = Uri.fromFile(file)).ok)

        // A new phone where someone else is archived already: Ada comes back under another id than in the backup.
        c.scope.cancel()
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
        c.db.close()
        File(app.filesDir, "archive").deleteRecursively()
        c = DataContainer(app)
        val boDetails = ContactDetails(given = "Bo", phones = listOf(DataItem(null, "+44 20 7946 0999", Phone.TYPE_MOBILE)))
        val bo = c.contacts.save(null, boDetails, null, null, false)!!.contactId!!
        c.archive.archive(bo)
        val boKey = c.archive.all().single().parleyKey
        assertEquals("the backup's id names Bo here", oldKey, boKey)

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val result = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals("every archived entry found its person", 0, result.unmatched)
        val adaKey = c.archive.all().single { it.name == "Ada Lovelace" }.parleyKey
        assertTrue(adaKey != oldKey)
        // The note for calls with its agenda, the Circle and the logged moment follow Ada, not the id.
        assertEquals(note, c.meta.meta(adaKey)?.pinnedNote)
        assertEquals(14, c.meta.meta(adaKey)?.reachOutDays)
        assertEquals("Coffee in town", c.circle.interactions.interactionsFor(adaKey).single().note)
        assertNull("nothing of Ada's lands on Bo", c.meta.meta(boKey)?.pinnedNote)
        assertTrue(c.circle.interactions.interactionsFor(boKey).isEmpty())
        // Restored again: still one of each.
        c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals(2, c.archive.all().size)
        assertEquals(1, c.circle.interactions.interactionsFor(adaKey).size)
    }

    @Test fun when_the_address_book_refuses_nothing_is_archived() = runBlocking {
        val (id, key) = ada()
        provider.refuseRawDeletes = true
        assertNull(c.archive.archive(id))
        assertTrue("not both archived and in the address book", c.archive.all().isEmpty())
        assertTrue(File(app.filesDir, "archive").listFiles().orEmpty().isEmpty())
        assertNotNull(c.contacts.details(id))
        assertEquals("Ask about the engine", c.meta.meta(key)?.pinnedNote)
        // A retry once it works archives one copy.
        provider.refuseRawDeletes = false
        assertNotNull(c.archive.archive(id))
        assertEquals(1, c.archive.all().size)
    }

    @Test fun the_record_codec_keeps_photos() {
        val photo = ByteArray(300) { it.toByte() }
        val r = ContactRecord("k", "Ada", raws = listOf(RawRecord(null, null, rows = listOf(DataRow(Mime.PHOTO, emptyMap(), blob = photo)))))
        val back = ArchiveStore.decodeRecord(ArchiveStore.encodeRecord(r))
        assertTrue(photo.contentEquals(back.raws.single().rows.single().blob))
    }
}
