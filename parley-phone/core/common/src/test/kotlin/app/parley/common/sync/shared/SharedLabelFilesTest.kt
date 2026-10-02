package app.parley.common.sync.shared

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.BackupIntegrityException
import app.parley.common.backup.KdfParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLabelFilesTest {
    private val cheap = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
    private val label = SharedLabelFiles.newId()
    private val ana = TestSigner()
    private val sid = SharedLabelFiles.newId()

    @Test fun the_header_opens_with_the_passphrase_and_names_label_and_epoch() {
        val (header, key) = SharedLabelCrypto.newHeader(label, 3, "family pass".toCharArray(), cheap)
        val h = SharedLabelCrypto.parseHeader(header)
        assertEquals(label, h.labelId)
        assertEquals(3, h.epoch)
        assertArrayEquals(key, SharedLabelCrypto.unlock(h, "family pass".toCharArray()))
        assertNull(SharedLabelCrypto.unlock(h, "wrong".toCharArray()))
        assertTrue(SharedLabelCrypto.opens(h, key))
        // A new key (a member removed) is a different header the old key doesn't open.
        val (next, _) = SharedLabelCrypto.newHeader(label, 4, "family pass".toCharArray(), cheap)
        assertFalse(SharedLabelCrypto.opens(SharedLabelCrypto.parseHeader(next), key))
    }

    @Test(expected = BackupIntegrityException::class)
    fun a_header_that_is_not_one_is_refused() {
        SharedLabelCrypto.parseHeader("PARLEYS1 something else".toByteArray())
    }

    @Test fun files_are_bound_to_their_label_and_name() {
        val (_, key) = SharedLabelCrypto.newHeader(label, 1, "pass".toCharArray(), cheap)
        val sealed = SharedLabelCrypto.seal(key, label, "c-$sid.plabel", "body".toByteArray())
        assertEquals("body", String(SharedLabelCrypto.open(key, label, "c-$sid.plabel", sealed)!!))
        assertNull(SharedLabelCrypto.open(key, label, "c-other.plabel", sealed))
        assertNull(SharedLabelCrypto.open(key, SharedLabelFiles.newId(), "c-$sid.plabel", sealed))
        assertNull(SharedLabelCrypto.open(ByteArray(32), label, "c-$sid.plabel", sealed))
    }

    @Test fun a_signed_card_reads_back_and_any_change_breaks_it() {
        val card = SharedCards.encode(SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000"))))
        val name = SharedLabelFiles.cardName(sid)
        val bytes = SharedLabelFiles.writeCard(ana, label, sid, 5, 1000, card)!!
        val f = SharedLabelFiles.readCard(label, name, bytes)
        assertNotNull(f)
        assertEquals(5, f!!.version)
        assertEquals(ana.hex, f.authorHex)
        assertFalse(f.deleted)
        assertEquals(card, f.card)
        // Another name, another label, or an edited body: refused.
        assertNull(SharedLabelFiles.readCard(label, SharedLabelFiles.cardName(SharedLabelFiles.newId()), bytes))
        assertNull(SharedLabelFiles.readCard(SharedLabelFiles.newId(), name, bytes))
        val forged = String(bytes).replace("\\\"v\\\":5", "\\\"v\\\":9").toByteArray()
        assertNull(SharedLabelFiles.readCard(label, name, forged))
    }

    @Test fun a_tombstone_has_no_card() {
        val f = SharedLabelFiles.readCard(label, SharedLabelFiles.cardName(sid), SharedLabelFiles.writeCard(ana, label, sid, 6, 1000, null)!!)!!
        assertTrue(f.deleted)
        assertNull(f.card)
    }

    @Test fun a_signer_that_cannot_sign_writes_nothing() {
        ana.refuse = true
        assertNull(SharedLabelFiles.writeCard(ana, label, sid, 1, 1, null))
    }

    @Test fun journals_round_trip_and_must_be_signed_by_their_member() {
        val ticket = SharedLabelFiles.ticket(ana, label, 1, SharedLabelFiles.newId())!!
        val sam = TestSigner()
        val j = Journal(
            sam.publicKey, "Sam", 1, ticket, emptyList(), left = false,
            entries = listOf(JournalEntry(1, sid, ChangeKind.EDITED, setOf(CardField.PHONES), "Dr Lee", 2000)),
        )
        val name = SharedLabelFiles.journalName(sam.publicKey)
        val back = SharedLabelFiles.readJournal(label, name, SharedLabelFiles.writeJournal(sam, label, j)!!)!!
        assertEquals("Sam", back.name)
        assertEquals(setOf(CardField.PHONES), back.entries.single().fields)
        assertTrue(SharedLabelFiles.verifyTicket(back.ticket!!, label))
        // Under another member's name: refused.
        assertNull(SharedLabelFiles.readJournal(label, SharedLabelFiles.journalName(ana.publicKey), SharedLabelFiles.writeJournal(sam, label, j)!!))
    }

    @Test fun names_are_recognised() {
        assertEquals(sid, SharedLabelFiles.sidOf(SharedLabelFiles.cardName(sid)))
        assertNull(SharedLabelFiles.sidOf("c-../x.plabel"))
        assertNull(SharedLabelFiles.sidOf(".parley-label"))
        assertTrue(SharedLabelFiles.isJournalName(SharedLabelFiles.journalName(ana.publicKey)))
    }
}
