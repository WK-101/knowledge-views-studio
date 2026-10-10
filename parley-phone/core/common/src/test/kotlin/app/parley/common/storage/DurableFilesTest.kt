package app.parley.common.storage

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class DurableFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        DurableFiles.disk = DurableFiles.RealDisk
    }

    @After fun tearDown() {
        DurableFiles.disk = DurableFiles.RealDisk
        DurableFiles.report = { _, _ -> }
    }

    @Test fun writes_and_replaces_on_the_real_disk() {
        val f = File(tmp.root, "a/b/state.json")
        assertTrue(DurableFiles.writeText(f, "one"))
        assertTrue(DurableFiles.writeText(f, "two"))
        assertEquals("two", f.readText())
        assertFalse(File(f.parentFile, "state.json.tmp").exists())
    }

    @Test fun a_body_that_closes_its_stream_is_still_synced_and_kept() {
        val f = File(tmp.root, "z.bin")
        assertTrue(DurableFiles.write(f) { out -> java.io.DataOutputStream(out).use { it.writeInt(7) } })
        assertEquals(4, f.length())
    }

    /** Each step that can fail leaves the old file whole, removes the temporary file and reports once. */
    @Test fun every_failure_keeps_the_old_file() {
        for (step in Step.entries.filter { it != Step.SYNC_DIR }) {
            val f = File(tmp.root, "keys-$step")
            f.writeText("old")
            var reports = 0
            DurableFiles.report = { _, _ -> reports++ }
            DurableFiles.disk = Failing(step)
            assertFalse(DurableFiles.writeText(f, "new"))
            assertEquals("old file after a failure at $step", "old", f.readText())
            assertFalse(File(tmp.root, "keys-$step.tmp").exists())
            assertEquals(1, reports)
            DurableFiles.disk = DurableFiles.RealDisk
        }
    }

    @Test fun a_folder_that_cant_be_synced_still_counts_as_written() {
        val f = File(tmp.root, "dir-sync")
        DurableFiles.disk = Failing(Step.SYNC_DIR)
        assertTrue(DurableFiles.writeText(f, "new"))
        assertEquals("new", f.readText())
    }

    @Test fun writeOrThrow_throws_and_keeps_the_old_file() {
        val f = File(tmp.root, "archive.rec")
        f.writeText("old")
        DurableFiles.disk = Failing(Step.RENAME)
        try {
            DurableFiles.writeOrThrow(f, "new".toByteArray())
            fail("expected a failure")
        } catch (_: IOException) {
        }
        assertEquals("old", f.readText())
    }

    @Test fun data_is_synced_before_the_rename_and_the_folder_after() {
        val rec = Recording()
        DurableFiles.disk = rec
        assertTrue(DurableFiles.writeText(File(tmp.root, "pin"), "x"))
        assertEquals(listOf("writeSynced pin.tmp", "rename pin.tmp>pin", "syncDir"), rec.calls)
    }

    @Test fun move_never_replaces_unless_asked() {
        val a = File(tmp.root, "a").apply { writeText("a") }
        val b = File(tmp.root, "b").apply { writeText("b") }
        assertFalse(DurableFiles.move(a, b))
        assertEquals("b", b.readText())
        assertTrue(DurableFiles.move(a, b, replace = true))
        assertEquals("a", b.readText())
    }

    /**
     * Power is cut after each step of a write, on a disk that loses whatever wasn't synced (ext4 or f2fs without the
     * replace-on-rename heuristics). The file is always the old one or the new one, whole; never empty.
     */
    @Test fun a_power_cut_at_any_point_leaves_old_or_new_never_empty() {
        for (cutAfter in 0..3) {
            val disk = PowerCutDisk(cutAfter)
            disk.seed("records.keys", "old".toByteArray())
            DurableFiles.disk = disk
            DurableFiles.write(File(tmp.root, "records.keys"), "new-key".toByteArray())
            val after = disk.afterPowerCut()["records.keys"]?.let { String(it) }
            assertTrue("cut after $cutAfter gave $after", after == "old" || after == "new-key")
        }
        // The model is meaningful: the same writes without the syncs end empty.
        val naive = PowerCutDisk(cutAfter = 2, honourSyncs = false)
        naive.seed("records.keys", "old".toByteArray())
        DurableFiles.disk = naive
        DurableFiles.write(File(tmp.root, "records.keys"), "new-key".toByteArray())
        assertArrayEquals(ByteArray(0), naive.afterPowerCut()["records.keys"])
    }

    private enum class Step { BODY, SYNC, RENAME, SYNC_DIR }

    private class Failing(val step: Step) : DurableFiles.Disk {
        override fun writeSynced(file: File, body: (OutputStream) -> Unit) {
            if (step == Step.BODY) {
                file.outputStream().use { out -> out.write("ne".toByteArray()) }
                throw IOException("disk full")
            }
            DurableFiles.RealDisk.writeSynced(file) { out -> body(out) }
            if (step == Step.SYNC) throw IOException("sync failed")
        }

        override fun sync(file: File) = if (step == Step.SYNC) throw IOException("sync failed") else DurableFiles.RealDisk.sync(file)

        override fun rename(from: File, to: File, replace: Boolean) =
            if (step == Step.RENAME) throw IOException("rename failed") else DurableFiles.RealDisk.rename(from, to, replace)

        override fun syncDir(dir: File) = if (step == Step.SYNC_DIR) throw IOException("not allowed") else DurableFiles.RealDisk.syncDir(dir)
    }

    private class Recording : DurableFiles.Disk {
        val calls = mutableListOf<String>()
        override fun writeSynced(file: File, body: (OutputStream) -> Unit) {
            calls += "writeSynced ${file.name}"
            DurableFiles.RealDisk.writeSynced(file, body)
        }
        override fun sync(file: File) {
            calls += "sync ${file.name}"
        }
        override fun rename(from: File, to: File, replace: Boolean) {
            calls += "rename ${from.name}>${to.name}"
            DurableFiles.RealDisk.rename(from, to, replace)
        }
        override fun syncDir(dir: File) {
            calls += "syncDir"
        }
    }

    /**
     * An in-memory disk with a page cache: a file's data reaches the platter only when synced, a folder's entries only
     * when the folder is synced (a rename's new name points at whatever data the platter holds for it). After
     * [cutAfter] steps the power goes: later steps do nothing.
     */
    private class PowerCutDisk(val cutAfter: Int, val honourSyncs: Boolean = true) : DurableFiles.Disk {
        private class Node(var cached: ByteArray, var platter: ByteArray)

        private val cachedNames = mutableMapOf<String, Node>()
        private var platterNames = mapOf<String, Node>()
        private var steps = 0

        fun seed(name: String, bytes: ByteArray) {
            cachedNames[name] = Node(bytes, bytes)
            platterNames = cachedNames.toMap()
        }

        private fun step(): Boolean = (steps++ < cutAfter)

        override fun writeSynced(file: File, body: (OutputStream) -> Unit) {
            val out = ByteArrayOutputStream().also(body).toByteArray()
            if (!step()) return
            // Written into the cache, then (if syncs are honoured) to the platter.
            cachedNames[file.name] = Node(out, if (honourSyncs) out else ByteArray(0))
        }

        override fun sync(file: File) {
            if (!step()) return
            if (honourSyncs) cachedNames[file.name]?.let { it.platter = it.cached }
        }

        override fun rename(from: File, to: File, replace: Boolean) {
            if (!step()) return
            val n = cachedNames.remove(from.name) ?: throw IOException("missing")
            cachedNames[to.name] = n
            // Without a folder sync the rename may still reach the disk on its own (the journal commits): model the
            // worst case for the data, the new name pointing at unsynced data.
            platterNames = cachedNames.toMap()
        }

        override fun syncDir(dir: File) {
            if (!step()) return
            platterNames = cachedNames.toMap()
        }

        fun afterPowerCut(): Map<String, ByteArray> = platterNames.mapValues { it.value.platter }
    }
}
