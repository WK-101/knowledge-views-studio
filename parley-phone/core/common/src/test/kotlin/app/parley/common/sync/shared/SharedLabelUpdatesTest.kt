package app.parley.common.sync.shared

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.KdfParams
import app.parley.common.crypto.Aead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

class SharedLabelUpdatesTest {
    private val cheap = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
    private val label = SharedLabelFiles.newId()
    private val ana = TestSigner()
    private val key = SharedLabelCrypto.newHeader(label, 2, "family pass".toCharArray(), cheap).second
    private val sid = SharedLabelFiles.newId()

    private fun files() = mapOf(
        SharedLabelCrypto.HEADER_NAME to byteArrayOf(1, 2, 3),
        SharedLabelFiles.cardName(sid) to "card".toByteArray(),
        SharedLabelFiles.journalName(ana.publicKey) to "journal".toByteArray(),
        SharedLabelCrypto.headerSigName(1) to "sig".toByteArray(),
    )

    @Test fun an_update_round_trips_sealed_and_signed() {
        val bytes = SharedLabelUpdates.write(ana, key, label, 2, "Ana", 5_000, files())!!
        assertTrue(SharedLabelUpdates.looksLikeUpdate(bytes))
        val peek = SharedLabelUpdates.peek(bytes)!!
        assertEquals(label, peek.labelId)
        assertEquals(2, peek.epoch)
        val u = (SharedLabelUpdates.open(bytes, key, label, 2) as SharedLabelUpdates.Opened.Ok).update
        assertEquals(ana.hex, u.fromHex)
        assertEquals("Ana", u.fromName)
        assertEquals(5_000, u.sentAt)
        assertEquals(files().keys, u.files.keys)
        assertArrayEquals("card".toByteArray(), u.files.getValue(SharedLabelFiles.cardName(sid)))
        // Nothing of the contents is readable in the file.
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("journal"))
    }

    @Test fun another_key_label_or_epoch_does_not_open_it() {
        val bytes = SharedLabelUpdates.write(ana, key, label, 2, "Ana", 5_000, files())!!
        val other = SharedLabelCrypto.newHeader(label, 2, "another pass".toCharArray(), cheap).second
        assertEquals(SharedLabelUpdates.Opened.WrongKey, SharedLabelUpdates.open(bytes, other, label, 2))
        assertEquals(SharedLabelUpdates.Opened.WrongKey, SharedLabelUpdates.open(bytes, key, SharedLabelFiles.newId(), 2))
        assertEquals(SharedLabelUpdates.Opened.WrongKey, SharedLabelUpdates.open(bytes, key, label, 3))
        // One byte changed anywhere in the sealed part: refused whole.
        val flipped = bytes.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertEquals(SharedLabelUpdates.Opened.WrongKey, SharedLabelUpdates.open(flipped, key, label, 2))
        assertEquals(SharedLabelUpdates.Opened.NotAnUpdate, SharedLabelUpdates.open("PARLEYL1 a folder file".toByteArray(), key, label, 2))
        assertNull(SharedLabelUpdates.peek("hello".toByteArray()))
    }

    /** Seals [signed] (the signed JSON) as an update file would, so tests can try what a writer with the key could. */
    private fun sealRaw(signed: String): ByteArray {
        val plain = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(signed.toByteArray()) } }.toByteArray()
        val nonce = ByteArray(12) { it.toByte() }
        val aad = "PARLEYU1|$label|2".toByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write("PARLEYU1".toByteArray()); writeByte(label.length); write(label.toByteArray()); writeInt(2); write(nonce)
            write(Aead.encrypt(key, nonce, plain, aad))
        }
        return out.toByteArray()
    }

    @Test fun a_body_changed_after_signing_or_a_strange_file_name_is_refused() {
        val bytes = SharedLabelUpdates.write(ana, key, label, 2, "Ana", 5_000, files())!!
        val start = SharedLabelUpdates.peek(bytes)!!.sealedAt
        val plain = Aead.decrypt(key, bytes.copyOfRange(start, start + 12), bytes.copyOfRange(start + 12, bytes.size), "PARLEYU1|$label|2".toByteArray())
        val signed = String(java.util.zip.GZIPInputStream(plain.inputStream()).readBytes())
        // Someone with the label's key changes the time (to win over later updates): the signature no longer holds.
        assertEquals(SharedLabelUpdates.Opened.Damaged, SharedLabelUpdates.open(sealRaw(signed.replace("\\\"at\\\":5000", "\\\"at\\\":9000")), key, label, 2))
        assertEquals(SharedLabelUpdates.Opened.Ok::class, SharedLabelUpdates.open(sealRaw(signed), key, label, 2)::class)
        // A name outside a label's files (a path, anything else) can't be written: the update is refused whole.
        val traversal = runCatching { SharedLabelUpdates.write(ana, key, label, 2, "Ana", 5_000, mapOf("../x" to byteArrayOf(1))) }
        assertTrue(traversal.isFailure)
        assertFalse(SharedLabelUpdates.isLabelFile("../.parley-label"))
        assertFalse(SharedLabelUpdates.isLabelFile("c-${sid}.plabel.tmp"))
        assertFalse(SharedLabelUpdates.isLabelFile("j-../../x.plabel"))
    }

    @Test fun an_update_opened_before_or_an_older_one_is_a_copy_put_back() {
        assertTrue(SharedLabelUpdates.fresh(5_000, null, 5_000))
        assertTrue(SharedLabelUpdates.fresh(6_000, 5_000, 6_000))
        assertFalse(SharedLabelUpdates.fresh(5_000, 5_000, 9_000))
        assertFalse(SharedLabelUpdates.fresh(4_000, 5_000, 9_000))
        // Far ahead of this phone's clock: not believed (it would shut out the sender's real updates).
        assertFalse(SharedLabelUpdates.fresh(5_000 + 2 * SharedLabelUpdates.MAX_AHEAD_MS, null, 5_000))
    }

    private fun card(version: Long, parent: Long?, signer: MemberSigner = ana): CardFile {
        val bytes = SharedLabelFiles.writeCard(signer, label, sid, version, version, "{}", parent)!!
        return SharedLabelFiles.readCard(label, SharedLabelFiles.cardName(sid), bytes)!!
    }

    @Test fun a_newer_file_or_an_edit_made_alongside_is_taken_and_only_a_newer_one_stays() {
        val mine = card(20, parent = 10)
        // Newer: seen and kept.
        assertTrue(SharedLabelUpdates.takesCard(card(30, 20), mine))
        assertTrue(SharedLabelUpdates.keepsCard(card(30, 20), mine))
        // Older, from the same version as mine: seen (merged by the run) but the folder keeps mine.
        assertTrue(SharedLabelUpdates.takesCard(card(15, 10), mine))
        assertFalse(SharedLabelUpdates.keepsCard(card(15, 10), mine))
        // Older from something else, or the same version: a copy put back, left out.
        assertFalse(SharedLabelUpdates.takesCard(card(15, 5), mine))
        assertFalse(SharedLabelUpdates.takesCard(card(20, 10), mine))
        assertTrue(SharedLabelUpdates.takesCard(card(5, null), null))
    }

    @Test fun the_newer_copy_of_a_journal_and_header_wins() {
        fun journal(epoch: Int, at: Long, lastId: Long) =
            Journal(ana.publicKey, "Ana", epoch, null, emptyList(), false, (1..lastId).map { JournalEntry(it, sid, ChangeKind.EDITED, emptySet(), "Ada", it) }, at = at)
        assertTrue(SharedLabelUpdates.takesJournal(journal(1, 200, 3), journal(1, 100, 3)))
        assertFalse(SharedLabelUpdates.takesJournal(journal(1, 100, 3), journal(1, 200, 3)))
        assertTrue(SharedLabelUpdates.takesJournal(journal(2, 50, 1), journal(1, 200, 3)))
        // Journals from before their time was recorded: more changes is newer.
        assertTrue(SharedLabelUpdates.takesJournal(journal(1, 0, 4), journal(1, 0, 3)))
        assertTrue(SharedLabelUpdates.takesJournal(journal(1, 0, 1), null))
        assertTrue(SharedLabelUpdates.takesHeader(2, null))
        assertTrue(SharedLabelUpdates.takesHeader(3, 2))
        assertFalse(SharedLabelUpdates.takesHeader(2, 2))
        assertFalse(SharedLabelUpdates.takesHeader(1, 2))
    }

    @Test fun a_contact_file_keeps_its_parent_and_older_readers_files_have_none() {
        val f = card(20, parent = 10)
        assertEquals(10L, f.parent)
        assertNull(card(20, parent = null).parent)
        // A parent that isn't older than the version is a forged or broken file.
        val bad = SharedLabelFiles.writeCard(ana, label, sid, 20, 20, "{}", 30)!!
        assertNull(SharedLabelFiles.readCard(label, SharedLabelFiles.cardName(sid), bad))
    }

    @Test fun a_journal_keeps_when_it_was_written() {
        val name = SharedLabelFiles.journalName(ana.publicKey)
        val j = Journal(ana.publicKey, "Ana", 1, null, emptyList(), false, emptyList(), at = 1234)
        assertEquals(1234, SharedLabelFiles.readJournal(label, name, SharedLabelFiles.writeJournal(ana, label, j)!!)!!.at)
        val old = Journal(ana.publicKey, "Ana", 1, null, emptyList(), false, emptyList())
        assertEquals(0, SharedLabelFiles.readJournal(label, name, SharedLabelFiles.writeJournal(ana, label, old)!!)!!.at)
    }
}
