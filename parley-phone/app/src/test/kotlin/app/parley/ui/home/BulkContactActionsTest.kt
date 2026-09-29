package app.parley.ui.home

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.BulkAction
import app.parley.common.people.BulkActions
import app.parley.common.people.ContactRef
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import app.parley.ui.contact.ContactConversions
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
 * The Contacts tab's bulk actions on a selection of one device contact (Bob) and one private contact (Ada): what works
 * for both works for both, what would copy Ada out of Parley leaves her out, and nothing of hers reaches the address
 * book until she is made visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BulkContactActionsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer
    private lateinit var bulk: BulkContactActions

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        provider = FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        VaultCrypto.appContext = context
        c = DataContainer(context)
        bulk = BulkContactActions(c)
    }

    @After fun tearDown() = c.scope.cancel()

    private suspend fun bob(): Long = c.contacts.save(
        null, ContactDetails(given = "Bob", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))), null, null, false,
    )!!.contactId!!

    private suspend fun ada(): Long = c.vault.save(null, ContactDetails(given = "Ada", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))))

    @Test fun star_label_and_expiry_work_on_both_kinds() = runBlocking {
        val bob = bob()
        val ada = ada()
        val ids = listOf(bob, ContactRef.Private(ada).navId)

        bulk.star(ids, true)
        assertTrue(c.contacts.details(bob)!!.starred)
        assertTrue(c.vault.summary(ada)!!.starred)

        val groupId = c.contacts.createGroup("Team", AccountRef(null, null))!!
        val team = c.contacts.groups().first { it.id == groupId }
        assertEquals(0, bulk.addToLabel(ids, team))
        assertTrue("Team" in c.contacts.labelTitlesOf(bob))
        assertEquals(setOf("Team"), c.privateLabels.titlesOf(ada))
        // Only Bob's membership is in the address book.
        assertEquals(1, provider.rows("data", "mimetype = '${GroupMembership.CONTENT_ITEM_TYPE}'").size)
        assertTrue(ContactRef.Private(ada).navId in c.people.labels.members("Team"))

        bulk.setExpiry(ids, 7)
        assertNotNull(c.vault.summary(ada)!!.expiresAt)
        assertNotNull(c.temporaries.forKey(c.contacts.lookupKeyOf(bob)!!))
        bulk.setExpiry(ids, null)
        assertNull(c.vault.summary(ada)!!.expiresAt)
        assertNull(c.temporaries.forKey(c.contacts.lookupKeyOf(bob)!!))
    }

    @Test fun actions_that_would_copy_a_private_contact_out_leave_it_out() = runBlocking {
        val bob = bob()
        val ada = ContactRef.Private(ada()).navId
        val ids = listOf(bob, ada)
        for (a in listOf(BulkAction.SHARE, BulkAction.EXPORT, BulkAction.COPY_AS_TEXT, BulkAction.MERGE)) {
            assertEquals(a.name, BulkActions.Targets(listOf(bob), 1), BulkActions.targets(a, ids))
        }
        assertEquals(listOf(bob), BulkActions.targets(BulkAction.MAKE_PRIVATE, ids).ids)
        assertEquals(listOf(ada), BulkActions.targets(BulkAction.MAKE_VISIBLE, ids).ids)
    }

    @Test fun make_visible_and_delete_across_a_mixed_selection() = runBlocking {
        val bob = bob()
        val ada = ada()
        val groupId = c.contacts.createGroup("Team", AccountRef(null, null))!!
        val team = c.contacts.groups().first { it.id == groupId }
        val ids = listOf(bob, ContactRef.Private(ada).navId)
        bulk.addToLabel(ids, team)
        bulk.star(ids, true)

        // Only Ada is converted; she arrives with her star and her label as the address book's own.
        assertEquals(1, bulk.makeVisible(ids, AccountRef(null, null)))
        assertTrue(c.vault.summariesNow().isEmpty())
        val visible = c.contacts.snapshot().first { it.displayName == "Ada" }.id
        assertTrue("Team" in c.contacts.labelTitlesOf(visible))
        assertTrue(c.contacts.details(visible)!!.starred)

        // Deleting a private contact keeps a sealed copy for "Recently deleted"; the device one goes to History & undo.
        val other = c.vault.save(null, ContactDetails(given = "Cy", phones = listOf(DataItem(null, "+1 202 555 0199", Phone.TYPE_MOBILE))))
        ContactConversions(c).deletePrivate(other)
        assertEquals(1, c.privateTrash.count())
        assertNull(c.vault.summary(other))
    }

    @Test fun a_new_date_keeps_the_keep_call_history_choice() = runBlocking {
        val bob = bob()
        // Both made temporary earlier with "Also delete call history" off.
        val ada = c.vault.save(
            null, ContactDetails(given = "Ada", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))),
            expiresAt = System.currentTimeMillis() + 86_400_000L, purgeHistory = false,
        )
        c.temporaries.mark(bob, 1, purgeHistory = false)
        val ids = listOf(bob, ContactRef.Private(ada).navId)

        bulk.setExpiry(ids, 30)
        assertEquals(false, c.vault.summary(ada)!!.purgeHistory)
        assertEquals(false, c.temporaries.forKey(c.contacts.lookupKeyOf(bob)!!)!!.purgeHistory)

        // Made temporary now: the call history goes with them, as the page's choice says.
        bulk.setExpiry(ids, null)
        bulk.setExpiry(ids, 7)
        assertEquals(true, c.vault.summary(ada)!!.purgeHistory)
        assertEquals(true, c.temporaries.forKey(c.contacts.lookupKeyOf(bob)!!)!!.purgeHistory)
    }
}
