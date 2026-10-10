package app.parley.data.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.data.backup.FileBlobStore
import app.parley.data.db.AppDatabase
import app.parley.data.db.BlockedCallEntity
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.JournalEntity
import app.parley.data.db.JournalPhotoEntity
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Notes, screened names, journal payloads and snapshots are sealed at rest and read back plain; older rows too. */
@RunWith(RobolectricTestRunner::class)
class RecordSealingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val crypto by lazy { RecordCrypto.get(context) }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun String?.readable() = this != null && !crypto.isSealed(this)

    @Test fun notes_and_names_are_sealed_in_the_table_and_plain_above_it() = runBlocking {
        val meta = SealedMetaDao(db.metaDao(), crypto)
        meta.setMeta(ContactMetaEntity("k1", pinnedNote = "Allergic to peanuts"))
        assertTrue(crypto.isSealed(db.metaDao().meta("k1")!!.pinnedNote))
        assertEquals("Allergic to peanuts", meta.meta("k1")!!.pinnedNote)
        assertEquals("Allergic to peanuts", meta.allMeta().first().single().pinnedNote)

        meta.addCallNote(CallNoteEntity(numberKey = "n1", callDate = 5, text = "Owes me a call"))
        assertTrue(crypto.isSealed(db.metaDao().allCallNotesNow().single().text))
        assertEquals("Owes me a call", meta.callNotesNow(listOf("n1")).single().text)
        // Duplicate checks compare the plain text.
        assertEquals(1, meta.countCallNote("n1", 5, "Owes me a call"))
        assertEquals(0, meta.countCallNote("n1", 5, "Something else"))

        val blocks = SealedBlockDao(db.blockDao(), crypto)
        blocks.logScreened(BlockedCallEntity(number = "+15550100", reason = "rule", action = "REJECT", time = 1, callerName = "ACME Sales"))
        assertFalse(db.blockDao().screenedSince(0).single().callerName.readable())
        assertEquals("ACME Sales", blocks.screenedSince(0).single().callerName)

        meta.addJournal(JournalEntity(contactKey = "k1", displayName = "Ada", action = "DELETE", time = 1, payload = "payload".toByteArray()))
        val id = db.metaDao().journalIds().single()
        assertTrue(crypto.isSealed(db.metaDao().journalEntry(id)!!.payload))
        assertArrayEquals("payload".toByteArray(), meta.journalEntry(id)!!.payload)
    }

    /** A device contact's relation kept in Parley only names a private contact: sealed in the table, plain above it. */
    @Test fun parley_only_relations_are_sealed_and_survive_whole_row_writes() = runBlocking {
        val meta = SealedMetaDao(db.metaDao(), crypto)
        meta.setMeta(ContactMetaEntity("k3", parleyRelations = "Ana\t13\t"))
        assertTrue(crypto.isSealed(db.metaDao().meta("k3")!!.parleyRelations))
        assertEquals("Ana\t13\t", meta.meta("k3")!!.parleyRelations)

        meta.setParleyRelations("k3", "Rui\t14\t")
        assertTrue(crypto.isSealed(db.metaDao().meta("k3")!!.parleyRelations))
        assertEquals("Rui\t14\t", meta.meta("k3")!!.parleyRelations)
        // A whole-row write of the row as read keeps them.
        meta.setMeta(meta.meta("k3")!!.copy(pinnedNote = "Hi"))
        assertEquals("Rui\t14\t", meta.meta("k3")!!.parleyRelations)
        meta.setParleyRelations("k3", null)
        assertEquals(null, db.metaDao().meta("k3")!!.parleyRelations)
    }

    @Test fun older_plain_rows_stay_readable_and_are_resealed_once() = runBlocking {
        val raw = db.metaDao()
        raw.setMeta(ContactMetaEntity("k2", pinnedNote = "Plain from before"))
        raw.addCallNote(CallNoteEntity(numberKey = "n2", callDate = 7, text = "Plain call note"))
        db.blockDao().logScreened(BlockedCallEntity(number = "+15550101", reason = "rule", action = "REJECT", time = 2, callerName = "Plain Name"))
        raw.addJournal(JournalEntity(contactKey = "k2", displayName = "Bo", action = "EDIT", time = 2, payload = byteArrayOf(1, 2, 3, 4, 5)))
        // A journal photo kept plain during a Keystore hiccup (later copies of the same photo point at it).
        raw.addJournalPhoto(JournalPhotoEntity("cafe", byteArrayOf(9, 8, 7, 6, 5, 4)))
        val meta = SealedMetaDao(raw, crypto)
        assertEquals("Plain from before", meta.meta("k2")!!.pinnedNote)

        val blobs = File(context.filesDir, "timemachine/blobs").apply { deleteRecursively() }
        val plainStore = FileBlobStore(blobs)
        plainStore.put("ab12", "old snapshot".toByteArray())

        context.getSharedPreferences("record_sealing", Context.MODE_PRIVATE).edit().clear().commit()
        val sealing = RecordSealing(context, db, { app.parley.data.backup.TimeMachine(context, app.parley.data.records.ContactRecordStore(context)) })
        val n = sealing.runIfNeeded()
        assertEquals(6, n)
        assertTrue(sealing.done)

        assertTrue(crypto.isSealed(raw.meta("k2")!!.pinnedNote))
        assertTrue(crypto.isSealed(raw.allCallNotesNow().single().text))
        assertTrue(crypto.isSealed(db.blockDao().screenedSince(0).single().callerName))
        assertTrue(crypto.isSealed(raw.journalEntry(raw.journalIds().single())!!.payload))
        assertEquals("Plain from before", meta.meta("k2")!!.pinnedNote)
        assertEquals("Plain call note", meta.allCallNotesNow().single().text)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), meta.journalEntry(raw.journalIds().single())!!.payload)
        assertTrue(crypto.isSealed(raw.journalPhoto("cafe")!!.blob))
        assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5, 4), meta.journalPhoto("cafe")!!.blob)
        // The snapshot blob is sealed on disk and still reads back through a sealing store.
        val sealedStore = FileBlobStore(blobs, crypto)
        assertArrayEquals("old snapshot".toByteArray(), sealedStore.get("ab12"))
        assertTrue(crypto.isSealed(sealedStore.all().single().readBytes()))
    }

    @Test fun a_contact_list_head_kept_plain_is_sealed_later() = runBlocking {
        val head = app.parley.data.people.ContactListHead(context, crypto)
        val file = File(context.noBackupFilesDir, "contact_list_head")
        val list = listOf(
            app.parley.common.ContactSummary(id = 1, lookupKey = "k1", displayName = "Ada", photoUri = null, starred = false, phones = emptyList()),
        )
        // As the fallback writes it while the key can't be used: the encoded rows, plain.
        file.writeBytes(app.parley.common.people.ListHead.encode(app.parley.common.ux.ListSections.interleave(list) { "A" }).toByteArray())
        fun name() = head.load()!!.rows.filterIsInstance<app.parley.common.ux.ListSections.Row.Item<app.parley.common.ContactSummary>>()
            .single().item.displayName
        assertEquals("Ada", name())
        assertTrue(head.resealPlain())
        assertTrue(crypto.isSealed(file.readBytes()))
        assertEquals("Ada", name())
    }
}
