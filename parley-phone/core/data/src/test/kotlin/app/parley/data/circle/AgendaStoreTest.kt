package app.parley.data.circle

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.security.PinVerdict
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.security.Concealment
import app.parley.data.security.LockTransitions
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
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
import java.io.File

/**
 * The agenda over its real stores: a contact's note for calls, a number's notes, a private contact's sealed entry, and
 * what discreet mode and a duress unlock hide.
 */
@RunWith(RobolectricTestRunner::class)
class AgendaStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    private val adaNumber = "+44 20 7946 0000"
    private val unknown = "+44 20 7946 0999"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        File(app.noBackupFilesDir, "app_pin").delete()
        File(app.noBackupFilesDir, "app_lock_state").delete()
        Concealment.forgetForTest(newProcess = true)
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        Concealment.forgetForTest(newProcess = true)
        File(app.noBackupFilesDir, "app_lock_state").delete()
        c.scope.cancel()
        c.db.close()
    }

    private suspend fun ada(): AgendaTarget.Contact {
        c.contacts.save(null, ContactDetails(given = "Ada", phones = listOf(DataItem(null, adaNumber, Phone.TYPE_MOBILE))), null, null, false)
        return c.agenda.targetFor(adaNumber) as AgendaTarget.Contact
    }

    @Test fun a_contacts_items_live_in_their_note_for_calls() = runBlocking {
        val ada = ada()
        c.circle.editPinnedNote(ada.lookupKey, ada.contactId) { "Gate code 1234" }
        assertEquals(AgendaStore.Added.ADDED, c.agenda.add(ada, "Ask about the trip"))
        assertEquals(AgendaStore.Added.ALREADY, c.agenda.add(ada, "ask about the trip"))
        assertEquals(AgendaStore.Added.ADDED, c.agenda.add(ada, "The loan"))
        assertEquals(listOf("Ask about the trip", "The loan"), c.agenda.open(ada))
        // The note keeps its own text; the items are promise lines of it.
        assertEquals("Gate code 1234\n[ ] Ask about the trip\n[ ] The loan", c.meta.meta(ada.lookupKey)?.pinnedNote)

        val before = c.agenda.setDone(ada, "Ask about the trip", true)
        assertNotNull(before)
        assertEquals(CircleRepository.NoteSource.PINNED, before!!.source)
        assertEquals(listOf("The loan"), c.agenda.open(ada))
        // Ticking again changes nothing; opening it again brings it back.
        assertNull(c.agenda.setDone(ada, "Ask about the trip", true))
        assertNotNull(c.agenda.setDone(ada, "Ask about the trip", false))
        assertEquals(listOf("Ask about the trip", "The loan"), c.agenda.open(ada))
        // The Circle's notes see them as promises of the note for calls.
        val pinned = c.circle.notesFor(ada.lookupKey, emptyList()).single { it.source == CircleRepository.NoteSource.PINNED }
        assertEquals(2, pinned.promises.size)
    }

    @Test fun a_number_that_isnt_saved_keeps_its_items_on_its_notes() = runBlocking {
        val target = c.agenda.targetFor(unknown)
        assertTrue(target is AgendaTarget.Number)
        assertEquals(AgendaStore.Added.ADDED, c.agenda.add(target!!, "Quote for the boiler"))
        assertEquals(AgendaStore.Added.ALREADY, c.agenda.add(target, "Quote for the boiler"))
        assertEquals(listOf("Quote for the boiler"), c.agenda.open(target))
        assertEquals("[ ] Quote for the boiler", c.meta.allCallNotesNow().single().text)
        val before = c.agenda.setDone(target, "Quote for the boiler", true)
        assertEquals(CircleRepository.NoteSource.CALL, before?.source)
        assertEquals(emptyList<String>(), c.agenda.open(target))
        // Too short to be a number: nothing to keep things for.
        assertNull(c.agenda.targetFor("12"))
    }

    @Test fun a_private_contacts_items_stay_in_its_sealed_entry() = runBlocking {
        val bo = ContactDetails(given = "Bo", pinnedNote = "Side door", phones = listOf(DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE)))
        val id = c.vault.save(null, bo)
        val target = c.agenda.targetFor("+44 20 7946 0001")
        assertEquals(AgendaTarget.Private(id), target)
        assertEquals(AgendaStore.Added.ADDED, c.agenda.add(target!!, "Birthday plans"))
        assertEquals(listOf("Birthday plans"), c.agenda.open(target))
        assertEquals("Side door\n[ ] Birthday plans", c.vault.details(id)?.pinnedNote)
        // The caller-ID copy (readable while the phone is locked) keeps the note without the items.
        assertEquals("Side door", c.vault.callerCard(id)?.note)
        // Nothing in Parley's own table.
        assertNull(c.meta.meta(target.parleyKey!!)?.pinnedNote)

        // Hidden private contacts: no target, and nothing read.
        c.settings.update { it.copy(hideVault = true) }
        assertNull(c.agenda.targetFor("+44 20 7946 0001"))
        assertNull(c.agenda.open(target))
        assertEquals(AgendaStore.Added.LOCKED, c.agenda.add(target, "More"))
    }

    @Test fun a_duress_unlock_hides_the_items_and_loses_none() = runBlocking {
        val ada = ada()
        c.agenda.add(ada, "Ask about the trip")
        c.appPin.setPin("246810", duressSession = false)
        c.appPin.setDuress("1357")
        c.settings.update { it.copy(appLock = true) }
        val a = c.appPin.check("1357")
        LockTransitions.pinEntered(c, a)
        assertEquals(PinVerdict.DURESS, a.verdict)

        assertEquals(emptyList<String>(), c.agenda.open(ada))
        // One added during the session shows (in memory) but never replaces the hidden note.
        c.agenda.add(ada, "Something new")
        assertEquals(listOf("Something new"), c.agenda.open(ada))
        // The real PIN ends the session: the items are as they were.
        LockTransitions.pinEntered(c, c.appPin.check("246810"))
        assertEquals(listOf("Ask about the trip"), c.agenda.open(ada))
    }

    @Test fun forKey_tells_private_keys_from_lookup_keys() {
        assertEquals(AgendaTarget.Private(7), AgendaTarget.forKey("parley-private:7"))
        assertEquals(AgendaTarget.Contact("lk3", null), AgendaTarget.forKey("lk3"))
        assertNull(AgendaTarget.forKey(""))
        assertNull(AgendaTarget.forKey("parley-private:x"))
    }
}
