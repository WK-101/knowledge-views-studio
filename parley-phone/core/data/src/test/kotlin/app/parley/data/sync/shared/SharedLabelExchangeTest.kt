package app.parley.data.sync.shared

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.KdfParams
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.common.spam.Ed25519
import app.parley.common.sync.shared.CardField
import app.parley.common.sync.shared.ChangeKind
import app.parley.common.sync.shared.MemberSigner
import app.parley.common.sync.shared.SharedCards
import app.parley.common.sync.shared.SharedLabelCrypto
import app.parley.common.sync.shared.SharedLabelFiles
import app.parley.common.sync.shared.SharedLabelUpdates
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.WorkProfile
import app.parley.data.sync.shared.SharedLabelEngine.UpdateResult
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * A shared label kept by update files only (docs/SHARED_LABELS.md, "Sharing by file"): Ana, Sam and Bob each keep the
 * label's files on their own phone and send updates by any app. Round trips, copies put back, wrong keys, edits made
 * alongside each other (merged, or asked about), and an edit meeting a deletion.
 */
@RunWith(RobolectricTestRunner::class)
class SharedLabelExchangeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val cheap = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
    private var clock = 1_000_000L
    private val root = File(app.cacheDir, "exchange-" + System.nanoTime())

    private class Signer : MemberSigner {
        val secret: ByteArray = Ed25519.newSecret()
        override val publicKey: ByteArray = Ed25519.publicKey(secret)
        val hex get() = SharedLabelFiles.keyHex(publicKey)

        override fun sign(message: ByteArray): ByteArray = Ed25519.sign(secret, message)
    }

    /** One member's phone: an address book, the label's files, and its key. */
    private inner class Phone(name: String) {
        val signer = Signer()
        val contacts = MemoryLabelContacts().apply { labels += "Family" }
        val files = LocalLabelFolder(File(root, name))
        val engine = SharedLabelEngine(files, contacts, signer) { clock }
        lateinit var s: SharedLabelState

        /** "Send an update": a run, then the file. Time passes, as it does between two updates. */
        suspend fun send(): ByteArray {
            s = engine.run(s).state
            clock += 60_000
            return engine.updateFile(s)!!
        }

        /** "Open an update". */
        suspend fun open(bytes: ByteArray): UpdateResult {
            val out = engine.openUpdate(s, bytes)
            if (out.result == UpdateResult.MERGED) s = out.state
            clock += 60_000
            return out.result
        }

        fun card(name: String) = contacts.cardOf(contacts.byName(name))

        fun edit(name: String, change: (ContactRecord) -> ContactRecord) = contacts.edit(contacts.byName(name), change)
    }

    private val ana = Phone("ana")
    private val sam = Phone("sam")
    private val bob = Phone("bob")

    @After fun tearDown() {
        root.deleteRecursively()
    }

    /** Ana shares Family (Ada, Grace) by file; [others] join with her invitation and open her first update. */
    private suspend fun shareByFile(vararg others: Phone) {
        ana.contacts.import("Family", record("Ada", "+44 20 7946 0000"))
        ana.contacts.import("Family", record("Grace", "+1 555 0100"))
        ana.s = ana.engine.create("Family", "", "", "family passphrase".toCharArray(), "Ana", cheap)!!
        ana.s = ana.engine.run(ana.s).state
        for (p in others) {
            p.s = p.engine.join(ana.engine.invitation(ana.s, "")!!, "", "", "Family", if (p === sam) "Sam" else "Bob", null)
            assertTrue(p.s.byFile)
            assertTrue(p.engine.introduce(p.s))
        }
        val first = ana.send()
        for (p in others) {
            assertEquals(UpdateResult.MERGED, p.open(first))
            // Their journal goes back to Ana with their first update, so she counts them.
            assertEquals(UpdateResult.MERGED, ana.open(p.send()))
        }
    }

    @Test fun an_update_round_trips_both_ways() = runBlocking {
        shareByFile(sam)
        assertEquals(setOf("Ada", "Grace"), sam.contacts.all().map { it.card.displayName }.toSet())
        assertEquals(setOf("Ana", "Sam"), ana.s.members.map { it.name }.toSet())
        assertTrue(sam.s.lastSyncAt > 0)
        // Ana's phone remembers when Sam's last update was made.
        assertTrue(ana.s.exchanged.getValue(sam.signer.hex) > 0)
        // Nothing readable in either phone's files.
        assertTrue(File(root, "sam").listFiles()!!.none { String(it.readBytes(), Charsets.ISO_8859_1).contains("Ada") })

        // Sam changes Ada's number; Ana opens his update.
        sam.edit("Ada") { it.withPhones("+44 20 7946 1111") }
        assertEquals(UpdateResult.MERGED, ana.open(sam.send()))
        assertEquals("+44 20 7946 1111", phones(ana.card("Ada")))
        assertTrue(ana.s.history.any { it.memberHex == sam.signer.hex && CardField.PHONES in it.fields })
        // Ana adds someone; Sam gets them.
        ana.contacts.import("Family", record("Linus", "+1 555 0199"))
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        assertNotNull(sam.contacts.all().firstOrNull { it.card.displayName == "Linus" })
        // The state keeps what it learnt through its JSON.
        assertEquals(ana.s, SharedLabelState.fromJson(JSONObject(ana.s.toJson().toString())))
    }

    @Test fun a_third_member_gets_changes_relayed_by_another() = runBlocking {
        shareByFile(sam, bob)
        // Bob only ever exchanges with Sam: Ana's change reaches him in Sam's update.
        ana.edit("Grace") { it.withNote("Back on Monday") }
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        assertEquals(UpdateResult.MERGED, bob.open(sam.send()))
        assertEquals("Back on Monday", note(bob.card("Grace")))
        assertTrue(bob.s.history.any { it.memberHex == ana.signer.hex && it.kind == ChangeKind.EDITED })
    }

    @Test fun copies_put_back_and_own_updates_are_refused() = runBlocking {
        shareByFile(sam)
        sam.edit("Ada") { it.withPhones("+44 20 7946 2222") }
        val first = sam.send()
        sam.edit("Ada") { it.withPhones("+44 20 7946 3333") }
        val second = sam.send()
        assertEquals(UpdateResult.MERGED, ana.open(second))
        assertEquals("+44 20 7946 3333", phones(ana.card("Ada")))
        // The same update again, or Sam's older one: nothing changes.
        assertEquals(UpdateResult.ALREADY_OPENED, ana.open(second))
        assertEquals(UpdateResult.ALREADY_OPENED, ana.open(first))
        assertEquals("+44 20 7946 3333", phones(ana.card("Ada")))
        // Ana's own update.
        assertEquals(UpdateResult.OWN, ana.open(ana.send()))
    }

    @Test fun a_wrong_key_another_label_or_a_damaged_file_is_refused() = runBlocking {
        shareByFile(sam)
        val good = sam.send()
        // One byte changed.
        val flipped = good.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertEquals(UpdateResult.WRONG_KEY, ana.open(flipped))
        // Someone who knows the label's id but not its key.
        val otherKey = SharedLabelCrypto.newHeader(ana.s.labelId, ana.s.epoch, "guess".toCharArray(), cheap).second
        val forged = SharedLabelUpdates.write(bob.signer, otherKey, ana.s.labelId, ana.s.epoch, "Bob", clock, emptyMap())!!
        assertEquals(UpdateResult.WRONG_KEY, ana.open(forged))
        // An update for a later key (the label's key changed after a removal) asks for a new invitation.
        val later = SharedLabelUpdates.write(bob.signer, otherKey, ana.s.labelId, ana.s.epoch + 1, "Bob", clock, emptyMap())!!
        assertEquals(UpdateResult.NEWER_KEY, ana.open(later))
        // Another label's update, or a file that isn't one.
        val other = Phone("other")
        other.contacts.import("Family", record("Zed", "+1 555 0111"))
        other.s = other.engine.create("Family", "", "", "other passphrase".toCharArray(), "Other", cheap)!!
        assertEquals(UpdateResult.OTHER_LABEL, ana.open(other.send()))
        assertEquals(UpdateResult.NOT_AN_UPDATE, ana.open("BEGIN:VCARD".toByteArray()))
        // None of these moved anything, and Sam's real update still opens.
        assertEquals(UpdateResult.MERGED, ana.open(good))
    }

    @Test fun edits_made_alongside_each_other_merge_both_ways() = runBlocking {
        shareByFile(sam)
        // Both change Ada before seeing the other's update, in different fields.
        ana.edit("Ada") { it.withNote("Surgery on Fridays") }
        sam.edit("Ada") { it.withPhones("+44 20 7946 1111") }
        val fromAna = ana.send()
        val fromSam = sam.send()
        assertEquals(UpdateResult.MERGED, ana.open(fromSam))
        assertEquals(UpdateResult.MERGED, sam.open(fromAna))
        for (p in listOf(ana, sam)) {
            assertTrue(p.s.pending.isEmpty())
            assertEquals("+44 20 7946 1111", phones(p.card("Ada")))
            assertEquals("Surgery on Fridays", note(p.card("Ada")))
        }
        // Another round settles on the same contact everywhere, with nothing asked.
        assertEquals(UpdateResult.MERGED, sam.open(ana.send()))
        assertEquals(UpdateResult.MERGED, ana.open(sam.send()))
        assertEquals(SharedCards.hash(ana.card("Ada")), SharedCards.hash(sam.card("Ada")))
        assertTrue(ana.s.pending.isEmpty() && sam.s.pending.isEmpty())
    }

    @Test fun the_same_field_changed_on_two_phones_asks_and_the_choice_travels() = runBlocking {
        shareByFile(sam)
        ana.edit("Ada") { it.withPhones("+44 20 7946 2222") }
        sam.edit("Ada") { it.withPhones("+44 20 7946 3333") }
        val fromAna = ana.send()
        val fromSam = sam.send()
        assertEquals(UpdateResult.MERGED, sam.open(fromAna))
        val sid = sam.s.pending.keys.single()
        assertEquals(setOf(CardField.PHONES), sam.s.pending.getValue(sid).fields)
        assertEquals("Ana", sam.s.pending.getValue(sid).authorName)
        // Sam keeps his own number until he picks.
        assertEquals("+44 20 7946 3333", phones(sam.card("Ada")))
        assertEquals(UpdateResult.MERGED, ana.open(fromSam))
        assertEquals(1, ana.s.pending.size)
        // Sam picks Ana's number; his next update settles Ana's question too.
        sam.s = sam.engine.resolve(sam.s, sid, mapOf(CardField.PHONES to Side.THEIRS))!!
        assertEquals("+44 20 7946 2222", phones(sam.card("Ada")))
        assertEquals(UpdateResult.MERGED, ana.open(sam.send()))
        assertTrue(ana.s.pending.isEmpty())
        assertEquals("+44 20 7946 2222", phones(ana.card("Ada")))
    }

    @Test fun an_edit_wins_over_a_deletion_made_alongside_it() = runBlocking {
        shareByFile(sam)
        val grace = ana.contacts.byName("Grace")
        ana.contacts.removeFromLabel("Family", grace)
        sam.edit("Grace") { it.withNote("New number soon") }
        val fromAna = ana.send()
        val fromSam = sam.send()
        assertEquals(UpdateResult.MERGED, sam.open(fromAna))
        // Sam's edit stays, and goes back to Ana.
        assertEquals("New number soon", note(sam.card("Grace")))
        assertEquals(UpdateResult.MERGED, ana.open(fromSam))
        assertEquals(UpdateResult.MERGED, ana.open(sam.send()))
        assertTrue(ana.contacts.members("Family").any { it.card.displayName == "Grace" && note(it.card) == "New number soon" })
    }

    @Test fun a_member_who_left_by_file_is_seen_to_leave() = runBlocking {
        shareByFile(sam)
        assertTrue(sam.engine.leave(sam.s))
        assertEquals(UpdateResult.MERGED, ana.open(sam.engine.updateFile(sam.s, clock)!!))
        assertEquals(listOf("Ana"), ana.s.members.map { it.name })
    }

    // ---------------------------------------------------------------- through the app's shared labels

    @Test fun the_app_shares_a_label_by_file_and_opens_updates() = runBlocking {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        VaultCrypto.appContext = app
        val c = DataContainer(app)
        try {
            c.settings.update { AppSettings() }
            val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
            val ada = ContactDetails(given = "Ada", phones = listOf(DataItem(null, "+44 20 7946 0000", PhoneKind.TYPE_MOBILE)), groupIds = setOf(family))
            c.contacts.save(null, ada, null, null, false)
            assertEquals(SharedLabels.Created.READY, c.sharedLabels.create("Family", null, "", "family passphrase".toCharArray(), "Ana"))
            val s = c.sharedLabels.forTitle("Family")!!
            assertTrue(s.byFile)
            val update = c.sharedLabels.sendUpdate(s.labelId)!!
            assertTrue(update.state.lastSentAt > 0)
            assertEquals(1, update.state.entries.size)
            assertTrue(File(app.noBackupFilesDir, "shared_labels/files-${s.labelId}").listFiles()!!.any { SharedLabelFiles.sidOf(it.name) != null })
            // This phone's own update, and one for no label here.
            assertEquals(UpdateResult.OWN, c.sharedLabels.openUpdate(update.bytes).result)
            ana.contacts.import("Family", record("Zed", "+1 555 0111"))
            ana.s = ana.engine.create("Family", "", "", "other passphrase".toCharArray(), "Other", cheap)!!
            val elsewhere = c.sharedLabels.openUpdate(ana.send())
            assertEquals(UpdateResult.OTHER_LABEL, elsewhere.result)
            assertNull(elsewhere.state)
            // Leaving by file leaves a last update behind, then the label's files go.
            assertNotNull(c.sharedLabels.farewell(s.labelId))
            assertTrue(c.sharedLabels.leave(s.labelId))
            assertFalse(File(app.noBackupFilesDir, "shared_labels/files-${s.labelId}").exists())
        } finally {
            c.scope.cancel()
            c.db.close()
        }
    }

    private fun phones(card: ContactRecord) = SharedCards.text(card, CardField.PHONES)

    private fun note(card: ContactRecord) = SharedCards.text(card, CardField.NOTE)

    private fun record(name: String, phone: String) = ContactRecord(
        "", name,
        raws = listOf(
            RawRecord(
                null, null,
                rows = listOf(DataRow(Mime.NAME, mapOf(Col.D1 to name, Col.D2 to name)), DataRow(Mime.PHONE, mapOf(Col.D1 to phone, Col.D2 to "2"))),
            ),
        ),
    )

    private fun ContactRecord.withPhones(vararg numbers: String): ContactRecord {
        val rows = raws.flatMap { it.rows }.filter { it.mimeType != Mime.PHONE } + numbers.map { DataRow(Mime.PHONE, mapOf(Col.D1 to it, Col.D2 to "2")) }
        return copy(raws = listOf(RawRecord(null, null, rows = rows)))
    }

    private fun ContactRecord.withNote(text: String): ContactRecord {
        val rows = raws.flatMap { it.rows }.filter { it.mimeType != Mime.NOTE } + DataRow(Mime.NOTE, mapOf(Col.D1 to text))
        return copy(raws = listOf(RawRecord(null, null, rows = rows)))
    }
}
