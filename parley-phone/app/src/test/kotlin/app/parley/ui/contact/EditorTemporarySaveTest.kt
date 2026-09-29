package app.parley.ui.contact

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.ContactRef
import app.parley.common.people.ExpiryChange
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryChoice
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import app.parley.ui.people.BackgroundChange
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/** The editor's "Save to: Temporary" and an existing contact's expiry go through the temporary-contact stores. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EditorTemporarySaveTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        VaultCrypto.appContext = context
        c = DataContainer(context)
    }

    @After fun tearDown() = c.scope.cancel()

    private val plumber = ContactDetails(
        given = "Sam", family = "Plumber",
        phones = listOf(DataItem(null, "+44 7700 900123", Phone.TYPE_MOBILE)),
        emails = listOf(DataItem(null, "sam@example.org", Email.TYPE_WORK)),
    )

    private fun request(
        original: ContactDetails?,
        draft: ContactDetails,
        toVault: Boolean = false,
        temporary: TemporaryChoice? = null,
        expiry: ExpiryChange? = null,
        vaultId: Long? = null,
        picked: Map<String, RelationLinks.Link> = emptyMap(),
    ) =
        SaveContactUseCase.Request(
            original = original, draft = draft, account = null, photo = null, removePhoto = false, toVault = toVault, vaultId = vaultId,
            background = BackgroundChange.None, pickedLinks = picked, temporary = temporary, expiry = expiry,
        )

    @Test fun a_visible_temporary_contact_keeps_every_field_and_expires() = runBlocking {
        val before = System.currentTimeMillis()
        val out = SaveContactUseCase(c)(request(null, plumber, temporary = TemporaryChoice(days = 30, private = false, purgeHistory = false)))
        val saved = out as SaveContactUseCase.Outcome.Saved
        assertTrue(saved.id > 0)
        assertNull(saved.keepPromptKey)
        val entry = c.temporaries.all.first().single()
        assertEquals(saved.id, entry.contactId)
        assertEquals(false, entry.purgeHistory)
        assertTrue(entry.expiresAt >= before + 30 * TemporaryChoice.DAY_MS)
        val details = c.contacts.editable(saved.id)!!
        assertEquals("sam@example.org", details.emails.single().value)
    }

    @Test fun a_private_temporary_contact_goes_to_the_vault_with_an_expiry() = runBlocking {
        val out = SaveContactUseCase(c)(request(null, plumber, toVault = true, temporary = TemporaryChoice(days = 1)))
        val saved = out as SaveContactUseCase.Outcome.Saved
        assertTrue(saved.id < 0)
        val summary = c.vault.summariesNow().single { it.id == -saved.id }
        assertNotNull(summary.expiresAt)
        assertTrue(summary.purgeHistory)
        assertEquals("sam@example.org", c.vault.details(-saved.id)!!.emails.single().value)
    }

    @Test fun an_existing_contact_is_made_temporary_then_kept() = runBlocking {
        val id = c.contacts.save(null, plumber, null, null, false)!!.contactId
        val loaded = c.contacts.editable(id)!!
        val made = SaveContactUseCase(c)(request(loaded, loaded, expiry = ExpiryChange.After(7))) as SaveContactUseCase.Outcome.Saved
        // Choosing the time here answers "Keep this contact?" already.
        assertNull(made.keepPromptKey)
        val key = c.contacts.lookupKeyOf(made.id)!!
        assertNotNull(c.temporaries.forKey(key))
        val again = c.contacts.editable(made.id)!!
        SaveContactUseCase(c)(request(again, again, expiry = ExpiryChange.Keep))
        assertNull(c.temporaries.forKey(c.contacts.lookupKeyOf(made.id)!!))
    }

    @Test fun a_private_temporary_contact_keeps_the_relations_picked_in_the_editor() = runBlocking {
        val ana = c.contacts.save(null, ContactDetails(given = "Ana", family = "Lee"), null, null, false)!!.contactId
        val link = RelationLinks.Link(c.contacts.lookupKeyOf(ana)!!, ana)
        val draft = plumber.copy(relations = listOf(DataItem(null, "Mum", Relation.TYPE_MOTHER)))
        val saved = SaveContactUseCase(c)(request(null, draft, toVault = true, temporary = TemporaryChoice(days = 1), picked = mapOf("mum" to link)))
            as SaveContactUseCase.Outcome.Saved
        val links = RelationLinks.decode(c.meta.meta(ContactRef.privateKey(-saved.id))?.relationLinks)
        assertEquals(link, links["mum"])
    }

    @Test fun a_new_date_in_the_editor_keeps_a_private_contacts_call_history_choice() = runBlocking {
        val saved = SaveContactUseCase(c)(request(null, plumber, toVault = true, temporary = TemporaryChoice(days = 1, purgeHistory = false)))
            as SaveContactUseCase.Outcome.Saved
        val v = -saved.id
        assertEquals(false, c.vault.summary(v)!!.purgeHistory)
        val loaded = c.vault.details(v)!!
        SaveContactUseCase(c)(request(null, loaded, toVault = true, vaultId = v, expiry = ExpiryChange.After(30)))
        val s = c.vault.summary(v)!!
        assertEquals(false, s.purgeHistory)
        assertTrue(s.expiresAt!! > System.currentTimeMillis() + 29 * TemporaryChoice.DAY_MS)
    }
}
