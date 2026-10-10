package app.parley.data.sync.shared

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
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
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.sync.shared.SharedLabelRules
import app.parley.common.sync.shared.SharedLabelMembership.State
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.WorkProfile
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.testing.FakeDocumentsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import app.parley.data.security.RecordCrypto
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest

/**
 * A shared label between two members (docs/SHARED_LABELS.md) through one fake SAF folder: Ana's phone is the real
 * address book (fake Contacts Provider), Sam's an in-memory one. Creating, inviting and joining, edits both ways, a
 * merge, a conflict and its choice, a deletion, a forged file, a vanished file, and removing a member.
 */
@RunWith(RobolectricTestRunner::class)
class SharedLabelSyncTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val cheap = KdfParams.Pbkdf2(BackupCrypto.MIN_ITERATIONS)
    private lateinit var c: DataContainer
    private lateinit var folder: FakeDocumentsProvider
    private lateinit var anaContacts: ProviderLabelContacts
    private val samContacts = MemoryLabelContacts()
    private val ana = Signer()
    private val sam = Signer()
    private lateinit var anaEngine: SharedLabelEngine
    private lateinit var samEngine: SharedLabelEngine
    private var clock = 1_000_000L

    private class Signer : MemberSigner {
        val secret: ByteArray = Ed25519.newSecret()
        override val publicKey: ByteArray = Ed25519.publicKey(secret)
        val hex get() = SharedLabelFiles.keyHex(publicKey)

        override fun sign(message: ByteArray): ByteArray = Ed25519.sign(secret, message)
    }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        VaultCrypto.appContext = app
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
        folder = FakeDocumentsProvider.install()
        anaContacts = ProviderLabelContacts(app, c.contacts, c.records, c.people.labels) { AccountRef(null, null) }
        anaEngine = SharedLabelEngine(SafLabelFolder(app, folder.treeUri), anaContacts, ana) { clock }
        samEngine = SharedLabelEngine(SafLabelFolder(app, folder.treeUri), samContacts, sam) { clock }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    /** Lets time pass and gives files written since the last call a later modified time, as a sync app would. */
    private val hashes = HashMap<String, String>()
    private fun tick() {
        clock += 60_000
        folder.dir.listFiles().orEmpty().forEach { f ->
            val h = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            if (hashes[f.name] != h) {
                hashes[f.name] = h
                f.setLastModified(clock)
            }
        }
    }

    private suspend fun anaAdd(given: String, number: String, groupId: Long): Long =
        c.contacts.save(
            null, ContactDetails(given = given, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE)), groupIds = setOf(groupId)), null, null, false,
        )!!.contactId

    private suspend fun anaEdit(id: Long, change: (ContactDetails) -> ContactDetails) {
        val before = c.contacts.editable(id)!!
        c.contacts.save(before, change(before), null, null, false)
    }

    private fun phones(card: ContactRecord) = SharedCards.text(card, CardField.PHONES)
    private fun note(card: ContactRecord) = SharedCards.text(card, CardField.NOTE)
    private suspend fun anaCard(name: String) = anaContacts.members("Family").single { it.card.displayName.startsWith(name) }.card

    private suspend fun runAna(s: SharedLabelState, allow: Boolean = false) = anaEngine.run(s, allow).also { tick() }
    private suspend fun runSam(s: SharedLabelState, allow: Boolean = false) = samEngine.run(s, allow).also { tick() }

    // One story, step by step, as two people would live it.
    @Suppress("LongMethod")
    @Test
    fun two_members_share_a_label_round_trip() = runBlocking {
        val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
        val ada = anaAdd("Ada", "+44 20 7946 0000", family)
        val grace = anaAdd("Grace", "+1 555 0100", family)
        anaAdd("Linus", "+1 555 0199", c.contacts.createGroup("Work", AccountRef(null, null))!!) // not in the label
        // A private contact in the label stays on this phone.
        c.vault.save(null, ContactDetails(given = "Secret", phones = listOf(DataItem(null, "+1 555 0142", Phone.TYPE_MOBILE)), groupIds = setOf(family)))

        // Ana shares the label.
        var a = anaEngine.create("Family", folder.treeUri.toString(), "Family", "family passphrase".toCharArray(), "Ana", cheap)!!
        a = runAna(a).state
        assertEquals(SharedRunResult.SYNCED, a.lastResult)
        assertEquals(2, a.entries.size)
        assertEquals(1, a.privateLeftOut)
        assertTrue(folder.names().any { it == SharedLabelCrypto.HEADER_NAME })
        assertTrue("nothing readable in the folder", folder.dir.listFiles()!!.none { String(it.readBytes(), Charsets.ISO_8859_1).contains("Ada") })

        // A folder that already holds a label can't take another.
        assertEquals(SharedRunResult.NOT_A_LABEL, anaEngine.checkEmpty())

        // Ana invites Sam with a QR code; Sam sees Ana and her fingerprint, then joins.
        val link = SharedLabelInvites.qrLink(anaEngine.invitation(a, "Syncthing › Family")!!, "K7Q2-9XMC")
        val invite = SharedLabelInvites.fromLink(link, "K7Q29XMC", clock)!!
        assertEquals("Syncthing › Family", invite.folderHint)
        assertEquals(Ed25519.fingerprint(ana.publicKey), invite.inviterFingerprint)
        val preview = samEngine.preview(invite) as SharedLabelEngine.Preview.Ready
        assertEquals(listOf("Ana"), preview.members.map { it.name })
        var s = samEngine.join(invite, folder.treeUri.toString(), "Family", "Family", "Sam", null)
        samContacts.labels += "Family"
        s = runSam(s).state
        assertEquals(setOf("Ada", "Grace"), samContacts.all().map { it.card.displayName }.toSet())
        assertTrue(samContacts.all().none { it.card.displayName == "Secret" || it.card.displayName == "Linus" })

        // Ana's phone now lists Sam, let in by Ana.
        a = runAna(a).state
        assertEquals(setOf("Ana", "Sam"), a.members.map { it.name }.toSet())
        assertEquals(ana.hex, a.members.single { it.name == "Sam" }.invitedBy)

        // Edits to different fields on both phones merge without asking.
        val samAda = samContacts.byName("Ada")
        samContacts.edit(samAda) { it.withPhones("+44 20 7946 1111") }
        anaEdit(ada) { it.copy(note = "Surgery on Fridays") }
        s = runSam(s).state
        a = runAna(a).state
        assertTrue(a.pending.isEmpty())
        assertEquals("+44 20 7946 1111", phones(anaCard("Ada")))
        assertEquals("Surgery on Fridays", note(anaCard("Ada")))
        s = runSam(s).state
        assertEquals("Surgery on Fridays", note(samContacts.cardOf(samAda)))
        // Sam's phone says who changed what.
        assertTrue(s.history.any { it.memberHex == ana.hex && it.kind == ChangeKind.EDITED && CardField.NOTE in it.fields && it.contactName.startsWith("Ada") })
        a = runAna(a).state
        assertTrue(a.history.any { it.memberHex == sam.hex && it.memberName == "Sam" && CardField.PHONES in it.fields })

        // Both change the same number: Sam's phone asks, and keeps its own value meanwhile.
        anaEdit(ada) { d -> d.copy(phones = d.phones.map { it.copy(value = "+44 20 7946 2222") }) }
        samContacts.edit(samAda) { it.withPhones("+44 20 7946 3333") }
        a = runAna(a).state
        s = runSam(s).state
        val sid = s.pending.keys.single()
        assertEquals(setOf(CardField.PHONES), s.pending.getValue(sid).fields)
        assertEquals("Ana", s.pending.getValue(sid).authorName)
        assertEquals("+44 20 7946 3333", phones(samContacts.cardOf(samAda)))
        // Waiting, Sam's phone doesn't write its value over Ana's.
        s = runSam(s).state
        a = runAna(a).state
        assertEquals("+44 20 7946 2222", phones(anaCard("Ada")))
        // Sam picks Ana's number.
        s = samEngine.resolve(s, sid, mapOf(CardField.PHONES to Side.THEIRS))!!
        tick()
        assertTrue(s.pending.isEmpty())
        assertEquals("+44 20 7946 2222", phones(samContacts.cardOf(samAda)))
        a = runAna(a).state
        assertEquals("+44 20 7946 2222", phones(anaCard("Ada")))

        // Ana deletes Grace: a tombstone, and Sam's copy (which the label brought) goes.
        c.contacts.delete(listOf(grace))
        a = runAna(a).state
        assertEquals(1, a.entries.size)
        s = runSam(s).state
        assertNull(samContacts.all().firstOrNull { it.card.displayName == "Grace" })
        assertTrue(samContacts.deleted.isNotEmpty())
        assertTrue(s.history.any { it.kind == ChangeKind.REMOVED && it.contactName == "Grace" && it.memberName == "Ana" })

        // A file that vanishes is written again, never read as a deletion.
        val adaFile = SharedLabelFiles.cardName(a.entries.keys.single())
        File(folder.dir, adaFile).delete()
        tick()
        s = runSam(s).state
        assertNotNull(samContacts.all().firstOrNull { it.card.displayName.startsWith("Ada") })
        assertTrue(File(folder.dir, adaFile).exists())

        // A contact file signed by someone who isn't a member is ignored.
        val eve = Signer()
        val forged = SharedLabelFiles.newId()
        val name = SharedLabelFiles.cardName(forged)
        val body = SharedLabelFiles.writeCard(eve, a.labelId, forged, clock, clock, SharedCards.encode(SharedCards.project(record("Mallory", "+1 555 0666"))))!!
        File(folder.dir, name).writeBytes(SharedLabelCrypto.seal(a.key, a.labelId, name, body))
        tick()
        s = runSam(s).state
        a = runAna(a).state
        assertTrue(samContacts.all().none { it.card.displayName == "Mallory" })
        assertTrue(anaContacts.members("Family").none { it.card.displayName == "Mallory" })

        // Ana removes Sam: a new key. Sam's phone stops syncing and says the key changed.
        a = anaEngine.removeMembers(a, setOf(sam.hex), "new family passphrase".toCharArray(), cheap)!!
        tick()
        assertEquals(State.Active(2), a.membership)
        assertNull(a.oldKey)
        s = runSam(s).state
        assertEquals(SharedRunResult.KEY_CHANGED, s.lastResult)
        assertEquals(State.KeyChanged(1, 2), s.membership)
        // Ana's own phone keeps syncing under the new key.
        anaEdit(ada) { it.copy(note = "New surgery") }
        a = runAna(a).state
        assertEquals(SharedRunResult.SYNCED, a.lastResult)
        assertTrue(a.members.none { it.keyHex == sam.hex })
        // Sam's old key opens nothing new: the note change isn't readable for it.
        assertEquals("Surgery on Fridays", note(samContacts.cardOf(samAda)))
    }

    @Test fun a_member_who_stays_comes_back_with_a_new_invitation() = runBlocking {
        val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
        anaAdd("Ada", "+44 20 7946 0000", family)
        var a = runAna(anaEngine.create("Family", folder.treeUri.toString(), "Family", "pass phrase one".toCharArray(), "Ana", cheap)!!).state
        var s = samEngine.join(anaEngine.invitation(a, "Family")!!, folder.treeUri.toString(), "Family", "Family", "Sam", null)
        samContacts.labels += "Family"
        s = runSam(s).state
        a = runAna(a).state
        // Removing someone else (nobody here): Sam is carried, and needs the new key.
        val kim = Signer()
        a = anaEngine.removeMembers(a, setOf(kim.hex), "pass phrase two".toCharArray(), cheap)!!
        tick()
        assertTrue(a.members.single { it.keyHex == sam.hex }.awaitingKey)
        s = runSam(s).state
        assertEquals(SharedRunResult.KEY_CHANGED, s.lastResult)
        // A new invitation brings Sam back, with what it synced.
        val entriesBefore = s.entries.keys
        s = samEngine.join(anaEngine.invitation(a, "Family")!!, folder.treeUri.toString(), "Family", "Family", "Sam", s)
        assertEquals(State.Active(2), s.membership)
        s = runSam(s).state
        assertEquals(SharedRunResult.SYNCED, s.lastResult)
        assertEquals(entriesBefore, s.entries.keys)
        assertEquals(1, samContacts.all().size)
        a = runAna(a).state
        assertFalse(a.members.single { it.keyHex == sam.hex }.awaitingKey)
    }

    @Test fun many_deletions_at_once_wait_for_the_user() = runBlocking {
        val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
        val ids = (1..6).map { anaAdd("Person$it", "+1 555 01${10 + it}", family) }
        var a = runAna(anaEngine.create("Family", folder.treeUri.toString(), "Family", "pass phrase".toCharArray(), "Ana", cheap)!!).state
        var s = samEngine.join(anaEngine.invitation(a, "Family")!!, folder.treeUri.toString(), "Family", "Family", "Sam", null)
        samContacts.labels += "Family"
        s = runSam(s).state
        assertEquals(6, samContacts.all().size)
        c.contacts.delete(ids.take(5))
        // Ana's own run waits too: 5 of 6 at once.
        a = runAna(a).state
        assertEquals(SharedRunResult.PAUSED, a.lastResult)
        assertEquals(5, a.pendingDeletions)
        a = runAna(a, allow = true).state
        s = runSam(s).state
        assertEquals(SharedRunResult.PAUSED, s.lastResult)
        assertEquals(6, samContacts.all().size)
        s = runSam(s, allow = true).state
        assertEquals(1, samContacts.all().size)
    }

    @Test fun a_renamed_or_deleted_label_never_reads_as_everyone_removed() = runBlocking {
        val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
        anaAdd("Ada", "+44 20 7946 0000", family)
        var a = runAna(anaEngine.create("Family", folder.treeUri.toString(), "Family", "pass phrase".toCharArray(), "Ana", cheap)!!).state
        c.people.labels.rename("Family", "Our family")
        a = runAna(a).state
        assertEquals(SharedRunResult.LABEL_GONE, a.lastResult)
        assertTrue(folder.names().none { name -> SharedLabelFiles.sidOf(name) != null && isTombstone(a, name) })
    }

    @Test fun the_state_round_trips_through_its_json() = runBlocking {
        val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
        anaAdd("Ada", "+44 20 7946 0000", family)
        val a = runAna(anaEngine.create("Family", folder.treeUri.toString(), "Family", "pass phrase".toCharArray(), "Ana", cheap)!!).state
        val back = SharedLabelState.fromJson(JSONObject(a.toJson().toString()))
        assertEquals(a, back)
        val dir = File(app.cacheDir, "labels-" + System.nanoTime())
        val store = SharedLabelStateStore(dir, RecordSealer(app))
        assertTrue(store.put(a))
        assertEquals(a, store.get(a.labelId))
        // Stored sealed: the whole file is the sealed text, so no contact's name or number is in it in plain text.
        // (Looking for "Ada" itself would fail now and then: three given letters turn up in base64 by chance.)
        val stored = File(dir, "${a.labelId}.json").readText()
        assertTrue(stored.startsWith(RecordCrypto.TEXT_PREFIX))
        assertTrue(stored.removePrefix(RecordCrypto.TEXT_PREFIX).all { it.isLetterOrDigit() || it in "+/=" })
        assertFalse(a.toJson().toString().all { it.isLetterOrDigit() || it in "+/=" })
    }

    // ---------------------------------------------------------------- the 5.0 review's findings

    /** Ana and Sam share "Family" (Ada, and [more]); both have run, so each counts the other as a member. */
    private suspend fun shared(vararg more: Pair<String, String>): Triple<SharedLabelState, SharedLabelState, Long> {
        val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
        val ada = anaAdd("Ada", "+44 20 7946 0000", family)
        more.forEach { (n, p) -> anaAdd(n, p, family) }
        var a = runAna(anaEngine.create("Family", folder.treeUri.toString(), "Family", "family passphrase".toCharArray(), "Ana", cheap)!!).state
        var s = samEngine.join(anaEngine.invitation(a, "Family")!!, folder.treeUri.toString(), "Family", "Family", "Sam", null)
        samContacts.labels += "Family"
        s = runSam(s).state
        a = runAna(a).state
        return Triple(a, s, ada)
    }

    private fun sidOf(s: SharedLabelState, contactId: Long) = s.entries.entries.single { it.value.contactId == contactId }.key

    /** The contact file [sid] as [s]'s key reads it now (null: it doesn't open, or isn't one). */
    private fun fileIn(s: SharedLabelState, sid: String) = File(folder.dir, SharedLabelFiles.cardName(sid)).takeIf { it.exists() }?.readBytes()
        ?.let { SharedLabelCrypto.open(s.key, s.labelId, SharedLabelFiles.cardName(sid), it) }
        ?.let { SharedLabelFiles.readCard(s.labelId, SharedLabelFiles.cardName(sid), it) }

    private fun cardIn(s: SharedLabelState, sid: String) = fileIn(s, sid)?.card?.let(SharedCards::decode)

    /** Writes a contact file into the folder as someone with [key] and [by]'s signature would. */
    private fun plant(key: ByteArray, labelId: String, sid: String, by: MemberSigner, version: Long, card: ContactRecord?) {
        val name = SharedLabelFiles.cardName(sid)
        val body = SharedLabelFiles.writeCard(by, labelId, sid, version, clock, card?.let { SharedCards.encode(SharedCards.project(it)) })!!
        File(folder.dir, name).writeBytes(SharedLabelCrypto.seal(key, labelId, name, body))
    }

    /** Everything the folder's contact files say, in plain text, as [s]'s key opens them. */
    private fun folderText(s: SharedLabelState): String = folder.dir.listFiles().orEmpty().mapNotNull { f ->
        SharedLabelFiles.sidOf(f.name)?.let { fileIn(s, it)?.card }
    }.joinToString("\n")

    @Test fun a_card_matching_a_contact_outside_the_label_never_pulls_it_in() = runBlocking {
        // H1: Sam writes a phone-only card with the number of a contact Ana keeps outside the label.
        val (a0, _, _) = shared()
        val shelter = c.contacts.save(
            null,
            ContactDetails(given = "Shelter", phones = listOf(DataItem(null, "+1 555 0177", Phone.TYPE_MOBILE)), note = "Room 4, ask for Jo"),
            null, null, false,
        )!!.contactId
        val sid = SharedLabelFiles.newId()
        plant(a0.key, a0.labelId, sid, sam, clock, phoneOnly("+1 555 0177"))
        tick()
        val a = runAna(a0).state
        // The shelter stays out of the label, isn't linked, and nothing of it reaches the folder.
        assertTrue(anaContacts.members("Family").none { it.id == shelter })
        assertTrue(a.entries.values.none { it.contactId == shelter })
        val text = folderText(a)
        assertFalse(text.contains("Shelter"))
        assertFalse(text.contains("Room 4"))
        // The card came in as a contact of its own in the label, with just the number.
        assertNotEquals(shelter, a.entries.getValue(sid).contactId)
        assertEquals(2, anaContacts.members("Family").size)
    }

    @Test fun a_key_change_signs_again_only_what_this_phone_accepted() = runBlocking {
        // H2: Ana removes Sam; the first try is cut short, and meanwhile Sam (who still has the old key) plants a scam
        // number for Ada and deletes Grace, and Eve, who never joined, adds a contact.
        val (a0, _, ada) = shared("Grace" to "+1 555 0100")
        val adaSid = sidOf(a0, ada)
        val graceSid = a0.entries.keys.single { it != adaSid }
        val flaky = FlakyFolder(SafLabelFolder(app, folder.treeUri)).apply { failWrites = 1 }
        var a = SharedLabelEngine(flaky, anaContacts, ana) { clock }.removeMembers(a0, setOf(sam.hex), "new family passphrase".toCharArray(), cheap)!!
        val old = a.oldKey!!.copyOf()
        val eve = Signer()
        val mallory = SharedLabelFiles.newId()
        plant(old, a.labelId, adaSid, sam, clock + 10, record("Ada", "+1 900 555 0666"))
        plant(old, a.labelId, graceSid, sam, clock + 10, null)
        plant(old, a.labelId, mallory, eve, clock, record("Mallory", "+1 555 0666"))
        tick()
        a = runAna(a).state
        assertNull(a.oldKey)
        assertEquals(SharedRunResult.SYNCED, a.lastResult)
        // Under the new key: Ada as Ana accepted her, Grace still there, Mallory nowhere; Ana's phone unchanged.
        assertEquals("+44 20 7946 0000", phones(cardIn(a, adaSid)!!))
        assertEquals(ana.hex, fileIn(a, adaSid)!!.authorHex)
        assertFalse(fileIn(a, graceSid)!!.deleted)
        assertNull(fileIn(a, mallory))
        assertEquals("+44 20 7946 0000", phones(anaCard("Ada")))
        assertNotNull(anaContacts.members("Family").firstOrNull { it.card.displayName.startsWith("Grace") })
        assertTrue(anaContacts.members("Family").none { it.card.displayName == "Mallory" })

        // After the change, Sam overwrites Ada's file with the old key: left alone a while (it may be arriving)...
        plant(old, a.labelId, adaSid, sam, clock + 20, record("Ada", "+1 900 555 0666"))
        tick()
        a = runAna(a).state
        assertEquals(setOf(adaSid), a.unreadable.keys)
        assertNull(fileIn(a, adaSid))
        assertEquals("+44 20 7946 0000", phones(anaCard("Ada")))
        // ...then Ana's copy goes back, and a later edit of Ana's is published, not frozen.
        clock += SharedLabelRules.CORRUPT_GRACE_MS
        anaEdit(ada) { it.copy(note = "Back") }
        a = runAna(a).state
        assertTrue(a.unreadable.isEmpty())
        assertEquals("Back", note(cardIn(a, adaSid)!!))
        assertEquals("+44 20 7946 0000", phones(cardIn(a, adaSid)!!))
    }

    @Test fun files_a_member_who_left_wrote_last_stay_readable() = runBlocking {
        // On a provider that gives no modified time or size (so content hashes stand in for stamps).
        folder.noStamps = true
        var (a, s, ada) = shared()
        val adaSid = sidOf(a, ada)
        samContacts.edit(samContacts.byName("Ada")) { it.withPhones("+44 20 7946 1111") }
        s = runSam(s).state
        a = runAna(a).state
        assertEquals("+44 20 7946 1111", phones(anaCard("Ada")))
        assertEquals(sam.hex, fileIn(a, adaSid)!!.authorHex)
        // Sam leaves: Ana's phone signs again the file it accepted from Sam.
        assertTrue(samEngine.leave(s))
        tick()
        a = runAna(a).state
        assertTrue(a.members.none { it.keyHex == sam.hex })
        assertEquals(ana.hex, fileIn(a, adaSid)!!.authorHex)
        assertEquals("+44 20 7946 1111", phones(cardIn(a, adaSid)!!))
        // Ana's own edits still go out, and Kim, who joins later, gets Ada.
        anaEdit(ada) { it.copy(note = "Fridays") }
        a = runAna(a).state
        assertEquals("Fridays", note(cardIn(a, adaSid)!!))
        val kim = Signer()
        val kimContacts = MemoryLabelContacts().apply { labels += "Family" }
        val kimEngine = SharedLabelEngine(SafLabelFolder(app, folder.treeUri), kimContacts, kim) { clock }
        kimEngine.run(kimEngine.join(anaEngine.invitation(a, "Family")!!, folder.treeUri.toString(), "Family", "Family", "Kim", null))
        assertEquals("Fridays", note(kimContacts.cardOf(kimContacts.byName("Ada"))))
    }

    @Test fun a_header_swapped_in_without_a_members_signature_is_not_followed() = runBlocking {
        // Someone with folder access writes a header of their own, at a later epoch.
        val (a0, _, ada) = shared()
        val (forged, _) = SharedLabelCrypto.newHeader(a0.labelId, 7, "not the label's".toCharArray(), cheap)
        File(folder.dir, SharedLabelCrypto.HEADER_NAME).writeBytes(forged)
        tick()
        anaEdit(ada) { it.copy(note = "Still syncing") }
        var a = runAna(a0).state
        assertEquals(SharedRunResult.SYNCED, a.lastResult)
        assertEquals(State.Active(1), a.membership)
        assertTrue(a.headerWarning)
        assertEquals("Still syncing", note(cardIn(a, sidOf(a, ada))!!))
        assertNotNull(anaEngine.invitation(a, "Family"))
        // An older or missing header is put back from the last good one, and the warning goes.
        File(folder.dir, SharedLabelCrypto.HEADER_NAME).delete()
        tick()
        a = runAna(a).state
        assertTrue(a.headerWarning)
        assertTrue(File(folder.dir, SharedLabelCrypto.HEADER_NAME).exists())
        tick()
        a = runAna(a).state
        assertFalse(a.headerWarning)
        // A key change a member signed is followed (Sam's phone, after Ana removes someone).
    }

    @Test fun junk_files_are_read_once_and_versions_far_ahead_never_freeze_a_contact() = runBlocking {
        // A member writes Ada at the largest version; junk files sit in the folder.
        var (a, _, ada) = shared()
        val adaSid = sidOf(a, ada)
        plant(a.key, a.labelId, adaSid, sam, Long.MAX_VALUE, record("Ada", "+1 900 555 0666"))
        repeat(5) { File(folder.dir, SharedLabelFiles.cardName(SharedLabelFiles.newId())).writeBytes(ByteArray(64) { 1 }) }
        tick()
        a = runAna(a).state
        assertEquals("+44 20 7946 0000", phones(anaCard("Ada")))
        val journals = folder.names().count(SharedLabelFiles::isJournalName)
        val before = folder.reads
        a = runAna(a).state
        assertEquals("junk isn't opened again, only journals and the header are", journals + 1, folder.reads - before)
        // After the grace period Ana's copy goes back with a version that still counts up.
        clock += SharedLabelRules.STRANGER_GRACE_MS
        a = runAna(a).state
        val f = fileIn(a, adaSid)!!
        assertTrue(f.version in 1 until Long.MAX_VALUE)
        assertEquals("+44 20 7946 0000", phones(cardIn(a, adaSid)!!))
    }

    @Test fun a_file_that_arrived_after_the_listing_is_written_in_place() = runBlocking {
        // Creating it again would make "name (1)", which no other phone reads.
        folder.renameOnCollision = true
        val f = SafLabelFolder(app, folder.treeUri)
        f.list()
        val name = SharedLabelFiles.cardName(SharedLabelFiles.newId())
        File(folder.dir, name).writeBytes(byteArrayOf(1))
        assertNotNull(f.write(name, byteArrayOf(2, 3)))
        assertArrayEquals(byteArrayOf(2, 3), File(folder.dir, name).readBytes())
        assertTrue(folder.names().none { it.endsWith("(1)") })
    }

    @Test fun joining_never_lands_in_a_label_of_the_same_name() = runBlocking {
        // Ana has a "Family" label of her own; Sam shares one also called "Family" and invites her.
        val family = c.contacts.createGroup("Family", AccountRef(null, null))!!
        anaAdd("Ada", "+44 20 7946 0000", family)
        samContacts.labels += "Family"
        samContacts.import("Family", record("Bob", "+1 555 0123"))
        val s = runSam(samEngine.create("Family", folder.treeUri.toString(), "Family", "sam passphrase".toCharArray(), "Sam", cheap)!!).state
        val invite = samEngine.invitation(s, "Family")!!
        val title = c.sharedLabels.titleForJoin(invite.title) { n -> if (n == 1) "Family (shared)" else "Family (shared $n)" }
        assertEquals("Family (shared)", title)
        assertEquals(title, c.sharedLabels.join(invite, folder.treeUri, "Family", "Ana", title, intoExisting = false))
        // Ana's own Family stays hers: Ada isn't shared, and Bob arrives in the new label.
        assertFalse(folderText(s).contains("Ada"))
        assertEquals(1, c.people.labels.members("Family (shared)").size)
        assertEquals(1, c.people.labels.members("Family").size)
        // Renaming either onto the other's name would merge them: refused while one is shared.
        assertTrue(c.sharedLabels.renameWouldMerge("Family", "Family (shared)"))
        assertTrue(c.sharedLabels.renameWouldMerge("Family (shared)", "Family"))
        assertFalse(c.sharedLabels.renameWouldMerge("Family (shared)", "Our family"))
    }

    private fun phoneOnly(phone: String) =
        ContactRecord("", "", raws = listOf(RawRecord(null, null, rows = listOf(DataRow(Mime.PHONE, mapOf(Col.D1 to phone, Col.D2 to "2"))))))

    private fun isTombstone(s: SharedLabelState, name: String): Boolean {
        val body = SharedLabelCrypto.open(s.key, s.labelId, name, File(folder.dir, name).readBytes()) ?: return false
        return SharedLabelFiles.readCard(s.labelId, name, body)?.deleted == true
    }

    private fun record(name: String, phone: String) = ContactRecord(
        "", name,
        raws = listOf(
            RawRecord(
                null, null,
                rows = listOf(DataRow(Mime.NAME, mapOf(Col.D1 to name, Col.D2 to name)), DataRow(Mime.PHONE, mapOf(Col.D1 to phone, Col.D2 to "2"))),
            ),
        ),
    )
}

/** A folder whose next [failWrites] writes fail, as a sync run cut short would see. */
class FlakyFolder(private val inner: LabelFolder) : LabelFolder by inner {
    var failWrites = 0

    override fun write(name: String, bytes: ByteArray): String? = if (failWrites-- > 0) null else inner.write(name, bytes)
}

/** Another member's phone: an address book in memory, with labels by title. */
class MemoryLabelContacts : LabelContacts {
    private class Contact(var key: String, var record: ContactRecord, val labels: MutableSet<String>)

    private val contacts = LinkedHashMap<Long, Contact>()
    private var next = 100L
    val labels = HashSet<String>()
    val deleted = ArrayList<Long>()

    fun all(): List<LabelContacts.Member> = contacts.map { (id, c) -> LabelContacts.Member(id, c.key, SharedCards.project(c.record)) }

    fun byName(name: String): Long = contacts.entries.first { it.value.record.displayName.startsWith(name) }.key

    fun cardOf(id: Long): ContactRecord = SharedCards.project(contacts.getValue(id).record)

    fun edit(id: Long, change: (ContactRecord) -> ContactRecord) {
        contacts.getValue(id).record = change(contacts.getValue(id).record)
    }

    override suspend fun labelExists(title: String) = title in labels

    override suspend fun members(title: String) =
        contacts.filter { title in it.value.labels }.map { (id, c) -> LabelContacts.Member(id, c.key, SharedCards.project(c.record)) }

    override suspend fun card(id: Long) = contacts[id]?.let { LabelContacts.Member(id, it.key, SharedCards.project(it.record)) }

    override suspend fun privateMembers(title: String) = 0

    override suspend fun idFor(key: String, lastId: Long?) = contacts.entries.firstOrNull { it.value.key == key }?.key

    override suspend fun apply(id: Long, card: ContactRecord): Long? {
        val c = contacts[id] ?: return null
        c.record = SharedCards.overlay(c.record, card)
        return id
    }

    override suspend fun import(title: String, card: ContactRecord): Long {
        val id = next++
        contacts[id] = Contact("mem$id", card, mutableSetOf(title))
        return id
    }

    override suspend fun addToLabel(title: String, id: Long): Boolean = contacts[id]?.labels?.add(title) != null

    override suspend fun delete(id: Long) {
        contacts.remove(id)
        deleted += id
    }

    override suspend fun removeFromLabel(title: String, id: Long) {
        contacts[id]?.labels?.remove(title)
    }

    override fun keyOf(id: Long) = contacts[id]?.key
}

private fun ContactRecord.withPhones(vararg numbers: String): ContactRecord {
    val rows = raws.flatMap { it.rows }.filter { it.mimeType != Mime.PHONE } + numbers.map { DataRow(Mime.PHONE, mapOf(Col.D1 to it, Col.D2 to "2")) }
    return copy(raws = listOf(RawRecord(null, null, rows = rows)))
}
