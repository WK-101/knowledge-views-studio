package app.parley.data

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseMode
import app.parley.data.security.RecordCrypto
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
 * The data self-check (SX-4): a healthy phone reports everything readable, and a deliberately broken sealed record is
 * reported while nothing on disk is changed, deleted or re-created.
 */
@RunWith(RobolectricTestRunner::class)
class DataSelfCheckTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val bank = "+442079460000"
    private val caseFolder by lazy { File(app.noBackupFilesDir, "docs/case_files") }

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

    /** Files of a folder, by name, so a before/after comparison catches any write, delete or re-create. */
    private fun snapshot(dir: File): Map<String, String> = dir.listFiles().orEmpty().associate { it.name to it.readText() }

    private suspend fun keepACaseFile() {
        c.cases.update { CaseFiles.setMode(it, "Northwind Energy", listOf(bank), false, CaseMode.ON, 1, "GB", "n") }
        assertTrue(c.cases.addReference("n", "Complaint", "CMP-77812345", typed = false))
    }

    @Test fun a_healthy_phone_reports_everything_readable() = runBlocking {
        keepACaseFile()
        c.backup.setupKeys("correct horse battery staple".toCharArray())
        c.backup.setFolder(Uri.fromFile(File(app.cacheDir, "backups")), "backups")

        val report = DataSelfCheck().run(c)

        assertTrue("nothing should be unreadable: ${report.items}", report.allClear)
        assertEquals(0, report.unreadable)
        // The case files were actually opened and counted.
        val cases = report.items.single { it.area == DataArea.SEALED_DOC && it.name == "case_files" }
        assertEquals(DataStatus.OK, cases.status)
        assertTrue(cases.rows > 0)
        // A backup can be produced, so there is a way back if anything ever breaks.
        assertEquals(DataStatus.OK, report.items.single { it.area == DataArea.BACKUP }.status)
    }

    @Test fun a_corrupted_sealed_record_is_reported_and_nothing_is_changed() = runBlocking {
        keepACaseFile()
        // Break one sealed part on disk: a value that looks sealed but can't be opened (not the index).
        val part = caseFolder.listFiles().orEmpty().single { it.name.endsWith(".sealed") && it.name != "index.sealed" }
        part.writeText(RecordCrypto.TEXT_PREFIX + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val before = snapshot(caseFolder)

        val report = DataSelfCheck().run(c)

        // It is reported, and the summary is no longer all-clear.
        val cases = report.items.single { it.area == DataArea.SEALED_DOC && it.name == "case_files" }
        assertEquals(DataStatus.UNREADABLE, cases.status)
        assertTrue(cases.unreadable >= 1)
        assertFalse(report.allClear)
        assertTrue(report.unreadable >= 1)
        // Nothing was changed, deleted or re-created: every file is byte-for-byte as it was.
        assertEquals(before, snapshot(caseFolder))
    }
}
