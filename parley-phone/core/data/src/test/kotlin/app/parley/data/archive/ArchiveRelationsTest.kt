package app.parley.data.archive

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Relation
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.people.RelationLinks
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Archiving takes back the relation rows Parley wrote on other contacts (SECURITY S3-07): "Child: Sam Lee" on Ana would
 * keep Sam's name in the address book and the synced account. Sam's own record keeps the relation, and Unarchive writes
 * Ana's side again.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveRelationsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        File(app.filesDir, "archive").deleteRecursively()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private suspend fun create(given: String, relations: List<DataItem> = emptyList()): Long =
        c.contacts.save(null, ContactDetails(given = given, family = "Lee", relations = relations), null, null, false)!!.contactId!!

    @Test fun archive_takes_the_name_off_other_contacts_and_unarchive_puts_it_back() = runBlocking {
        val ana = create("Ana")
        val mother = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_MOTHER))
        val sam = create("Sam", mother)
        val samKey = c.contacts.lookupKeyOf(sam)!!
        val links = mapOf("ana lee" to RelationLinks.Link(c.contacts.lookupKeyOf(ana)!!, ana))
        c.meta.setMeta(ContactMetaEntity(samKey, contactId = sam, relationLinks = RelationLinks.encode(links)))
        c.people.relationMirrors.mirror(sam, mother, links)
        assertEquals("Sam Lee", c.contacts.details(ana)!!.relations.single().value)

        val done = c.archive.archive(sam)!!
        assertTrue("Sam's name is off Ana's contact", c.contacts.details(ana)!!.relations.isEmpty())
        val kept = c.archive.readRecord(done.id)!!
        assertTrue("Sam's record keeps the relation", kept.raws.flatMap { it.rows }.any { it.mimeType == Relation.CONTENT_ITEM_TYPE })

        val back = c.archive.unarchive(done.id) as ArchiveStore.Unarchived.Done
        assertEquals("Ana Lee", c.contacts.details(back.contactId)!!.relations.single().value)
        assertEquals("Ana's side is written again", "Sam Lee", c.contacts.details(ana)!!.relations.single().value)
    }
}
