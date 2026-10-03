package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Relation
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.RelationLinks
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.db.AppDatabase
import app.parley.data.people.RelationMirrors
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** Two-way relations against a SQLite-backed Contacts Provider: what reaches the other contact, and what never does. */
@RunWith(RobolectricTestRunner::class)
class RelationMirrorsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: ContactsRepository
    private lateinit var db: AppDatabase
    private lateinit var mirrors: RelationMirrors
    private val journaled = ArrayList<Long>()

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { ids, _ -> journaled += ids; listOf(1L) }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        mirrors = RelationMirrors(app, repo, db.metaDao())
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun create(given: String, relations: List<DataItem> = emptyList()): Long =
        runBlocking { repo.save(null, ContactDetails(given = given, family = "Lee", relations = relations), null, null, false)!!.contactId }

    private fun relationsOf(id: Long) = runBlocking { repo.details(id)!!.relations }

    private fun link(id: Long) = RelationLinks.Link(repo.lookupKeyOf(id)!!, id)

    @Test fun a_mother_gets_the_child_back_and_undo_takes_it_away() = runBlocking {
        val ana = create("Ana")
        val mother = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_MOTHER))
        val sam = create("Sam", mother)
        journaled.clear()
        val report = mirrors.mirror(sam, mother, mapOf("ana lee" to link(ana)))
        assertEquals(1, report.done.size)
        val back = relationsOf(ana).single()
        assertEquals("Sam Lee", back.value)
        assertEquals(Relation.TYPE_CHILD, back.type)
        // Written like any edit in Parley: journaled for History & undo.
        assertEquals(listOf(ana), journaled)
        // Saving again changes nothing.
        assertTrue(mirrors.mirror(sam, mother, mapOf("ana lee" to link(ana))).done.isEmpty())

        assertEquals(1, mirrors.undo(sam, report.done))
        assertTrue(relationsOf(ana).isEmpty())
    }

    @Test fun a_relation_to_a_private_contact_is_never_written_to_the_address_book() = runBlocking {
        // A namesake in the address book must not get the row either: the private key is never resolved there.
        val namesake = create("Ana")
        val mother = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_MOTHER))
        val sam = create("Sam", mother)
        journaled.clear()
        val report = mirrors.mirror(sam, mother, mapOf("ana lee" to RelationLinks.Link("parley-private:7", -7)))
        assertTrue(report.isEmpty)
        assertTrue(relationsOf(namesake).isEmpty())
        assertTrue(journaled.isEmpty())
        assertTrue("nothing is recorded about the private contact", !mirrors.any())
    }

    @Test fun removing_or_retyping_follows_only_rows_parley_added() = runBlocking {
        val ana = create("Ana")
        val friend = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_FRIEND))
        val sam = create("Sam", friend)
        mirrors.mirror(sam, friend, mapOf("ana lee" to link(ana)))
        assertEquals(Relation.TYPE_FRIEND, relationsOf(ana).single().type)

        // Sam's relation becomes "Manager": Ana's row Parley added becomes "Assistant".
        val manager = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_MANAGER))
        mirrors.mirror(sam, manager, mapOf("ana lee" to link(ana)))
        assertEquals(Relation.TYPE_ASSISTANT, relationsOf(ana).single().type)

        // The user changes Ana's row by hand; then Sam's relation is removed: Ana's row stays.
        val edited = repo.editable(ana)!!
        repo.save(edited, edited.copy(relations = edited.relations.map { it.copy(type = Relation.TYPE_BROTHER) }), null, null, false)
        mirrors.mirror(sam, emptyList(), emptyMap())
        assertEquals(Relation.TYPE_BROTHER, relationsOf(ana).single().type)
    }

    @Test fun an_existing_relation_written_by_the_user_is_never_doubled() = runBlocking {
        val ana = create("Ana", listOf(DataItem(value = "Sam Lee", type = Relation.TYPE_BROTHER)))
        val sister = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_SISTER))
        val sam = create("Sam", sister)
        val report = mirrors.mirror(sam, sister, mapOf("ana lee" to link(ana)))
        assertTrue(report.done.isEmpty())
        assertEquals(listOf(Relation.TYPE_BROTHER), relationsOf(ana).map { it.type })
    }

    @Test fun a_contact_leaving_the_address_book_takes_back_only_unchanged_rows_and_its_name() = runBlocking {
        val ana = create("Ana")
        val ben = create("Ben")
        val family = listOf(DataItem(value = "Ana Lee", type = Relation.TYPE_MOTHER), DataItem(value = "Ben Lee", type = Relation.TYPE_BROTHER))
        val sam = create("Sam", family)
        mirrors.mirror(sam, family, mapOf("ana lee" to link(ana), "ben lee" to link(ben)))
        assertEquals("Sam Lee", relationsOf(ana).single().value)
        // The user rewrote Ben's row in their own words: it is theirs now.
        val benNow = repo.editable(ben)!!
        repo.save(benNow, benNow.copy(relations = listOf(benNow.relations.single().copy(value = "Sammy"))), null, null, false)
        val prefs = app.getSharedPreferences("relation_mirrors", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.getString("created", null).orEmpty().contains("Sam Lee"))

        assertEquals(1, mirrors.takeBack(sam, repo.lookupKeyOf(sam)!!))
        assertTrue("Parley's own row on Ana goes", relationsOf(ana).isEmpty())
        assertEquals("the user's row on Ben stays", "Sammy", relationsOf(ben).single().value)
        assertTrue("no record of Sam's name stays", prefs.getString("created", null).isNullOrEmpty())
        assertEquals(0, mirrors.takeBack(sam, repo.lookupKeyOf(sam)!!))
    }
}
