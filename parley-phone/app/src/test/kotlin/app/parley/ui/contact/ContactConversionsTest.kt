package app.parley.ui.contact

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import androidx.test.core.app.ApplicationProvider
import app.parley.common.circle.InteractionType
import app.parley.common.people.ContactRef
import app.parley.common.people.RelationLinks
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.testing.VaultTesting
import app.parley.data.vault.VaultCrypto
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One contact, converted between its variants (docs/CONTACT_MODEL.md): device → private → device keeps every field
 * and everything Parley keeps about the person (note for calls, Circle membership, logged moments, the call-screen
 * picture, labels, a temporary date), re-keyed to the contact's key each time, with nothing left under the old one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContactConversionsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private lateinit var provider: FakeContactsProvider
    private lateinit var callLog: FakeCallLogProvider

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        provider = FakeContactsProvider.install()
        callLog = FakeCallLogProvider.install()
        File(context.noBackupFilesDir, "vault_trash").deleteRecursively()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        VaultCrypto.appContext = context
        c = DataContainer(context)
    }

    @After fun tearDown() = c.scope.cancel()

    private fun picture(): Uri {
        val f = File(context.cacheDir, "bg.png")
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    /** Ada, a phone contact with a label, a note for calls, a Circle rhythm, a logged moment and a call-screen picture. */
    private suspend fun ada(): Pair<Long, String> {
        val id = c.contacts.save(
            null,
            ContactDetails(given = "Ada", family = "Lovelace", company = "Engines", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))),
            null, null, false,
        )!!.contactId!!
        val key = c.contacts.lookupKeyOf(id)!!
        c.meta.ensureMeta(key, id)
        c.meta.setPinnedNote(key, id, "Ask about the engine")
        c.circle.setRhythm(key, id, 14)
        c.circle.interactions.log(key, id, InteractionType.MEET, null, 1_000L, "Coffee in town", "m:coffee")
        c.people.backgrounds.set(key, picture())
        val group = c.contacts.createGroup("Friends", AccountRef(null, null))
        if (group != null) c.contacts.addToGroup(listOf(id), c.contacts.groups().first { it.id == group })
        return id to key
    }

    private suspend fun visible(conversions: ContactConversions, vaultId: Long): Long =
        (conversions.makeVisible(vaultId, c.vault.details(vaultId)!!, AccountRef(null, null)) as ContactConversions.MadeVisible.Done).contactId

    private fun assertNothingUnder(key: String) = runBlocking {
        assertNull("meta left under $key", c.meta.meta(key))
        assertTrue("interactions left under $key", c.circle.interactions.interactionsFor(key).isEmpty())
        assertNull("picture left under $key", c.people.backgrounds.forLookupKey(key))
        assertNull("temporary flag left under $key", c.meta.temporary(key))
    }

    @Test fun device_to_private_to_device_keeps_everything() = runBlocking {
        val (id, key) = ada()
        val labelled = c.contacts.details(id)!!.groupIds
        assertTrue("Ada is in a label to begin with", labelled.isNotEmpty())
        val conversions = ContactConversions(c)

        // ---- Make private
        val made = conversions.makePrivate(id, c.contacts.details(id)!!)
        val privateKey = ContactRef.privateKey(made.vaultId)
        assertNull("gone from the address book", c.contacts.details(id))
        val sealed = c.vault.details(made.vaultId)!!
        assertEquals("Ada Lovelace", sealed.composedName)
        assertEquals("Engines", sealed.company)
        assertEquals("the note for calls is sealed with them", "Ask about the engine", sealed.pinnedNote)
        val privateMeta = c.meta.meta(privateKey)!!
        assertEquals("Circle membership follows", 14, privateMeta.reachOutDays)
        assertNull("no plain copy of the note outside the vault entry", privateMeta.pinnedNote)
        assertEquals("Coffee in town", c.circle.interactions.interactionsFor(privateKey).single().note)
        assertNotNull("call-screen picture follows", c.people.backgrounds.forLookupKey(privateKey))
        assertNothingUnder(key)
        // A key sweep never resolves a private key through the address book.
        c.contactKeys.sweep()
        assertEquals(14, c.meta.meta(privateKey)?.reachOutDays)

        // ---- Make visible
        val back = visible(conversions, made.vaultId)
        val newKey = c.contacts.lookupKeyOf(back)!!
        val restored = c.contacts.details(back)!!
        assertEquals("Ada Lovelace", restored.composedName)
        assertEquals("Engines", restored.company)
        assertEquals("+44 20 7946 0000", restored.phones.single().value)
        assertEquals("labels come back", labelled.size, restored.groupIds.size)
        val meta = c.meta.meta(newKey)!!
        assertEquals("Ask about the engine", meta.pinnedNote)
        assertEquals(14, meta.reachOutDays)
        assertEquals("Coffee in town", c.circle.interactions.interactionsFor(newKey).single().note)
        assertNotNull(c.people.backgrounds.forLookupKey(newKey))
        assertTrue("the private entry is gone", c.vault.summariesNow().isEmpty())
        assertNothingUnder(privateKey)
    }

    @Test fun labels_ringtone_and_voicemail_become_parleys_while_private_and_the_address_books_again() = runBlocking {
        val (id, _) = ada()
        c.contacts.setRingtone(id, "content://tone/ada")
        c.contacts.setSendToVoicemail(id, true)
        val conversions = ContactConversions(c)
        val made = conversions.makePrivate(id, c.contacts.details(id)!!)
        // Kept sealed in the vault entry; the address book no longer has any of it.
        val s = c.vault.summary(made.vaultId)!!
        assertEquals("content://tone/ada", s.ringtone)
        assertTrue(s.sendToVoicemail)
        assertEquals(setOf("Friends"), c.privateLabels.titlesOf(made.vaultId))
        // Changed while private: out of Friends, into Work, no voicemail.
        val workId = c.contacts.createGroup("Work", AccountRef(null, null))!!
        c.people.labels.addMembers(listOf(ContactRef.Private(made.vaultId).navId), c.contacts.groups().first { it.id == workId })
        c.people.labels.removeMembers("Friends", listOf(ContactRef.Private(made.vaultId).navId))
        c.vault.updateCallerChoices(made.vaultId) { it.copy(sendToVoicemail = false) }
        assertEquals(setOf("Work"), c.privateLabels.titlesOf(made.vaultId))

        val back = visible(conversions, made.vaultId)
        assertEquals(setOf("Work"), c.contacts.labelTitlesOf(back))
        val restored = c.contacts.details(back)!!
        assertEquals("content://tone/ada", restored.customRingtone)
        assertEquals(false, restored.sendToVoicemail)
    }

    @Test fun a_temporary_contact_stays_temporary_both_ways() = runBlocking {
        val (id, key) = ada()
        val at = System.currentTimeMillis() + 5 * 86_400_000L
        c.temporaries.markAt(id, at, purgeHistory = false, name = "Ada")
        val conversions = ContactConversions(c)

        val made = conversions.makePrivate(id, c.contacts.details(id)!!)
        val entry = c.vault.summariesNow().single()
        assertEquals(at, entry.expiresAt)
        assertEquals(false, entry.purgeHistory)
        assertNothingUnder(key)

        val back = visible(conversions, made.vaultId)
        val temp = c.meta.temporary(c.contacts.lookupKeyOf(back)!!)!!
        assertEquals(at, temp.expiresAt)
        assertEquals(false, temp.purgeHistory)
    }

    @Test fun deleting_a_private_contact_forgets_what_parley_kept() = runBlocking {
        val (id, _) = ada()
        val made = ContactConversions(c).makePrivate(id, c.contacts.details(id)!!)
        ContactConversions(c).deletePrivate(made.vaultId)
        assertTrue(c.vault.summariesNow().isEmpty())
        assertNothingUnder(ContactRef.privateKey(made.vaultId))
    }

    @Test fun a_private_contacts_extras_travel_in_the_private_backup_section() = runBlocking {
        val (id, _) = ada()
        val made = ContactConversions(c).makePrivate(id, c.contacts.details(id)!!)
        val exported = c.contactKeys.exportPrivate(ContactRef.privateKey(made.vaultId))!!
        val details = c.vault.details(made.vaultId)!!
        // As on another phone: the private contact is restored under a new id.
        ContactConversions(c).deletePrivate(made.vaultId)
        val other = c.vault.save(null, details)
        c.contactKeys.importPrivate(other, exported)
        val key = ContactRef.privateKey(other)
        assertEquals(14, c.meta.meta(key)?.reachOutDays)
        assertEquals("Coffee in town", c.circle.interactions.interactionsFor(key).single().note)
        assertNotNull(c.people.backgrounds.forLookupKey(key))
    }

    @Test fun an_entry_made_private_before_keeps_its_star_labels_and_ringtone_when_made_visible() = runBlocking {
        val (id, _) = ada()
        c.contacts.setStarred(id, true)
        c.contacts.setRingtone(id, "content://tone/ada")
        val conversions = ContactConversions(c)
        val made = conversions.makePrivate(id, c.contacts.details(id)!!)
        VaultTesting.asBeforeCallerChoices(c.vault, made.vaultId)
        assertEquals(false, c.vault.summary(made.vaultId)!!.choicesKnown)

        val back = visible(conversions, made.vaultId)
        val restored = c.contacts.details(back)!!
        assertTrue("the star isn't taken away", restored.starred)
        assertEquals("content://tone/ada", restored.customRingtone)
        assertEquals(setOf("Friends"), c.contacts.labelTitlesOf(back))
    }

    @Test fun the_migration_seeds_entries_from_before_once() = runBlocking {
        val (id, _) = ada()
        c.contacts.setStarred(id, true)
        c.contacts.setRingtone(id, "content://tone/ada")
        val made = ContactConversions(c).makePrivate(id, c.contacts.details(id)!!)
        VaultTesting.asBeforeCallerChoices(c.vault, made.vaultId)

        assertEquals(1, c.vault.migrateCallerChoices())
        val s = c.vault.summary(made.vaultId)!!
        assertTrue(s.choicesKnown)
        assertTrue(s.starred)
        assertEquals("content://tone/ada", s.ringtone)
        assertEquals(setOf("Friends"), c.privateLabels.titlesOf(made.vaultId))
        // Marked done: nothing runs again, and a change made since stays.
        c.vault.updateCallerChoices(made.vaultId) { it.copy(starred = false) }
        assertEquals(0, c.vault.migrateCallerChoices())
        assertEquals(false, c.vault.summary(made.vaultId)!!.starred)
    }

    @Test fun a_temporary_contact_made_visible_records_only_its_own_raw_contacts() = runBlocking {
        val (id, _) = ada()
        val at = System.currentTimeMillis() + 86_400_000L
        c.temporaries.markAt(id, at, purgeHistory = false, name = "Ada")
        val conversions = ContactConversions(c)
        val made = conversions.makePrivate(id, c.contacts.details(id)!!)
        // The user's own copy of Ada in another account, which Android joins the restored contact with.
        val own = c.contacts.save(null, ContactDetails(given = "Ada", family = "Lovelace"), AccountRef("com.google", "me@example.com"), null, false)!!
        provider.joinNewRawsInto = own.contactId
        val back = visible(conversions, made.vaultId)
        provider.joinNewRawsInto = null
        assertEquals("joined with the user's copy", own.contactId, back)

        c.temporaries.expire(at + 1)
        assertTrue("the user's own copy is never deleted", own.rawId!! in c.contacts.rawIds(own.contactId))
        assertEquals("only the restored copy went", listOf(own.rawId), c.contacts.rawIds(own.contactId))
    }

    @Test fun an_unreadable_key_keeps_parleys_data_until_the_sweep_finds_the_contact() = runBlocking {
        val (id, _) = ada()
        val conversions = ContactConversions(c)
        val made = conversions.makePrivate(id, c.contacts.details(id)!!)
        val privateKey = ContactRef.privateKey(made.vaultId)
        provider.lookupKeysUnreadable = true
        val back = visible(conversions, made.vaultId)
        provider.lookupKeysUnreadable = false
        // Nothing was forgotten: it waits under the private key.
        assertEquals(14, c.meta.meta(privateKey)?.reachOutDays)
        assertEquals("Ask about the engine", c.meta.meta(privateKey)?.pinnedNote)
        assertEquals(setOf(privateKey), c.contactKeys.waitingKeys())

        c.contactKeys.sweep()
        val key = c.contacts.lookupKeyOf(back)!!
        assertEquals(14, c.meta.meta(key)?.reachOutDays)
        assertEquals("Ask about the engine", c.meta.meta(key)?.pinnedNote)
        assertEquals("Coffee in town", c.circle.interactions.interactionsFor(key).single().note)
        assertNotNull(c.people.backgrounds.forLookupKey(key))
        assertTrue(c.contactKeys.waitingKeys().isEmpty())
        assertNothingUnder(privateKey)
    }

    @Test fun private_calls_go_back_before_the_entry_goes_or_nothing_changes() = runBlocking {
        val (id, _) = ada()
        val conversions = ContactConversions(c)
        val made = conversions.makePrivate(id, c.contacts.details(id)!!)
        c.vault.storePrivateCall(made.vaultId, "+44 20 7946 0000", "Ada Lovelace", 5_000L, 60L, CallLog.Calls.INCOMING_TYPE)
        val contactsBefore = c.contacts.lookupKeys()!!.size

        // The call log can't be written: the private contact, its call and the address book stay as they were.
        callLog.failInserts = true
        assertEquals(ContactConversions.MadeVisible.CallsKept, conversions.makeVisible(made.vaultId, c.vault.details(made.vaultId)!!, AccountRef(null, null)))
        assertEquals(1, c.vault.privateCallsOf(made.vaultId).size)
        assertEquals(contactsBefore, c.contacts.lookupKeys()!!.size)

        callLog.failInserts = false
        visible(conversions, made.vaultId)
        assertTrue(c.vault.summariesNow().isEmpty())
        val row = callLog.rows().single()
        assertEquals("+44 20 7946 0000", row["number"])
        assertEquals("5000", row["date"])
    }

    @Test fun a_delete_waits_when_no_copy_can_be_kept() = runBlocking {
        val (id, _) = ada()
        val made = ContactConversions(c).makePrivate(id, c.contacts.details(id)!!)
        // "Recently deleted" can't be written: a file stands where its folder should be.
        val trash = File(context.noBackupFilesDir, "vault_trash")
        trash.deleteRecursively()
        trash.writeText("in the way")
        try {
            assertEquals(false, ContactConversions(c).deletePrivate(made.vaultId))
            assertEquals("nothing was deleted", 1, c.vault.summariesNow().size)
            assertTrue("deleting without a copy is still possible", ContactConversions(c).deletePrivate(made.vaultId, keepCopy = false))
            assertTrue(c.vault.summariesNow().isEmpty())
        } finally {
            trash.delete()
        }
    }

    @Test fun making_a_contact_private_takes_back_the_relations_parley_wrote_for_it() = runBlocking {
        val ana = c.contacts.save(null, ContactDetails(given = "Ana", family = "Lee"), null, null, false)!!.contactId
        val mother = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_MOTHER))
        val sam = c.contacts.save(null, ContactDetails(given = "Sam", family = "Lee", relations = mother), null, null, false)!!.contactId
        c.people.relationMirrors.mirror(sam, mother, mapOf("ana lee" to RelationLinks.Link(c.contacts.lookupKeyOf(ana)!!, ana)))
        assertEquals("Sam Lee", c.contacts.details(ana)!!.relations.single().value)

        ContactConversions(c).makePrivate(sam, c.contacts.details(sam)!!)
        assertTrue("Ana's contact no longer names Sam", c.contacts.details(ana)!!.relations.isEmpty())
        val record = context.getSharedPreferences("relation_mirrors", android.content.Context.MODE_PRIVATE).getString("created", null)
        assertTrue("Parley's record keeps no trace of Sam", record.isNullOrEmpty())
    }
}
