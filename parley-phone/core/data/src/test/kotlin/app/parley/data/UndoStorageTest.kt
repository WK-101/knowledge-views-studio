package app.parley.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.CallType
import app.parley.common.backup.SnapshotIndex
import app.parley.common.backup.SnapshotKeep
import app.parley.common.testing.testCall
import app.parley.data.db.JournalEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Clearing History & undo's stores removes exactly the undo copies asked for, and leaves nothing of them behind. */
@RunWith(RobolectricTestRunner::class)
class UndoStorageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val day = 86_400_000L
    private val root get() = File(app.filesDir, "timemachine")

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        root.deleteRecursively()
        c = DataContainer(app)
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    @Test fun contactChangesGoOneOrAllAtOnce() = runBlocking {
        val first = c.meta.addJournal(JournalEntity(contactKey = "k1", displayName = "Ada", action = "DELETE", time = 1, payload = "a".toByteArray()))
        c.meta.addJournal(JournalEntity(contactKey = "k2", displayName = "Bo", action = "EDIT", time = 2, payload = "b".toByteArray()))
        val before = c.undoStorage.usage()
        assertEquals(2, before.contactChanges)
        assertTrue(before.contactBytes > 0)

        assertTrue(c.undoStorage.forgetContactChange(first))
        assertEquals(1, c.meta.journalCount())
        assertEquals(1, c.undoStorage.clearContactChanges())
        assertEquals(0, c.undoStorage.usage().contactChanges)
        assertEquals(0L, c.undoStorage.usage().contactBytes)
    }

    /**
     * Android's SQLite creates databases with auto_vacuum FULL, so pruned pages already go back at each commit; daily
     * upkeep then finds nothing to do and leaves the file as it is. (Which step a file in another mode gets is
     * [app.parley.common.storage.VacuumPolicy]'s, tested in core/common.)
     */
    @Test fun dailyUpkeepLeavesNoFreePages() = runBlocking {
        val db = c.db.openHelper.writableDatabase
        fun pragma(name: String) = db.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
        repeat(40) { i ->
            c.meta.addJournal(JournalEntity(contactKey = "k$i", displayName = "P$i", action = "EDIT", time = 1, payload = ByteArray(40_000) { i.toByte() }))
        }
        val full = pragma("page_count")
        c.meta.clearJournal()
        c.undoStorage.tidy()
        assertEquals(0L, pragma("freelist_count"))
        assertTrue(pragma("page_count") < full / 2)
    }

    @Test fun deletedCallsAreForgottenButNotTheCallLog() = runBlocking {
        fun call(id: Long) = testCall(id, "+15550100", null, CallType.INCOMING, 1_700_000_000_000L + id, 30)
        val batch = c.history.delete(listOf(call(1), call(2)))!!
        c.history.delete(listOf(call(3)))
        assertEquals(3, c.undoStorage.usage().deletedCalls)

        assertEquals(2, c.undoStorage.forgetDeletedCalls(batch))
        assertEquals(1, c.undoStorage.clearDeletedCalls())
        assertEquals(0, c.undoStorage.usage().deletedCalls)
        assertTrue(c.history.trashBatches().isEmpty())
    }

    @Test fun snapshotsKeepWhatWasAskedFor() = runBlocking {
        val now = System.currentTimeMillis()
        val index = File(root, "index").apply { mkdirs() }
        listOf(now - 40 * day, now - 10 * day, now - day).forEach { File(index, "$it.idx").writeBytes(SnapshotIndex(it, emptyMap()).toBytes()) }
        File(root, "blobs/ab").apply { mkdirs() }.resolve("ab12").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(3, c.undoStorage.usage().snapshots)

        assertEquals(1, c.undoStorage.clearSnapshots(SnapshotKeep.RECENT))
        assertEquals(listOf(now - 10 * day, now - day), c.timeMachine.snapshotTimes())
        assertEquals(1, c.undoStorage.clearSnapshots(SnapshotKeep.LATEST))
        assertEquals(listOf(now - day), c.timeMachine.snapshotTimes())
        assertEquals(1, c.undoStorage.clearSnapshots(SnapshotKeep.NONE))
        val after = c.undoStorage.usage()
        assertEquals(0, after.snapshots)
        assertEquals(0L, after.snapshotBytes)
    }
}
