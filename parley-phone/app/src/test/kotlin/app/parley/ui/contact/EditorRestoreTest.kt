package app.parley.ui.contact

import android.Manifest
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * An editor draft restored after the process was stopped, over a contact that meanwhile was linked, unlinked or
 * re-aggregated: a save never writes into rows of another raw contact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EditorRestoreTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        provider = FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        c = DataContainer(context)
    }

    @After fun tearDown() = c.scope.cancel()

    private fun create(given: String, number: String): Long = runBlocking {
        c.contacts.save(null, ContactDetails(given = given, phones = listOf(DataItem(null, number, Phone.TYPE_MOBILE))), null, null, false)!!.contactId
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("Timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun editor(contactId: Long, saved: Bundle? = null): EditorViewModel {
        val vm = EditorViewModel(c, if (saved == null) SavedStateHandle() else SavedStateHandle(mapOf("editor" to saved)))
        vm.start(EditorArgs(contactId))
        return vm
    }

    /** Opens [contactId], changes its number to [number], and returns the saved state the process would keep. */
    private fun editedDraft(contactId: Long, number: String): Bundle {
        val vm = editor(contactId)
        until("the contact to load") { vm.draft?.phones?.any { it.id != null } == true }
        vm.update { d -> d.copy(phones = d.phones.map { it.copy(value = number) }) }
        return vm.toBundle()
    }

    private fun restored(contactId: Long, saved: Bundle, number: String): EditorViewModel =
        editor(contactId, saved).also { vm -> until("the draft to come back") { vm.draft?.phones?.any { it.value == number } == true } }

    private fun dataIdsOf(rawId: Long) = provider.rows("data", "raw_contact_id = $rawId").map { (it["_id"] as String).toLong() }.toSet()

    private fun draftIds(d: ContactDetails) = (d.phones + d.emails).mapNotNull { it.id } + listOfNotNull(d.nameId)

    @Test fun anotherCopyJoinedTheContactTheDraftStillEditsItsOwnCopy() {
        val other = create("Ada", "+44 20 7946 5555") // its raw contact comes first, so the contact would now open on it
        val ada = create("Ada", "+44 20 7946 0000")
        val adaRaw = c.contacts.rawIds(ada).single()
        val otherRaw = c.contacts.rawIds(other).single()
        val saved = editedDraft(ada, "+44 20 7946 1111")
        provider.moveRaw(otherRaw, ada)
        val otherRows = provider.rows("data", "raw_contact_id = $otherRaw")

        val vm = restored(ada, saved, "+44 20 7946 1111")
        assertEquals(adaRaw, vm.original?.editRawId)
        vm.save()
        until("the save") { provider.rows("data", "raw_contact_id = $adaRaw").any { it["data1"] == "+44 20 7946 1111" } }
        assertEquals("the joined copy is untouched", otherRows, provider.rows("data", "raw_contact_id = $otherRaw"))
    }

    @Test fun theDraftsCopyLeftTheContactSoTheSaveAsksAndNeverWritesIntoTheOtherCopy() {
        val ada = create("Ada", "+44 20 7946 0000")
        val grace = create("Grace", "+1 555 0100")
        val adaRaw = c.contacts.rawIds(ada).single()
        val graceRaw = c.contacts.rawIds(grace).single()
        val saved = editedDraft(ada, "+44 20 7946 1111")
        // Another app relinks: Grace's copy now forms contact [ada], Ada's copy moved to contact [grace].
        provider.moveRaw(graceRaw, ada)
        provider.moveRaw(adaRaw, grace)
        val adaRows = provider.rows("data", "raw_contact_id = $adaRaw")
        val graceRows = provider.rows("data", "raw_contact_id = $graceRaw")

        val vm = restored(ada, saved, "+44 20 7946 1111")
        val draft = vm.draft!!
        assertEquals(graceRaw, draft.editRawId)
        assertTrue("only ids of the copy loaded now", dataIdsOf(graceRaw).containsAll(draftIds(draft)))
        val writes = provider.writes.size
        vm.save()
        until("the changed-elsewhere choices") { vm.conflict != null }
        assertEquals("nothing written before the user chose", writes, provider.writes.size)
        assertEquals(graceRows, provider.rows("data", "raw_contact_id = $graceRaw"))

        vm.keepMine()
        until("the save") { provider.rows("data", "raw_contact_id = $graceRaw").any { it["data1"] == "+44 20 7946 1111" } }
        assertEquals("the copy the draft was made of is untouched", adaRows, provider.rows("data", "raw_contact_id = $adaRaw"))
    }

    @Test fun aContactReAggregatedUnderAnotherIdIsFoundByItsKey() {
        val grace = create("Grace", "+1 555 0100")
        val ada = create("Ada", "+44 20 7946 0000")
        val adaRaw = c.contacts.rawIds(ada).single()
        val graceRaw = c.contacts.rawIds(grace).single()
        val saved = editedDraft(ada, "+44 20 7946 1111")
        provider.moveRaw(adaRaw, grace) // joined: contact [ada] is gone, its copy is now part of [grace]
        val graceRows = provider.rows("data", "raw_contact_id = $graceRaw")

        val vm = restored(ada, saved, "+44 20 7946 1111")
        assertEquals(grace, vm.original?.id)
        assertEquals(adaRaw, vm.original?.editRawId)
        vm.save()
        until("the save") { provider.rows("data", "raw_contact_id = $adaRaw").any { it["data1"] == "+44 20 7946 1111" } }
        assertEquals("no new contact", 2, provider.rows("raw_contacts").size)
        assertEquals(graceRows, provider.rows("data", "raw_contact_id = $graceRaw"))
    }

    @Test fun aDeletedContactsDraftAsksBeforeSavingAsANewContact() {
        val ada = create("Ada", "+44 20 7946 0000")
        val saved = editedDraft(ada, "+44 20 7946 1111")
        runBlocking { c.contacts.delete(listOf(ada)) }

        val vm = restored(ada, saved, "+44 20 7946 1111")
        assertTrue(draftIds(vm.draft!!).isEmpty())
        vm.save()
        until("the changed-elsewhere choices") { vm.conflict != null }
        assertNull(vm.conflict!!.theirs)
        vm.keepMine()
        until("the save") { provider.rows("data").any { it["data1"] == "+44 20 7946 1111" } }
        assertNotNull(provider.rows("raw_contacts").singleOrNull())
    }
}
