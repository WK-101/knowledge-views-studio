package app.parley.data

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The undo journal keeps a photo once however many copies need it, restores it, and lets it go with the last copy. */
@RunWith(RobolectricTestRunner::class)
class JournalPhotosTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val photo = ByteArray(40_000) { (it * 31 % 253).toByte() }

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        c = DataContainer(app)
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private fun person(name: String) = ContactRecord(
        key = "", displayName = name,
        raws = listOf(
            RawRecord(
                null, null,
                rows = listOf(
                    DataRow(Mime.NAME, mapOf(Col.D1 to name, Col.D2 to name)),
                    DataRow(Mime.PHOTO, emptyMap(), blob = photo),
                ),
            ),
        ),
    )

    private fun photoOf(id: Long) =
        c.records.read(id, fullPhoto = true)?.raws?.firstNotNullOfOrNull { r -> r.rows.firstOrNull { it.mimeType == Mime.PHOTO }?.blob }

    @Test fun aPhotoIsKeptOnceAndComesBackOnRestore() = runBlocking {
        val ids = listOf(c.records.insert(person("Ada"), null)!!, c.records.insert(person("Bo"), null)!!)
        assertArrayEquals(photo, photoOf(ids[0]))
        val copies = c.journal.snapshot(ids, "EDIT") + c.journal.snapshot(ids, "EDIT")
        assertEquals(4, copies.size)
        // Four copies, one photo, and no copy carries the photo itself.
        assertEquals(1, c.meta.journalPhotoHashes().size)
        for (id in copies) assertTrue(c.meta.journalEntry(id)!!.payload.size < photo.size / 4)
        // Sealed at rest like the payloads.
        val stored = c.db.metaDao().journalPhoto(c.meta.journalPhotoHashes().single())!!.blob
        assertTrue(!stored.contentEquals(photo))

        val restored = c.journal.restore(copies[0])
        assertNotNull(restored)
        assertArrayEquals(photo, photoOf(restored!!))
    }

    @Test fun thePhotoGoesWithItsLastCopy() = runBlocking {
        val id = c.records.insert(person("Cy"), null)!!
        val (first, second) = c.journal.snapshot(listOf(id), "EDIT") + c.journal.snapshot(listOf(id), "DELETE")
        assertTrue(c.undoStorage.forgetContactChange(first))
        assertEquals(1, c.meta.journalPhotoHashes().size)
        assertTrue(c.undoStorage.forgetContactChange(second))
        assertEquals(0, c.meta.journalPhotoHashes().size)

        c.journal.snapshot(listOf(id), "EDIT")
        c.journal.prune(before = Long.MAX_VALUE)
        assertEquals(0, c.meta.journalCount())
        assertEquals(0, c.meta.journalPhotoHashes().size)
        assertEquals(0L, c.undoStorage.usage().contactBytes)
    }

    @Test fun undoBringsTheContactBackWhenItsPhotoIsGone() = runBlocking {
        val id = c.records.insert(person("Di"), null)!!
        val copy = c.journal.snapshot(listOf(id), "DELETE").single()
        // The kept photo was removed (by hand, or by a bug): undo restores everything else.
        c.db.metaDao().deleteJournalPhotos(c.meta.journalPhotoHashes())
        val restored = c.journal.restore(copy)
        assertNotNull(restored)
        assertEquals(null, photoOf(restored!!))
        assertEquals("Di", c.records.read(restored, fullPhoto = false)!!.displayName)
    }

    @Test fun numberMemoryKeepsDeletedContactsWithAPhoto() = runBlocking {
        val record = person("Ed").let { p ->
            p.copy(raws = p.raws.map { r -> r.copy(rows = r.rows + DataRow(Mime.PHONE, mapOf(Col.D1 to "+44 20 7946 0999"))) })
        }
        val id = c.records.insert(record, null)!!
        c.journal.snapshot(listOf(id), "DELETE")
        val deleted = c.journal.deletedForMemory().single()
        assertEquals(listOf("+44 20 7946 0999"), deleted.numbers)
    }
}
