package app.parley.data.export

import android.Manifest
import android.app.Application
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.WrongKeyException
import app.parley.common.circle.InteractionType
import app.parley.common.vcard.CardNotes
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.PhoneEnv
import app.parley.common.PhoneIdentity
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The open export: private contacts only when asked, Parley's notes beside each card, an encrypted vCard that opens
 * with its passphrase only, and an import that brings private contacts back private (never into the address book).
 */
@RunWith(RobolectricTestRunner::class)
class ContactExportTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer
    private val pass = "otter plum seven lantern"
    private val words = CardNotes.Words("Notes", "For calls", "Who", { "Every $it days" }, "Call note", { it }, "Promises", { it.toString() })
    private fun file(name: String) = File(app.cacheDir, name).also { it.delete() }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private suspend fun seed() {
        val id = c.contacts.save(
            null, ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))), null, null, false,
        )!!.contactId
        val key = c.contacts.lookupKeyOf(id)!!
        c.meta.setMeta(ContactMetaEntity(key, pinnedNote = "Prefers mornings\n[ ] Send the paper", contactId = id))
        val line = PhoneIdentity.key("+44 20 7946 0000", PhoneEnv.countryIso(app))
        c.meta.addCallNote(CallNoteEntity(numberKey = line, callDate = 1_000L, text = "Asked about the engine", createdAt = 1_000L))
        c.circle.interactions.log(key, id, InteractionType.MEET, null, 5_000L, "Tea at the Royal Society", "meet-1")
        c.vault.save(
            null,
            ContactDetails(
                given = "Grace", family = "Hopper", phones = listOf(DataItem(null, "+1 202 555 0188", Phone.TYPE_MOBILE)),
                pinnedNote = "Call after 6", context = "Navy",
            ),
        )
    }

    private suspend fun wipe() {
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
        provider.exec("DELETE FROM data")
        provider.exec("DELETE FROM raw_contacts")
        c.contacts.refresh()
    }

    @Test fun aPlainExportLeavesPrivateContactsOutUnlessAsked() = runBlocking {
        seed()
        val plain = file("plain.vcf")
        val r = c.contactExport.export(Uri.fromFile(plain), ContactExport.Choice(ContactExport.Format.VCARD), words)
        assertEquals(1, r.exported)
        val text = plain.readText().replace("\r\n ", "")
        assertFalse(text.contains("Hopper"))
        assertTrue(text, text.contains("X-PARLEY-NOTE-FOR-CALLS:Prefers mornings\\n[ ] Send the paper"))
        assertTrue(text, text.contains("X-PARLEY-PROMISE:Send the paper"))
        assertTrue(text, text.contains("Asked about the engine"))
        assertTrue(text, text.contains("X-PARLEY-MOMENT"))

        val all = file("all.vcf")
        assertEquals(2, c.contactExport.export(Uri.fromFile(all), ContactExport.Choice(ContactExport.Format.VCARD, includePrivate = true), words).exported)
        val withPrivate = all.readText()
        assertTrue(withPrivate.contains("Hopper"))
        assertTrue(withPrivate.contains("X-PARLEY-PRIVATE:1"))
        assertTrue(withPrivate.contains("X-PARLEY-CONTEXT:Navy"))

        // CSV carries private contacts too, without notes.
        val csv = file("all.csv")
        assertEquals(2, c.contactExport.export(Uri.fromFile(csv), ContactExport.Choice(ContactExport.Format.CSV_PARLEY, includePrivate = true), words).exported)
        assertTrue(csv.readText().contains("Hopper"))
        assertFalse(csv.readText().contains("Prefers mornings"))

        // The notes alone, as text.
        val notes = file("notes.txt")
        c.contactExport.export(Uri.fromFile(notes), ContactExport.Choice(ContactExport.Format.NOTES_TEXT, includePrivate = true), words)
        val n = notes.readText()
        assertTrue(n, n.startsWith("Ada Lovelace\nFor calls: Prefers mornings"))
        assertTrue(n, n.contains("Grace Hopper\nFor calls: Call after 6\nWho: Navy"))
    }

    @Test fun anEncryptedExportComesBackWithPrivateContactsPrivate() = runBlocking {
        seed()
        val sealed = file("contacts.vcf.parley")
        val choice = ContactExport.Choice(ContactExport.Format.SEALED_VCARD, includePrivate = true)
        assertEquals(2, c.contactExport.export(Uri.fromFile(sealed), choice, words, pass.toCharArray()).exported)
        val raw = String(sealed.readBytes(), Charsets.ISO_8859_1)
        for (s in listOf("Hopper", "Lovelace", "Navy", "mornings")) assertFalse(s, raw.contains(s))
        assertTrue(c.vcards.isSealed(Uri.fromFile(sealed)))

        wipe()
        assertThrows(WrongKeyException::class.java) {
            runBlocking { c.vcards.import(Uri.fromFile(sealed), AccountRef(null, null), passphrase = "wrong one entirely".toCharArray()) }
        }
        val report = c.vcards.import(Uri.fromFile(sealed), AccountRef(null, null), passphrase = pass.toCharArray())
        assertEquals(report.failures.toString(), 2, report.imported)

        // Grace is private again, with her notes; only Ada is in the address book.
        val visible = c.contacts.loadNow()
        assertEquals(listOf("Ada Lovelace"), visible.map { it.displayName })
        val grace = c.vault.summariesNow().single()
        assertEquals("Grace Hopper", grace.name)
        val d = c.vault.details(grace.id)!!
        assertEquals("Call after 6", d.pinnedNote)
        assertEquals("Navy", d.context)
        assertEquals(listOf("+1 202 555 0188"), d.phones.map { it.value })

        // Ada's notes found her new contact.
        val ada = visible.single()
        assertEquals("Prefers mornings\n[ ] Send the paper", c.meta.meta(ada.lookupKey)?.pinnedNote)
        assertEquals(listOf("Asked about the engine"), c.meta.allCallNotesNow().map { it.text })
        assertEquals("Tea at the Royal Society", c.circle.interactions.interactionsFor(ada.lookupKey).single().note)

        // The same file again adds nothing twice.
        c.vcards.import(Uri.fromFile(sealed), AccountRef(null, null), skipDuplicates = true, passphrase = pass.toCharArray())
        assertEquals(1, c.vault.summariesNow().size)
        assertEquals(1, c.contacts.loadNow().size)
        assertEquals(1, c.meta.allCallNotesNow().size)
    }

    @Test fun aCardFromElsewhereBringsNoNotesAndNoneOnOtherNumbers() = runBlocking {
        val card = file("bank.vcf")
        card.writeText(
            listOf(
                "BEGIN:VCARD", "VERSION:4.0", "FN:Bank", "TEL:+44 20 7946 0000",
                "X-PARLEY-NOTE-FOR-CALLS:Confirmed safe\\, give the code",
                "X-PARLEY-KEEP-IN-TOUCH:7",
                "X-PARLEY-CALL-NOTE;X-WHEN=\"2025-10-04T10:00:00Z\";X-LINE=+12025550188:Bank: confirmed safe",
                "END:VCARD", "",
            ).joinToString("\r\n"),
        )
        val report = c.vcards.import(Uri.fromFile(card), AccountRef(null, null))
        assertEquals(report.failures.toString(), 1, report.imported)
        val bank = c.contacts.loadNow().single()
        assertEquals(null, c.meta.meta(bank.lookupKey)?.pinnedNote)
        assertTrue(c.meta.allCallNotesNow().isEmpty())
        assertFalse(c.circle.isMember(bank.lookupKey))
    }

    @Test fun aFailedExportRemovesTheFileSaveAsCreated() = runBlocking {
        val target = file("contacts.vcf.parley").also { it.writeText("") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { c.contactExport.export(Uri.fromFile(target), ContactExport.Choice(ContactExport.Format.SEALED_VCARD), words, CharArray(0)) }
        }
        assertFalse(target.exists())
    }
}
