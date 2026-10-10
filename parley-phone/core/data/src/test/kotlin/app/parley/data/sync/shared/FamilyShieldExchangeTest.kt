package app.parley.data.sync.shared

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.KdfParams
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.common.spam.Ed25519
import app.parley.common.sync.shared.MemberSigner
import app.parley.common.sync.shared.OwnVerdict
import app.parley.common.sync.shared.SharedLabelCrypto
import app.parley.common.sync.shared.SharedLabelFiles
import app.parley.common.sync.shared.ShieldKind
import app.parley.common.sync.shared.ShieldMode
import app.parley.data.sync.shared.SharedLabelEngine.UpdateResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The family spam shield through update files (docs/SHARED_LABELS.md, "Family spam shield"): Ana, Sam and Bob share
 * Family; verdicts travel hashed in their signed journals, merge on the phone that receives them, are withdrawn, go
 * when a member leaves or the shield is turned off, and are matched against calls in memory.
 */
@RunWith(RobolectricTestRunner::class)
class FamilyShieldExchangeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val cheap = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
    private var clock = 1_000_000L
    private val root = File(app.cacheDir, "shield-" + System.nanoTime())

    private class Signer : MemberSigner {
        val secret: ByteArray = Ed25519.newSecret()
        override val publicKey: ByteArray = Ed25519.publicKey(secret)
        val hex get() = SharedLabelFiles.keyHex(publicKey)

        override fun sign(message: ByteArray): ByteArray = Ed25519.sign(secret, message)
    }

    /** No Keystore in tests: states are kept as they are. */
    private object Plain : StateSealer {
        override fun seal(text: String) = text

        override fun open(text: String) = text
    }

    private inner class Phone(name: String) {
        val signer = Signer()
        val contacts = MemoryLabelContacts().apply { labels += "Family" }
        val files = LocalLabelFolder(File(root, name))
        var own: List<OwnVerdict> = emptyList()
        val engine get() = SharedLabelEngine(files, contacts, signer, own) { clock }
        lateinit var s: SharedLabelState

        suspend fun send(): ByteArray {
            s = engine.run(s).state
            clock += 60_000
            return engine.updateFile(s)!!
        }

        suspend fun open(bytes: ByteArray): UpdateResult {
            val out = engine.openUpdate(s, bytes)
            if (out.result == UpdateResult.MERGED) s = out.state
            clock += 60_000
            return out.result
        }

        fun shield(on: Boolean, mode: ShieldMode = ShieldMode.WARN) {
            s = s.copy(shieldOn = on, shieldMode = mode)
        }
    }

    private val ana = Phone("ana")
    private val sam = Phone("sam")
    private val bob = Phone("bob")

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun record(name: String, phone: String) = ContactRecord(
        "", name,
        raws = listOf(
            RawRecord(null, null, rows = listOf(DataRow(Mime.NAME, mapOf(Col.D1 to name)), DataRow(Mime.PHONE, mapOf(Col.D1 to phone, Col.D2 to "2")))),
        ),
    )

    private suspend fun shareByFile() {
        ana.contacts.import("Family", record("Ada", "+44 20 7946 0000"))
        ana.s = ana.engine.create("Family", "", "", "family passphrase".toCharArray(), "Ana", cheap)!!
        ana.s = ana.engine.run(ana.s).state
        for (p in listOf(sam, bob)) {
            p.s = p.engine.join(ana.engine.invitation(ana.s, "")!!, "", "", "Family", if (p === sam) "Sam" else "Bob", null)
            assertTrue(p.engine.introduce(p.s))
        }
        val first = ana.send()
        for (p in listOf(sam, bob)) {
            assertEquals(UpdateResult.MERGED, p.open(first))
            assertEquals(UpdateResult.MERGED, ana.open(p.send()))
        }
    }

    private fun index(vararg states: SharedLabelState) = FamilyShieldStore(File(root, "index"), Plain, { emptyList() }).apply { update(states.toList()) }

    private val scam = "+447700900123"
    private val spam = "+12025550143"

    @Suppress("LongMethod")
    @Test
    fun verdicts_travel_hashed_merge_and_go_away() = runBlocking {
        shareByFile()
        // Off by default: nothing is shared or kept.
        assertFalse(ana.s.shieldOn)
        ana.own = listOf(OwnVerdict(scam, ShieldKind.SCAM, 10), OwnVerdict(spam, ShieldKind.BLOCKED, 11))
        bob.own = listOf(OwnVerdict(scam, ShieldKind.BLOCKED, 12))
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        assertTrue(sam.s.shieldIn.isEmpty())

        // Ana and Bob turn it on; Sam too, to receive.
        ana.shield(true)
        bob.shield(true)
        sam.shield(true, ShieldMode.SILENCE)
        val fromAna = ana.send()
        assertEquals(UpdateResult.MERGED, bob.open(fromAna))
        val fromBob = bob.send()
        assertEquals(UpdateResult.MERGED, sam.open(fromBob))
        // Bob's update relays Ana's journal too.
        assertEquals(setOf(ana.signer.hex, bob.signer.hex), sam.s.shieldIn.keys)
        assertEquals(2, sam.s.shieldIn.getValue(ana.signer.hex).size)

        // Only hashes travel: no digits of either number in any journal Sam holds.
        sam.files.list()!!.keys.filter(SharedLabelFiles::isJournalName).forEach { name ->
            val body = String(SharedLabelCrypto.open(sam.s.key, sam.s.labelId, name, sam.files.read(name)!!)!!)
            assertFalse(body.contains("7700900123"))
            assertFalse(body.contains("2025550143"))
        }

        // Matched in memory: both said something about the scam number, the strongest wins, in Sam's mode.
        val idx = index(sam.s)
        val hit = idx.match("+44 7700 900123", "GB")!!
        assertEquals("Family", hit.label)
        assertEquals(ShieldKind.SCAM, hit.kind)
        assertEquals(2, hit.members)
        assertEquals(ShieldMode.SILENCE, hit.mode)
        assertEquals(1, idx.match("(202) 555-0143", "US")!!.members)
        assertNull(idx.match("+447700900999", "GB"))
        assertTrue(idx.mayMatch())

        // Ana withdraws the scam number: after her next update Sam's phone only has Bob's word for it.
        ana.own = listOf(OwnVerdict(spam, ShieldKind.BLOCKED, 11))
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        assertEquals(1, sam.s.shieldIn.getValue(ana.signer.hex).size)
        val after = index(sam.s).match(scam, "GB")!!
        assertEquals(1, after.members)
        assertEquals(ShieldKind.BLOCKED, after.kind)
        // Block mode needs two voices: Bob's word alone only warns; Ana shared the label (its anchor), so hers blocks.
        val blocking = sam.s.copy(shieldMode = ShieldMode.BLOCK)
        assertEquals(ShieldMode.WARN, index(blocking).match(scam, "GB")!!.mode)
        assertEquals(ShieldMode.BLOCK, index(blocking).match(spam, "US")!!.mode)

        // Bob leaves: his last update says so, and what he shared stops counting.
        assertTrue(bob.engine.leave(bob.s))
        assertEquals(UpdateResult.MERGED, sam.open(bob.engine.updateFile(bob.s)!!))
        assertFalse(bob.signer.hex in sam.s.shieldIn)
        assertNull(index(sam.s).match(scam, "GB"))

        // Sam turns it off: nothing is kept, nothing matches.
        sam.shield(false)
        sam.s = sam.engine.run(sam.s).state
        assertTrue(sam.s.shieldIn.isEmpty())
        assertNull(index(sam.s).match(spam, "US"))
        assertFalse(index(sam.s).mayMatch())
    }

    @Test fun a_label_left_on_this_phone_takes_its_verdicts_with_it() = runBlocking {
        shareByFile()
        ana.own = listOf(OwnVerdict(scam, ShieldKind.SCAM, 10))
        ana.shield(true)
        sam.shield(true)
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        val idx = index(sam.s)
        assertEquals(ShieldKind.SCAM, idx.match(scam, "GB")?.kind)
        // Leaving removes the label's state; the index is rebuilt from what is left.
        idx.update(emptyList())
        assertNull(idx.match(scam, "GB"))
    }

    /** Opens nothing until [ok]: the Keystore unavailable for a moment, in a process just started for a call. */
    private class Flaky : StateSealer {
        @Volatile var ok = false

        override fun seal(text: String) = text

        override fun open(text: String) = text.takeIf { ok }
    }

    @Test fun a_failed_read_is_tried_again_and_the_ring_path_reads_nothing() = runBlocking {
        shareByFile()
        ana.own = listOf(OwnVerdict(scam, ShieldKind.SCAM, 10))
        ana.shield(true)
        sam.shield(true)
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        val dir = File(root, "states")
        assertTrue(SharedLabelStateStore(dir, Plain).put(sam.s))
        val sealer = Flaky()
        val store = FamilyShieldStore(dir, sealer, { emptyList() })
        // Before anything was read: maybe (the call is screened), from memory.
        assertTrue(store.mayMatch())
        store.load()
        assertNull(store.match(scam, "GB"))
        assertTrue(store.mayMatch())
        // The next call reads again, and this time the state opens.
        sealer.ok = true
        store.load()
        assertEquals(ShieldKind.SCAM, store.match(scam, "GB")?.kind)
        // Nothing shared anywhere: once read, a call isn't screened for the shield.
        val none = FamilyShieldStore(File(root, "none"), Plain, { emptyList() })
        none.load()
        assertFalse(none.mayMatch())
    }

    @Test fun a_blocked_number_marked_then_withdrawn_is_not_shared_again() = runBlocking {
        val store = FamilyShieldStore(File(root, "own"), Plain, { listOf(scam) })
        assertEquals(listOf(scam), store.outgoing().map { it.e164 })
        assertTrue(store.mark(scam, "GB", ShieldKind.SCAM))
        assertTrue(store.withdraw(scam))
        assertTrue(store.outgoing().isEmpty())
        assertTrue(store.mine().isEmpty())
        assertTrue(store.outgoing().isEmpty())
    }

    @Test fun the_state_keeps_the_shield_across_storage() = runBlocking {
        shareByFile()
        ana.own = listOf(OwnVerdict(scam, ShieldKind.SCAM, 10))
        ana.shield(true)
        sam.shield(true, ShieldMode.BLOCK)
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        val back = SharedLabelState.fromJson(sam.s.toJson())
        assertEquals(sam.s, back)
        assertEquals(ShieldMode.BLOCK, back.shieldMode)
        assertEquals(sam.s.shieldIn, back.shieldIn)
    }
}
