package app.parley.data.backup

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.backup.SnapshotIndex
import app.parley.common.backup.SnapshotKeep
import app.parley.common.backup.SnapshotWriter
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.records.ContactRecordStore
import app.parley.data.security.RecordCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The time machine on its change-only index, and the move from the per-snapshot files versions before 5.4 wrote. */
@RunWith(RobolectricTestRunner::class)
class TimeMachineIndexTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val root get() = File(app.filesDir, "timemachine")
    private val day = 86_400_000L

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        root.deleteRecursively()
    }

    private fun person(key: String, name: String, number: String, photo: ByteArray? = null) = ContactRecord(
        key = key,
        displayName = name,
        raws = listOf(
            RawRecord(
                null, null,
                rows = listOfNotNull(
                    DataRow(Mime.NAME, mapOf("data1" to name)),
                    DataRow(Mime.PHONE, mapOf("data1" to number, "data2" to "2")),
                    photo?.let { DataRow(Mime.PHOTO, mapOf("data14" to "1"), blob = it) },
                ),
            ),
        ),
    )

    /** Writes snapshots the way 5.3 did: sealed blobs, and one full index file per snapshot. */
    private fun oldSnapshots(vararg days: Pair<Long, List<ContactRecord>>): List<SnapshotIndex> {
        val store = FileBlobStore(File(root, "blobs"), RecordCrypto.get(app))
        val dir = File(root, "index").apply { mkdirs() }
        return days.map { (t, people) ->
            SnapshotWriter(store).write(t, people).index.also { File(dir, "$t.idx").writeBytes(it.toBytes()) }
        }
    }

    @Test fun oldIndexFilesMoveOverOnceAndReadTheSame() = runBlocking {
        val now = System.currentTimeMillis()
        val pic = ByteArray(300) { it.toByte() }
        val ana1 = person("ana", "Ana", "+44 7700 900001", pic)
        val ana2 = person("ana", "Ana Lee", "+44 7700 900002", pic)
        val bo = person("bo", "Bo", "+44 7700 900003")
        val old = oldSnapshots(now - 3 * day to listOf(ana1, bo), now - 2 * day to listOf(ana1, bo), now - day to listOf(ana2))
        val tm = TimeMachine(app, ContactRecordStore(app))

        assertEquals(old.map { it.timestamp }, tm.snapshotTimes())
        assertFalse("the old files are gone once moved", File(root, "index").exists())
        assertTrue(File(root, "versions.bin").isFile)
        old.forEach { assertEquals(it, tm.snapshotAt(it.timestamp)) }
        assertEquals(listOf(ana1, ana2), tm.history("ana").map { it.record })
        assertEquals(listOf(bo, null), tm.history("bo").map { it.record })
        assertEquals(mapOf("bo" to bo, "ana" to ana1), tm.lastVersions(listOf("bo", "ana"), atOrBefore = now - 2 * day))
        val diff = tm.diffBetween(old[0].timestamp, old[2].timestamp)!!
        assertEquals(listOf(bo), diff.removed)
        assertEquals(listOf("ana"), diff.changed.map { it.key })

        // Number memory reads each version once, with the last day that had it.
        val spans = tm.peopleForMemory()
        assertEquals(old[2].timestamp, spans.newest)
        assertEquals(old[1].timestamp, spans.spans.single { it.key == "bo" }.lastSeen)

        // A new instance (a restart) reads the new index; nothing moves twice.
        val again = TimeMachine(app, ContactRecordStore(app))
        assertEquals(old.map { it.timestamp }, again.snapshotTimes())
        assertEquals(listOf(ana1, ana2), again.history("ana").map { it.record })
    }

    @Test fun cleanupKeepsTheBlobsKeptSnapshotsNeedWithoutOpeningRecords() = runBlocking {
        val now = System.currentTimeMillis()
        val pic = ByteArray(300) { (it * 7).toByte() }
        val old = oldSnapshots(
            now - 3 * day to listOf(person("ana", "Ana", "+44 7700 900001", pic), person("bo", "Bo", "+44 7700 900003")),
            now - day to listOf(person("ana", "Ana", "+44 7700 900001", pic)),
        )
        val tm = TimeMachine(app, ContactRecordStore(app))
        val blobs = { File(root, "blobs").walkTopDown().filter { it.isFile }.map { it.name }.toSet() }
        assertEquals(3, blobs().size) // ana, her photo, bo
        assertEquals(1, tm.clear(SnapshotKeep.LATEST))
        assertEquals(listOf(old[1].timestamp), tm.snapshotTimes())
        assertEquals("bo's record went; ana's and her photo stay", 2, blobs().size)
        assertEquals(old[1], tm.snapshotAt(old[1].timestamp))

        tm.purge("ana")
        assertTrue(blobs().isEmpty())
        assertNull(tm.lastVersions(listOf("ana"))["ana"])
    }

    @Test fun aDayWithoutChangesMovesTheLastSnapshotForward() = runBlocking {
        val tm = TimeMachine(app, ContactRecordStore(app))
        assertTrue(tm.snapshotIfDue(minIntervalMs = 0))
        val first = tm.snapshotTimes().single()
        Thread.sleep(5)
        assertTrue(tm.snapshotIfDue(minIntervalMs = 0))
        val times = tm.snapshotTimes()
        assertEquals(1, times.size)
        assertTrue(times.single() > first)
    }

    @Test fun aDamagedIndexIsSetAsideRebuiltFromTheStoredVersionsAndNothingIsDeleted() = runBlocking {
        val now = System.currentTimeMillis()
        val ana1 = person("ana", "Ana", "+44 7700 900001")
        val ana2 = person("ana", "Ana Lee", "+44 7700 900002")
        val bo = person("bo", "Bo", "+44 7700 900003")
        val old = oldSnapshots(now - 3 * day to listOf(ana1, bo), now - day to listOf(ana2, bo))
        TimeMachine(app, ContactRecordStore(app)).snapshotTimes()
        // Each version's file is dated by when it was first stored.
        fun blob(hash: String) = File(File(File(root, "blobs"), hash.take(2)), hash)
        blob(old[0].contacts.getValue("ana")).setLastModified(now - 3 * day)
        blob(old[0].contacts.getValue("bo")).setLastModified(now - 3 * day)
        blob(old[1].contacts.getValue("ana")).setLastModified(now - day)
        val blobs = { File(root, "blobs").walkTopDown().filter { it.isFile }.map { it.name }.toSet() }
        val stored = blobs()

        File(root, "versions.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val tm = TimeMachine(app, ContactRecordStore(app))
        assertEquals("both versions of Ana are still there, in order", listOf(ana1, ana2), tm.history("ana").map { it.record })
        assertEquals(listOf(bo), tm.history("bo").map { it.record })
        assertEquals(2, tm.snapshotTimes().size)
        assertTrue("the damaged index is kept aside", root.listFiles().orEmpty().any { it.name.startsWith("versions.bin.damaged-") })
        // A clean-up right after may drop snapshots from the index, never the stored versions.
        tm.clear(SnapshotKeep.LATEST)
        tm.purge("bo")
        assertEquals(stored, blobs())
    }
}
