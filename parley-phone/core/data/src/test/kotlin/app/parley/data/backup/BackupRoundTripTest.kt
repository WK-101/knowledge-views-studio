package app.parley.data.backup

import android.Manifest
import android.app.Application
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.RuleType
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.Unlock
import app.parley.common.backup.WrongKeyException
import app.parley.common.circle.InteractionType
import app.parley.common.storage.PersistentStores
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * A full encrypted backup of one phone's data, a wipe, and a restore: contacts, blocking, speed dial, settings and the
 * feature sections of the persistent-store registry (contact notes, the Circle) come back, matched to the new contacts.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeContactsProvider
    private lateinit var c: DataContainer
    private val passphrase = "correct horse battery staple"
    private val file by lazy { File(app.cacheDir, "test.parleybackup") }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        provider = FakeContactsProvider.install()
        c = DataContainer(app)
        // DataStore instances are process-wide, so settings would carry over from the previous test.
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private suspend fun seed(): Long {
        val id = c.contacts.save(
            null, ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))), null, null, false,
        )!!.contactId
        val key = c.contacts.lookupKeyOf(id)!!
        c.blocks.saveRule(BlockRule(pattern = "+12025550143", type = RuleType.EXACT, note = "sales"))
        c.blocks.logBlocked("+12025550143", "rule", BlockAction.REJECT)
        c.prefs.setSpeedDial(2, "+44 20 7946 0000", "Ada")
        c.settings.update { it.copy(confirmBeforeCall = true, screening = it.screening.copy(blockHidden = true)) }
        c.meta.setMeta(ContactMetaEntity(key, pinnedNote = "Prefers mornings", contactId = id))
        c.meta.addCallNote(CallNoteEntity(numberKey = "442079460000", callDate = 1_000L, text = "Asked about the engine", createdAt = 1_000L))
        c.circle.interactions.log(key, id, InteractionType.MEET, null, 5_000L, "Tea at the Royal Society", "meet-1")
        return id
    }

    private suspend fun wipe() {
        withContext(Dispatchers.IO) { c.db.clearAllTables() }
        provider.exec("DELETE FROM data")
        provider.exec("DELETE FROM raw_contacts")
        c.settings.update { AppSettings() }
    }

    @Test fun securitySettingsWaitForConfirmation() = runBlocking {
        c.settings.update { it.copy(appLock = true, hideVault = true, confirmBeforeCall = true) }
        c.backup.setupKeys(passphrase.toCharArray())
        assertTrue(c.backup.backupNow(scheduled = false, target = Uri.fromFile(file)).ok)
        wipe()

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val report = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        // Ordinary settings come back; the app lock and discreet mode wait for the user.
        assertTrue(report.needsConfirmation)
        assertTrue(c.settings.current().confirmBeforeCall)
        assertFalse(c.settings.current().appLock)
        assertFalse(c.settings.current().hideVault)
        assertTrue(c.backup.applyPendingRestore())
        assertTrue(c.settings.current().appLock)
        assertTrue(c.settings.current().hideVault)
        assertFalse(c.backup.hasPendingRestore())
    }

    @Test fun everythingComesBackAfterAWipe() = runBlocking {
        seed()
        c.backup.setupKeys(passphrase.toCharArray())
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.message, out.ok)
        assertTrue(out.verified)
        assertEquals(1, out.contacts)
        assertEquals("every backed-up store of the registry has a section", emptyList<String>(), out.failedSections)
        // Nothing personal is readable in the file.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        for (s in listOf("Lovelace", "Prefers mornings", "Royal Society", "12025550143")) assertFalse(s, raw.contains(s))

        wipe()
        assertTrue(c.blocks.allRules().isEmpty())
        assertFalse(c.settings.current().confirmBeforeCall)

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val plan = c.backup.plan(opened, RestoreMode.MERGE)
        val report = c.backup.restore(opened, plan, RestoreOptions(settings = true))
        assertEquals(report.skipped.toString(), emptyList<String>(), report.skipped)
        assertEquals(1, report.added)
        assertEquals(1, report.rules)
        assertEquals(0, report.unmatched)

        val restored = c.contacts.loadNow().single()
        assertEquals("Ada Lovelace", restored.displayName)
        assertEquals(listOf("+44 20 7946 0000"), restored.phones.map { it.number })
        assertEquals("sales", c.blocks.allRules().single().note)
        assertEquals(1, c.db.blockDao().blockedCallsNow().size)
        assertEquals("Ada", c.prefs.speedDials.first().single { it.key == 2 }.label)
        val s = c.settings.current()
        assertTrue(s.confirmBeforeCall)
        assertTrue(s.screening.blockHidden)
        // Feature sections are matched to the restored contact, whose lookup key differs from the old one.
        assertEquals("Prefers mornings", c.meta.meta(restored.lookupKey)?.pinnedNote)
        assertEquals("Asked about the engine", c.meta.allCallNotesNow().single().text)
        val met = c.circle.interactions.interactionsFor(restored.lookupKey).single()
        assertEquals("Tea at the Royal Society", met.note)
        assertEquals(InteractionType.MEET, met.type)
    }

    @Test fun aRestoreRunTwiceAddsNothingTwice() = runBlocking {
        seed()
        c.backup.setupKeys(passphrase.toCharArray())
        assertTrue(c.backup.backupNow(scheduled = false, target = Uri.fromFile(file)).ok)
        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val report = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals(0, report.added)
        assertEquals(1, c.contacts.loadNow().size)
        assertEquals(1, c.meta.allCallNotesNow().size)
        assertEquals(1, c.circle.interactions.all().size)
    }

    @Test fun theWrongPassphraseOpensNothing() {
        runBlocking {
            c.backup.setupKeys(passphrase.toCharArray())
            assertTrue(c.backup.backupNow(scheduled = false, target = Uri.fromFile(file)).ok)
        }
        assertThrows(WrongKeyException::class.java) {
            runBlocking { c.backup.open(Uri.fromFile(file), Unlock.Passphrase("tr0ub4dor".toCharArray())) }
        }
    }

    @Test fun aBackupWithoutAFeaturePartSaysWhichSectionsAreMissing() = runBlocking {
        c.backup.setupKeys(passphrase.toCharArray())
        c.backup.extras = { emptyList() }
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.ok)
        val builtIn = with(PersistentStores.Sections) { setOf(CONTACTS, CALL_LOG, CALL_HISTORY, BLOCKING, SPEED_DIAL, SETTINGS, VAULT) }
        assertEquals(PersistentStores.requiredSections - builtIn, out.failedSections.toSet())
        assertNotEquals(emptyList<String>(), out.failedSections)
    }

    @Test fun aFailingPartIsNamedAndTheRestIsKept() = runBlocking {
        c.backup.setupKeys(passphrase.toCharArray())
        val parts = c.backup.extras()
        val broken = object : BackupExtras by parts.first() {
            override suspend fun export(): Map<String, String> = error("store unreadable")
        }
        c.backup.extras = { listOf(broken) + parts.drop(1) }
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.ok)
        assertEquals(listOf(broken.section), out.failedSections)
        val keys = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray())).reader.settings().orEmpty().keys
        for (p in parts.drop(1)) assertTrue(p.section, p.export().keys.all { it in keys })
        // Only the settings store itself is outside the feature prefix.
        assertTrue(keys.any { !it.startsWith(BackupExtras.PREFIX) } || c.settings.exportMap().isEmpty())
    }
}
