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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
        val invite = SharedLabelInvites.fromLink(link, "K7Q29XMC")!!
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
        // Stored sealed: the contacts' names are not in the file in plain text.
        assertFalse(File(dir, "${a.labelId}.json").readText().contains("Ada"))
    }

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

    override suspend fun findMatch(card: ContactRecord, exclude: Set<Long>): Long? {
        val keys = SharedCards.matchKeys(card)
        return contacts.entries.firstOrNull { it.key !in exclude && SharedCards.matchKeys(SharedCards.project(it.value.record)).any { k -> k in keys } }?.key
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
