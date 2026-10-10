package app.parley.common.backup

import app.parley.common.backup.Fixtures.contact
import app.parley.common.backup.Fixtures.phone
import app.parley.common.backup.Fixtures.photo
import app.parley.common.record.Mime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * A backup with thousands of photos (PERFORMANCE B2): the writer and the reader keep them in a sealed spool file, not in
 * memory, so the heap they hold doesn't grow with the photos, and the reader's in-memory cap no longer turns a large
 * backup into one that can't be restored. Tiny photos keep it fast; there are many of them.
 */
class LargeBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val count = 3_000
    private val photoBytes = 4 * 1024

    private fun photoOf(i: Int) = Random(i).nextBytes(photoBytes)

    private fun contacts() = (0 until count).asSequence().map { i -> contact("k$i", "Person $i", phone("+4420794600%02d".format(i % 100)), photo(photoOf(i))) }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(20) }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test fun three_thousand_photos_are_written_and_restored_without_being_held() {
        val spool = tmp.newFolder("spool")
        val file = File(tmp.root, "backup.zip")
        val total = count.toLong() * photoBytes
        val writer = BackupArchiveWriter(file.outputStream().buffered(), ArchiveMeta(1, "test"), spool)
        val before = usedHeap()
        writer.writeContacts(contacts())
        val held = usedHeap() - before
        println("Writer: ${total / 1024} KiB of photos, heap held ${held / 1024} KiB")
        assertTrue("the writer held $held bytes for $total bytes of photos", held < total / 3)
        assertTrue("no spool file is left in the folder", spool.listFiles().orEmpty().isEmpty())
        writer.close()

        // An in-memory cap far below the photos' size: spooled, they don't count towards it.
        val limits = ArchiveLimits(maxInMemoryTotalBytes = 1L shl 20)
        val readBefore = usedHeap()
        val reader = BackupArchiveReader.open({ file.inputStream().buffered() }, limits, spoolDir = spool)
        val readHeld = usedHeap() - readBefore
        println("Reader: heap held ${readHeld / 1024} KiB after verifying")
        assertTrue("the reader held $readHeld bytes", readHeld < total / 3)
        assertEquals(count.toLong(), reader.manifest.counts[BackupArchive.Counts.PHOTOS])
        val restored = reader.contacts { seq -> seq.map { r -> r.key to r.raws.single().rows.single { it.mimeType == Mime.PHOTO }.blob }.toList() }
        assertEquals(count, restored.size)
        restored.forEach { (key, blob) -> assertArrayEquals(key, photoOf(key.removePrefix("k").toInt()), blob) }
        reader.close()
    }

    @Test fun without_a_spool_the_in_memory_cap_still_holds() {
        val file = File(tmp.root, "small-cap.zip")
        BackupArchiveWriter(file.outputStream().buffered(), ArchiveMeta(1, "test")).use { w -> w.writeContacts(contacts().take(400)) }
        try {
            BackupArchiveReader.open({ file.inputStream().buffered() }, ArchiveLimits(maxInMemoryTotalBytes = 1L shl 20))
            fail("expected the in-memory cap")
        } catch (_: BackupIntegrityException) {
        }
        // The same backup opens with a spool folder.
        BackupArchiveReader.open({ file.inputStream().buffered() }, ArchiveLimits(maxInMemoryTotalBytes = 1L shl 20), tmp.newFolder("s")).use { r ->
            assertEquals(400L, r.manifest.counts[BackupArchive.Counts.PHOTOS])
        }
    }

    @Test fun a_spooled_backup_is_the_same_archive_as_one_held_in_memory() {
        val a = File(tmp.root, "a.zip")
        val b = File(tmp.root, "b.zip")
        BackupArchiveWriter(a.outputStream(), ArchiveMeta(1, "test")).use { it.writeContacts(contacts().take(200)) }
        BackupArchiveWriter(b.outputStream(), ArchiveMeta(1, "test"), tmp.newFolder("s2")).use { it.writeContacts(contacts().take(200)) }
        assertArrayEquals(a.readBytes(), b.readBytes())
        // And the hash-only writer agrees without keeping any bytes.
        val m = BackupArchiveReader.open(a.readBytes()).manifest
        assertEquals(m.contentHash(), BackupArchive.contentHash { writeContacts(contacts().take(200)) })
    }

    @Test fun call_history_is_written_line_by_line() {
        val f = File(tmp.root, "calls.zip")
        BackupArchiveWriter(f.outputStream(), ArchiveMeta(1, "test")).use { w ->
            val s = w.callHistory()
            repeat(5_000) { s.add(CallHistoryLine(call = CallLogRecord("+1555$it", it.toLong(), 1, 1))) }
            s.end()
        }
        val r = BackupArchiveReader.open(f.readBytes())
        assertEquals(5_000, r.callHistory { it.count() })
        // No line: no entry, as before.
        val g = File(tmp.root, "none.zip")
        BackupArchiveWriter(g.outputStream(), ArchiveMeta(1, "test")).use { w -> w.callHistory().end() }
        assertEquals(null, BackupArchiveReader.open(g.readBytes()).callHistory { it.count() })
    }
}
