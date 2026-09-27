package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.data.ContactChangedElsewhereException
import app.parley.data.ContactDetails
import app.parley.data.ContactEditRebase
import app.parley.data.ContactEditRebase.Field
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Saves assert the raw contact's version, so an edit made elsewhere meanwhile is never overwritten silently. */
@RunWith(RobolectricTestRunner::class)
class ContactVersionCheckTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> listOf(1L) }
    }

    @After fun tearDown() = scope.cancel()

    private fun create(): Long = runBlocking {
        repo.save(
            null,
            ContactDetails(given = "Ada", family = "Lovelace", note = "Engine", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))),
            null, null, false,
        )!!.contactId
    }

    /** What a sync adapter does: rewrite the number and bump the raw contact's version. */
    private fun changeElsewhere(number: String = "+44 20 7946 9999") {
        provider.exec("UPDATE data SET data1 = '$number' WHERE mimetype = '${Phone.CONTENT_ITEM_TYPE}'")
        provider.exec("UPDATE raw_contacts SET version = version + 1")
    }

    @Test fun theEditorLoadsTheVersionOfTheCopyItEdits() = runBlocking {
        val before = repo.editable(create())!!
        assertNotNull(before.editRawVersion)
        assertEquals(provider.rows("raw_contacts").single()["version"], before.editRawVersion.toString())
    }

    @Test fun aChangeMadeElsewhereStopsTheSaveBeforeAnythingIsWritten() = runBlocking {
        val before = repo.editable(create())!!
        changeElsewhere()
        provider.writes.clear()
        assertThrows(ContactChangedElsewhereException::class.java) {
            runBlocking { repo.save(before, before.copy(note = "Mine"), null, null, false) }
        }
        assertTrue(provider.writes.isEmpty())
        assertEquals("Engine", repo.details(before.id)!!.note)
    }

    @Test fun aChangeThatLandsDuringTheSaveFailsTheWholeBatch() = runBlocking {
        val before = repo.editable(create())!!
        // The journal runs between the early check and the batch: a sync writing then is caught by the assertion.
        repo.beforeChange = { _, _ -> changeElsewhere(); listOf(1L) }
        assertThrows(ContactChangedElsewhereException::class.java) {
            runBlocking { repo.save(before, before.copy(note = "Mine"), null, null, false) }
        }
        assertEquals("Engine", repo.details(before.id)!!.note)
        assertEquals("+44 20 7946 9999", repo.details(before.id)!!.phones.single().value)
    }

    @Test fun aCopyDeletedElsewhereIsReportedToo() = runBlocking {
        val before = repo.editable(create())!!
        provider.exec("UPDATE raw_contacts SET deleted = 1")
        assertThrows(ContactChangedElsewhereException::class.java) {
            runBlocking { repo.save(before, before.copy(note = "Mine"), null, null, false) }
        }
        Unit
    }

    @Test fun anUnchangedContactSavesAsBefore() = runBlocking {
        val before = repo.editable(create())!!
        assertNotNull(repo.save(before, before.copy(note = "Mine"), null, null, false))
        assertEquals("Mine", repo.details(before.id)!!.note)
    }

    @Test fun mergingKeepsTheirChangeAndMineWhenTheyTouchDifferentFields() = runBlocking {
        val base = repo.editable(create())!!
        val mine = base.copy(note = "Mine")
        changeElsewhere()
        val theirs = repo.editable(base.id)!!
        assertEquals(emptyList<ContactEditRebase.Conflict>(), ContactEditRebase.conflicts(base, mine, theirs))
        val merged = ContactEditRebase.rebase(base, mine, theirs, emptyMap())
        assertNotNull(repo.save(theirs, merged, null, null, false))
        val now = repo.details(base.id)!!
        assertEquals("Mine", now.note)
        assertEquals("+44 20 7946 9999", now.phones.single().value)
    }

    @Test fun aFieldBothChangedIsAConflictAndThePickWins() = runBlocking {
        val base = repo.editable(create())!!
        val mine = base.copy(phones = base.phones.map { it.copy(value = "+1 555 0100") }, note = "Mine")
        changeElsewhere()
        val theirs = repo.editable(base.id)!!
        val conflicts = ContactEditRebase.conflicts(base, mine, theirs)
        assertEquals(listOf(Field.PHONES), conflicts.map { it.field })
        assertEquals("+1 555 0100", conflicts.single().mine)
        assertEquals("+44 20 7946 9999", conflicts.single().theirs)

        val theirsPicked = ContactEditRebase.rebase(base, mine, theirs, mapOf(Field.PHONES to Side.THEIRS))
        assertEquals("+44 20 7946 9999", theirsPicked.phones.single().value)
        assertEquals("Mine", theirsPicked.note)

        val keepMine = ContactEditRebase.rebase(null, mine, theirs, emptyMap(), Side.MINE)
        assertEquals(theirs.phones.single().id, keepMine.phones.single().id)
        repo.save(theirs, keepMine, null, null, false)
        assertEquals("+1 555 0100", repo.details(base.id)!!.phones.single().value)
    }

    @Test fun rowsDeletedElsewhereComeBackAsNewRowsWhenMineWins() = runBlocking {
        val base = repo.editable(create())!!
        val mine = base.copy(phones = base.phones.map { it.copy(value = "+1 555 0100") })
        provider.exec("DELETE FROM data WHERE mimetype = '${Phone.CONTENT_ITEM_TYPE}'")
        provider.exec("UPDATE raw_contacts SET version = version + 1")
        val theirs = repo.editable(base.id)!!
        val keepMine = ContactEditRebase.rebase(base, mine, theirs, mapOf(Field.PHONES to Side.MINE))
        assertEquals(null, keepMine.phones.single().id)
        repo.save(theirs, keepMine, null, null, false)
        assertEquals("+1 555 0100", repo.details(base.id)!!.phones.single().value)
        Unit
    }

    @Test fun aDraftOfAnotherCopyKeepsOnlyRowIdsOfTheCopyItIsPutOn() {
        val draft = ContactDetails(
            id = 1, nameId = 10, noteId = 11, editRawId = 100, editRawVersion = 3,
            phones = listOf(
                DataItem(20, "+1 555 0100", Phone.TYPE_MOBILE), DataItem(21, "+1 555 0199", Phone.TYPE_WORK), DataItem(null, "+1 555 0142", Phone.TYPE_HOME),
            ),
        )
        val onto = ContactDetails(
            id = 2, nameId = 30, editRawId = 200, editRawVersion = 7, writableRawIds = listOf(200),
            phones = listOf(DataItem(40, "+1 555 0100", Phone.TYPE_MOBILE), DataItem(41, "+1 555 0177", Phone.TYPE_MOBILE)),
        )
        val adopted = ContactEditRebase.adopt(draft, onto)
        // Same number and type: that row of the new copy; the others are new rows. Nothing of the old copy is left.
        assertEquals(listOf(40L, null, null), adopted.phones.map { it.id })
        assertEquals(listOf("+1 555 0100", "+1 555 0199", "+1 555 0142"), adopted.phones.map { it.value })
        assertEquals(listOf(2L, 30L, null, 200L, 7L), listOf(adopted.id, adopted.nameId, adopted.noteId, adopted.editRawId, adopted.editRawVersion))
        assertTrue(ContactEditRebase.hasRowIds(draft))
        assertEquals(false, ContactEditRebase.hasRowIds(ContactDetails(phones = listOf(DataItem(null, "+1 555 0100")))))
    }
}
