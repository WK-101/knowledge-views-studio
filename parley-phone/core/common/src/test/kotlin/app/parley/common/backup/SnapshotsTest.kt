package app.parley.common.backup

import app.parley.common.backup.Fixtures.contact
import app.parley.common.backup.Fixtures.email
import app.parley.common.backup.Fixtures.phone
import app.parley.common.backup.Fixtures.photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SnapshotsTest {
    private val pic = ByteArray(2000) { it.toByte() }
    private val mum1 = contact("mum", "Jane Doe", phone("+44 7700 900001"), photo(pic))
    private val mum2 = contact("mum", "Jane Doe", phone("+44 7700 900002"), photo(pic))
    private val dad = contact("dad", "John Doe", phone("+44 7700 900003"))
    private val bob = contact("bob", "Bob Smith", email("bob@x.com"))
    private val carol = contact("carol", "Carol Jones", starred = true)

    @Test fun incrementalSnapshotsDeduplicate() {
        val store = InMemoryBlobStore()
        val w = SnapshotWriter(store)
        val s1 = w.write(100, listOf(mum1, dad, bob))
        assertEquals(4, s1.newBlobs) // 3 contacts + 1 photo
        val s2 = w.write(200, listOf(mum1, dad, bob))
        assertEquals(0, s2.newBlobs)
        assertEquals(s1.index.contacts, s2.index.contacts)
        val s3 = w.write(300, listOf(mum2, dad, carol))
        assertEquals(2, s3.newBlobs) // changed mum + new carol; photo reused
        assertEquals(6, store.size)
        assertEquals(dad, Snapshots.load(store, s3.index.contacts.getValue("dad")))
        assertEquals(mum1, Snapshots.load(store, s1.index.contacts.getValue("mum")))
    }

    @Test fun diffReportsAddedRemovedAndRowChanges() {
        val store = InMemoryBlobStore()
        val w = SnapshotWriter(store)
        val a = w.write(100, listOf(mum1, dad, bob)).index
        val dad2 = dad.copy(starred = true)
        val b = w.write(200, listOf(mum2, dad2, carol)).index
        val d = Snapshots.diff(store, a, b)
        assertEquals(listOf(carol), d.added)
        assertEquals(listOf(bob), d.removed)
        assertEquals(listOf("dad", "mum"), d.changed.map { it.key })
        val mum = d.changed.first { it.key == "mum" }
        assertTrue(mum.fields.isEmpty())
        assertEquals(listOf(phone("+44 7700 900002")), mum.addedRows)
        assertEquals(listOf(phone("+44 7700 900001")), mum.removedRows)
        val dd = d.changed.first { it.key == "dad" }
        assertEquals(listOf("starred"), dd.fields)
        assertTrue(dd.addedRows.isEmpty() && dd.removedRows.isEmpty())
        assertTrue(Snapshots.diff(store, a, a).isEmpty)
    }

    @Test fun rowReorderIsNotAChange() {
        val c1 = contact("k", "A B", phone("1"), phone("2"))
        val c2 = c1.copy(raws = c1.raws.map { it.copy(rows = it.rows.reversed()) })
        val ch = Snapshots.diffRecords(c1, c2)
        assertTrue(ch.addedRows.isEmpty() && ch.removedRows.isEmpty() && ch.fields.isEmpty())
    }

    @Test fun historyCollapsesUnchangedAndShowsDeletion() {
        val store = InMemoryBlobStore()
        val w = SnapshotWriter(store)
        val snaps = listOf(
            w.write(100, listOf(dad)).index, // mum absent before she was added: skipped
            w.write(200, listOf(mum1, dad)).index,
            w.write(300, listOf(mum1, dad)).index,
            w.write(400, listOf(mum2, dad)).index,
            w.write(500, listOf(dad)).index,
            w.write(600, listOf(mum2, dad)).index,
        ).shuffled()
        val h = Snapshots.history(store, snaps, "mum")
        assertEquals(listOf(200L, 400L, 500L, 600L), h.map { it.timestamp })
        assertEquals(mum1, h[0].record)
        assertEquals(mum2, h[1].record)
        assertNull(h[2].record)
        assertEquals(mum2, h[3].record)
        assertTrue(Snapshots.history(store, snaps, "nobody").isEmpty())
    }

    @Test fun corruptedBlobIsDetected() {
        val store = InMemoryBlobStore()
        val idx = SnapshotWriter(store).write(1, listOf(dad)).index
        val h = idx.contacts.getValue("dad")
        val evil = object : BlobStore by store {
            override fun get(hash: String) = store.get(hash)?.also { it[5] = 'X'.code.toByte() }
        }
        try {
            Snapshots.load(evil, h); fail()
        } catch (_: BackupIntegrityException) {}
        try {
            Snapshots.load(InMemoryBlobStore(), h); fail()
        } catch (_: BackupIntegrityException) {}
    }

    @Test fun indexSerializes() {
        val idx = SnapshotWriter(InMemoryBlobStore()).write(42, listOf(dad, bob)).index
        assertEquals(idx, SnapshotIndex.fromBytes(idx.toBytes()))
        assertEquals(listOf("bob", "dad"), idx.contacts.keys.toList())
        try {
            SnapshotIndex.fromBytes("{".toByteArray()); fail()
        } catch (_: BackupIntegrityException) {}
    }

    @Test fun duplicateKeysRejected() {
        try {
            SnapshotWriter(InMemoryBlobStore()).write(1, listOf(dad, dad)); fail()
        } catch (_: IllegalArgumentException) {}
    }
}
