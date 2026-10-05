package app.parley.data.contacts

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import app.parley.common.people.ContactSearch
import app.parley.common.people.NativeName
import app.parley.common.people.NativeNames
import app.parley.common.record.Mime
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactDraftJson
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.RecordDetails
import app.parley.data.messaging.Romanizer
import app.parley.data.people.DetailsSearch
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The name in their own language (a labelled nickname row beside the everyday name), several languages in order, and
 * citizenship, through the address book, private contacts, drafts and the search's Latin spellings.
 */
@RunWith(RobolectricTestRunner::class)
class NamesLanguagesWriteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var provider: FakeContactsProvider
    private lateinit var repo: ContactsRepository

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        repo = ContactsRepository(app, scope)
        repo.beforeChange = { _, _ -> emptyList() }
    }

    @After fun tearDown() = scope.cancel()

    private val ivan = ContactDetails(
        given = "Ivan", family = "Petrov", nickname = "Vanya",
        nativeName = NativeName("Иван Петров", "Иван", "Петров", "Russian"),
        languages = listOf("Russian", "English"), citizenships = listOf("RU", "Germany"),
        phones = listOf(DataItem(null, "+7 900 000 0000", Phone.TYPE_MOBILE)),
    )

    private fun rows(mime: String) = provider.rows("data").filter { it["mimetype"] == mime }

    @Test fun theNativeNameIsALabelledNicknameBesideTheEverydayName() = runBlocking {
        val id = repo.save(null, ivan, AccountRef(null, null), null, false)!!.contactId
        // What other apps see: the main name unchanged, and a nickname labelled "Name in Russian".
        assertEquals("Ivan", rows(StructuredName.CONTENT_ITEM_TYPE).single()["data2"])
        val nicknames = rows(Nickname.CONTENT_ITEM_TYPE)
        assertEquals(setOf("Vanya", "Иван Петров"), nicknames.map { it["data1"] }.toSet())
        val native = nicknames.single { it["data1"] == "Иван Петров" }
        assertEquals("0", native["data2"])
        assertEquals("Name in Russian", native["data3"])
        assertEquals("ru", native["data4"])
        assertEquals("Иван" to "Петров", native["data5"] to native["data6"])

        val back = repo.editable(id)!!
        assertEquals("Vanya", back.nickname)
        assertEquals(NativeName("Иван Петров", "Иван", "Петров", "ru"), back.nativeName)
        assertEquals("Иван Петров", repo.nativeNameOf(id))
        // The contact page reads it too.
        assertEquals("Иван Петров", repo.details(id)!!.nativeName.full)
    }

    @Test fun languagesAreRowsInOrderTheFirstPrimary() = runBlocking {
        val id = repo.save(null, ivan, AccountRef(null, null), null, false)!!.contactId
        val written = rows(Mime.LANGUAGE)
        assertEquals(listOf("ru", "en"), written.map { it["data1"] })
        assertEquals("1", written.first()["is_primary"]?.toString())
        val back = repo.editable(id)!!
        assertEquals(listOf("ru", "en"), back.languages)
        // Reordered: the same rows, written again in place, so the first row is still the one to use with them.
        repo.save(back, back.copy(languages = listOf("en", "ru", "de")), null, null, false)
        assertEquals(listOf("en", "ru", "de"), rows(Mime.LANGUAGE).map { it["data1"] })
        val again = repo.editable(id)!!
        repo.save(again, again.copy(languages = listOf("en")), null, null, false)
        assertEquals(listOf("en"), rows(Mime.LANGUAGE).map { it["data1"] })
    }

    @Test fun citizenshipIsKeptAsIsoCodes() = runBlocking {
        val id = repo.save(null, ivan, AccountRef(null, null), null, false)!!.contactId
        assertEquals(listOf("RU", "DE"), rows(Mime.CITIZENSHIP).map { it["data1"] })
        val back = repo.editable(id)!!
        assertEquals(listOf("RU", "DE"), back.citizenships)
        repo.save(back, back.copy(citizenships = listOf("DE")), null, null, false)
        assertEquals(listOf("DE"), rows(Mime.CITIZENSHIP).map { it["data1"] })
    }

    @Test fun anUntouchedSaveWritesNothingAndRemovingTheNativeNameKeepsTheNickname() = runBlocking {
        val id = repo.save(null, ivan, AccountRef(null, null), null, false)!!.contactId
        val back = repo.editable(id)!!
        provider.writes.clear()
        repo.save(back, back, null, null, false)
        assertTrue(provider.writes.none { it.path.startsWith("data") })
        repo.save(back, back.copy(nativeName = NativeName()), null, null, false)
        assertEquals(listOf("Vanya"), rows(Nickname.CONTENT_ITEM_TYPE).map { it["data1"] })
        assertTrue(repo.editable(id)!!.nativeName.isBlank)
    }

    @Test fun privateContactsDraftsAndCardsKeepThem() {
        val sealed = ContactDetailsJson.decode(ContactDetailsJson.encode(ivan))
        assertEquals(ivan.nativeName, sealed.nativeName)
        assertEquals(ivan.languages, sealed.languages)
        assertEquals(ivan.citizenships, sealed.citizenships)
        assertEquals(ivan, ContactDraftJson.decode(ContactDraftJson.encode(ivan)))
        val record = RecordDetails.toRecord(ivan.copy(languages = listOf("ru", "en"), citizenships = listOf("RU", "DE")), "k")
        val d = RecordDetails.toDetails(record)
        assertEquals("Vanya", d.nickname)
        assertEquals(NativeName("Иван Петров", "Иван", "Петров", "ru"), d.nativeName)
        assertEquals(listOf("ru", "en"), d.languages)
        assertEquals(listOf("RU", "DE"), d.citizenships)
        assertTrue(!RecordDetails.hasHiddenFields(record))
    }

    @Test fun aSingleStoredLanguageBecomesAListOfOne() {
        // A private contact and an editor draft saved before languages were a list.
        assertEquals(listOf("es"), ContactDetailsJson.decode("""{"given":"Ana","lang":"es"}""").languages)
        val draft = ContactDraftJson.decode("""{"given":"Ana","languageId":5,"lang":"es"}""")
        assertEquals(listOf("es"), draft.languages)
        assertEquals(listOf(5L), draft.languageIds)
        assertEquals(emptyList<String>(), ContactDetailsJson.decode("""{"given":"Ana"}""").languages)
    }

    @Test fun icuSpellsOtherScriptsInLatinLetters() {
        assertEquals("ivan petrov", Romanizer.latin("Иван Петров")?.lowercase())
        assertEquals("wang wei", Romanizer.latin("王伟")?.lowercase())
        assertEquals("giorgos", Romanizer.latin("Γιώργος")?.lowercase())
        assertTrue(Romanizer.latin("محمد").orEmpty().lowercase().startsWith("m"))
        assertTrue(Romanizer.latin("김민준").orEmpty().lowercase().replace(" ", "").startsWith("gim"))
        assertNull("accented Latin needs no spelling", Romanizer.latin("José Ñúñez"))
        assertEquals("Ivan Petrov", Romanizer.spelling("иван петров"))
    }

    @Test fun aPrivateContactIsFoundByEitherNameAndBySpelling() {
        val d = ivan.copy(given = "Иван", family = "Петров", nativeName = NativeName("王伟", language = "zh"))
        val doc = DetailsSearch.doc(-3, d, emptyList(), "RU")
        assertEquals(ContactSearch.Field.NAME, ContactSearch.match("ivan", doc))
        assertEquals(ContactSearch.Field.NATIVE_NAME, ContactSearch.match("王", doc))
        assertEquals(ContactSearch.Field.NATIVE_NAME, ContactSearch.match("wang", doc))
        assertEquals(ContactSearch.Field.CITIZENSHIP, ContactSearch.match("germany", doc))
        assertTrue(NativeNames.isRow("0", NativeNames.label("ru")))
    }
}
