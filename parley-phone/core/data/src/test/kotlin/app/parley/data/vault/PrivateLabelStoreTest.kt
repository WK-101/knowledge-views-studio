package app.parley.data.vault

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.people.ContactRef
import app.parley.common.people.RelationLinks
import app.parley.common.storage.ContactKeyedStores
import app.parley.common.storage.StoreKind
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.GroupInfo
import app.parley.data.ScreenRequest
import app.parley.data.WorkProfile
import app.parley.data.people.OriginalPhotos
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

/**
 * Private contacts' label membership, ringtone and "send to voicemail" (docs/CONTACT_MODEL.md): kept sealed in the
 * vault entry, never in the address book; labels follow renames, merges and deletes; the call path applies them; and
 * "Recently deleted" keeps a deleted private contact sealed and gives it back whole.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateLabelStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        VaultCrypto.appContext = app
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
        File(app.noBackupFilesDir, "vault_trash").deleteRecursively()
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private suspend fun label(title: String): GroupInfo {
        val id = c.contacts.createGroup(title, AccountRef(null, null))!!
        return c.contacts.groups().first { it.id == id }
    }

    private suspend fun ada(groups: Set<Long> = emptySet()): Long =
        c.vault.save(
            null,
            ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE)), groupIds = groups),
        )

    private fun membershipRows() = provider.rows("data", "mimetype = '${GroupMembership.CONTENT_ITEM_TYPE}'")

    @Test fun membership_is_sealed_in_the_vault_and_never_written_to_the_address_book() = runBlocking {
        val friends = label("Friends")
        val id = ada(setOf(friends.id))
        assertEquals(setOf("Friends"), c.privateLabels.titlesOf(id))
        // Label pages list them by their list id, beside the address book's members.
        assertEquals(setOf(ContactRef.Private(id).navId), c.people.labels.members("Friends"))
        assertTrue("no group row for a private contact", membershipRows().isEmpty())
        // The editor reads it back as the label's id.
        assertEquals(setOf(friends.id), c.vault.details(id)!!.groupIds)
        // Stored sealed: the label's name isn't in the row in plain text.
        val row = c.db.vaultDao().get(id)!!
        assertFalse(String(row.callerIdBlob, Charsets.ISO_8859_1).contains("Friends"))
        assertFalse(String(row.detailBlob, Charsets.ISO_8859_1).contains("Friends"))
    }

    @Test fun membership_follows_parleys_renames_merges_and_deletes() = runBlocking {
        val work = label("Work")
        val id = ada()
        c.people.labels.addMembers(listOf(ContactRef.Private(id).navId), work)
        assertEquals(setOf("Work"), c.privateLabels.titlesOf(id))
        c.people.labels.rename("Work", "Office")
        assertEquals(setOf("Office"), c.privateLabels.titlesOf(id))
        label("Family")
        c.people.labels.merge(setOf("Office"), "Family")
        assertEquals(setOf("Family"), c.privateLabels.titlesOf(id))
        c.people.labels.removeMembers("Family", listOf(ContactRef.Private(id).navId))
        assertTrue(c.privateLabels.titlesOf(id).isEmpty())
        c.people.labels.addMembers(listOf(ContactRef.Private(id).navId), c.contacts.groups().first { it.title == "Family" })
        c.people.labels.delete("Family")
        assertTrue(c.privateLabels.titlesOf(id).isEmpty())
        assertTrue(membershipRows().isEmpty())
    }

    @Test fun a_label_gone_meanwhile_is_kept_through_edits_and_found_again() = runBlocking {
        val club = label("Club")
        // Another label, so the address book's labels can be read (with none at all, stored titles are trusted).
        label("Work")
        val id = ada(setOf(club.id))
        // Deleted in another app: hidden, not forgotten.
        provider.exec("DELETE FROM groups WHERE _id = ${club.id}")
        assertTrue(c.privateLabels.titlesOf(id).isEmpty())
        // An edit that couldn't show it keeps it.
        val d = c.vault.details(id)!!
        c.vault.save(id, d.copy(note = "Met at the club"))
        label("Club")
        assertEquals(setOf("Club"), c.privateLabels.titlesOf(id))
    }

    @Test fun star_ringtone_and_voicemail_change_without_rewriting_the_sealed_details() = runBlocking {
        val id = ada()
        val sealed = c.db.vaultDao().get(id)!!.detailBlob
        assertTrue(c.vault.updateCallerChoices(id) { it.copy(starred = true, ringtone = "content://tone/7", sendToVoicemail = true) })
        assertArrayEquals(sealed, c.db.vaultDao().get(id)!!.detailBlob)
        val d = c.vault.details(id)!!
        assertTrue(d.starred)
        assertEquals("content://tone/7", d.customRingtone)
        assertTrue(d.sendToVoicemail)
        // An edit saved from those details keeps them.
        c.vault.save(id, d.copy(note = "x"))
        val s = c.vault.summary(id)!!
        assertTrue(s.starred && s.sendToVoicemail)
        assertEquals("content://tone/7", s.ringtone)
        assertTrue(VaultCallChoices.any)
    }

    @Test fun the_call_path_rings_a_private_contacts_own_tone_and_sends_it_to_voicemail() = runBlocking {
        val id = ada()
        c.vault.updateCallerChoices(id) { it.copy(ringtone = "content://tone/7") }
        val rung = c.screener.screenCall(ScreenRequest("+44 20 7946 0000", false))
        assertEquals(Decision.Allow, rung.decision)
        assertEquals("content://tone/7", rung.ringtone)
        assertTrue(rung.contactTone)
        c.vault.updateCallerChoices(id) { it.copy(sendToVoicemail = true) }
        val declined = c.screener.screenCall(ScreenRequest("+44 20 7946 0000", false))
        assertEquals(BlockReason.SEND_TO_VOICEMAIL, (declined.decision as Decision.Block).reason)
        assertNull(declined.ringtone)
        // Not a block: nothing about the private contact is logged outside the vault.
        kotlinx.coroutines.delay(200)
        assertTrue(c.db.blockDao().blockedCallsNow().isEmpty())
    }

    @Test fun label_rules_and_ringtones_see_private_members() = runBlocking {
        val team = label("Team")
        ada(setOf(team.id))
        c.peoplePrefs.update { it.copy(labelRingtones = mapOf("Team" to "content://tone/team")) }
        val r = c.screener.screenCall(ScreenRequest("+44 20 7946 0000", false))
        assertEquals("content://tone/team", r.ringtone)
        assertFalse(r.contactTone)
    }

    @Test fun a_deleted_private_contact_is_kept_sealed_and_comes_back_whole() = runBlocking {
        val friends = label("Friends")
        val id = ada(setOf(friends.id))
        c.vault.updateCallerChoices(id) { it.copy(starred = true) }
        assertTrue(c.privateTrash.keep(id))
        c.vault.delete(id)
        assertNull(c.vault.summary(id))
        assertEquals(1, c.privateTrash.count())
        // The kept file is sealed: no name or number in plain text.
        val file = File(app.noBackupFilesDir, "vault_trash").listFiles()!!.single()
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("Lovelace"))
        assertFalse(raw.contains("7946"))
        val kept = c.privateTrash.list().single()
        assertEquals("Ada Lovelace", kept.name)
        val back = c.privateTrash.restore(kept.file)!!
        val s = c.vault.summary(back)!!
        assertEquals("Ada Lovelace", s.name)
        assertTrue(s.starred)
        assertEquals(setOf("Friends"), c.privateLabels.titlesOf(back))
        assertEquals("Lovelace", c.vault.details(back)!!.family)
        assertNotNull(c.vault.lookup("+44 20 7946 0000"))
        assertEquals(0, c.privateTrash.count())
    }

    @Test fun a_restored_contact_gets_its_photo_as_picked_and_the_relations_pointing_at_it() = runBlocking {
        val id = ada()
        val key = ContactRef.privateKey(id)
        // The photo as picked, kept sealed beside the entry.
        val picture = byteArrayOf(1, 2, 3, 4)
        assertTrue(OriginalPhotos.restoreSealedPrivate(app, id, VaultCrypto.sealCallerId(picture), """{"w":60,"h":40}"""))
        // Bob's relation "Sister: Ada" opens her.
        c.meta.ensureMeta("lk-bob", 7)
        c.meta.setRelationLinks("lk-bob", RelationLinks.encode(mapOf("ada lovelace" to RelationLinks.Link(key, -id))))

        assertTrue(c.privateTrash.keep(id))
        c.vault.delete(id)
        c.contactKeys.forget(key)
        assertNull(OriginalPhotos.sealedPrivate(app, id))
        assertTrue(RelationLinks.decode(c.meta.meta("lk-bob")?.relationLinks).isEmpty())

        val back = c.privateTrash.restore(c.privateTrash.list().single().file)!!
        val (image, meta) = OriginalPhotos.sealedPrivate(app, back)!!
        assertArrayEquals("the photo as picked comes back", picture, VaultCrypto.openCallerId(image))
        assertEquals("""{"w":60,"h":40}""", meta)
        assertEquals(RelationLinks.Link(ContactRef.privateKey(back), -back), RelationLinks.decode(c.meta.meta("lk-bob")?.relationLinks)["ada lovelace"])
    }

    @Test fun an_editor_save_keeps_a_star_or_label_changed_meanwhile() = runBlocking {
        val friends = label("Friends")
        val work = label("Work")
        val id = ada(setOf(friends.id))
        val loaded = c.vault.details(id)!!
        // While the editor is open: starred from the list, and added to Work from the selection bar.
        c.vault.updateCallerChoices(id) { it.copy(starred = true) }
        c.people.labels.addMembers(listOf(ContactRef.Private(id).navId), work)
        // The editor saves: it changed the note and took Friends off, nothing else.
        c.vault.save(id, loaded.copy(note = "x", groupIds = emptySet()), loaded = loaded)
        val s = c.vault.summary(id)!!
        assertTrue("the star stays", s.starred)
        assertEquals(setOf("Work"), c.privateLabels.titlesOf(id))
        assertEquals("x", c.vault.details(id)!!.note)
    }

    @Test fun kept_copies_go_after_thirty_days() = runBlocking {
        val id = ada()
        val long = System.currentTimeMillis() - 31 * 86_400_000L
        c.privateTrash.keep(id, now = long)
        c.privateTrash.purge()
        assertEquals(0, c.privateTrash.count())
    }

    @Test fun call_time_is_a_contact_keyed_store() {
        assertTrue(ContactKeyedStores.all.contains(StoreKind.PREFS to "parley_calling"))
    }
}
