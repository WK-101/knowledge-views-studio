package app.parley.data.cases

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.Unlock
import app.parley.common.cases.CaseCall
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
import app.parley.common.cases.CaseState
import app.parley.data.DataContainer
import app.parley.data.backup.RestoreOptions
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Case files travel in the encrypted backup, restored with the contacts: the calls with their hold times, and the
 * reference numbers (unreadable in the file, sealed again with the new phone's key). This phone's own choice wins.
 */
@RunWith(RobolectricTestRunner::class)
class CaseFilesBackupRoundTripTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val passphrase = "correct horse battery staple"
    private val file by lazy { File(app.cacheDir, "cases.parleybackup") }
    private val bank = "+442079460000"
    private val council = "+442079460222"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    @Test fun caseFilesComeBack() = runBlocking {
        c.cases.update {
            val kept = CaseFiles.setMode(it, "Northwind Energy", listOf(bank), false, CaseMode.ON, 1, "GB", "n")
            val call = CaseCall(1_000, incoming = false, durationSec = 1_800, holdSec = 1_260, connected = true, menu = "31")
            CaseFiles.recordCall(kept, bank, call, false, "", false, "GB", "x")
        }
        assertTrue(c.cases.addReference("n", "Complaint", "CMP-77812345", typed = false))
        c.cases.update { CaseFiles.setMode(it, "Council", listOf(council), false, CaseMode.ON, 1, "GB", "k") }

        c.backup.setupKeys(passphrase.toCharArray())
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.message, out.ok)
        assertEquals(emptyList<String>(), out.failedSections)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        for (s in listOf("CMP-77812345", "Northwind Energy")) assertFalse(s, raw.contains(s))

        // The new phone: nothing of Northwind, and the council's case file stopped here.
        c.cases.update { CaseFiles.stop(CaseState(it.cases.filter { k -> k.id == "k" }), "k") }

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val report = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(contacts = true, settings = true))
        assertEquals(report.skipped.toString(), emptyList<String>(), report.skipped)

        val state = c.cases.state.value
        val northwind = CaseFiles.find(state, listOf(bank), "GB")!!
        assertEquals(listOf(1_260L), northwind.calls.map { it.holdSec })
        assertEquals("31", northwind.calls.single().menu)
        assertEquals("CMP-77812345", c.cases.openReference(northwind.references.single()))
        assertFalse(CaseFiles.find(state, listOf(council), "GB")!!.kept)
    }
}
