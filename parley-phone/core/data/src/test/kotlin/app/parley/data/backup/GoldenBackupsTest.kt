package app.parley.data.backup

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.BackupArchive
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.Unlock
import app.parley.common.circle.InteractionType
import app.parley.data.DataContainer
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Backups made by older releases still restore (AUDIT GP-3). The files in `test/resources/golden` were written by the
 * 5.0.0, 5.7.0 and 6.0.0 code itself (their BackupRepository under Robolectric, from the release commits), with the
 * same data: Ada with a note for calls, a pinned note and a Circle moment; Grace with a photo; a private contact; a
 * block rule and a blocked call; speed dial 2; two settings. Each is restored here on an empty phone and checked.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenBackupsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

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

    @Test fun backup_from_5_0_restores() = restores("5.0")

    @Test fun backup_from_5_7_restores() = restores("5.7")

    @Test fun backup_from_6_0_restores() = restores("6.0")

    private fun restores(version: String) = runBlocking {
        val file = File(app.cacheDir, "golden-$version.parleybackup")
        javaClass.getResourceAsStream("/golden/backup-$version.parleybackup")!!.use { input -> file.outputStream().use { input.copyTo(it) } }

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(PASSPHRASE.toCharArray()))
        assertEquals(2L, opened.counts[BackupArchive.Counts.CONTACTS])
        assertEquals(1L, opened.counts[BackupArchive.Counts.PHOTOS])
        val plan = c.backup.plan(opened, RestoreMode.MERGE)
        val report = c.backup.restore(opened, plan, RestoreOptions(settings = true))
        assertEquals("$version: ${report.skipped}", emptyList<String>(), report.skipped)
        assertEquals(2, report.added)
        assertEquals(1, report.rules)
        assertEquals(1, report.vault)
        assertEquals(0, report.missingPhotos)

        val people = c.contacts.loadNow().associateBy { it.displayName }
        val ada = people.getValue("Ada Lovelace")
        assertEquals(listOf("+44 20 7946 0000"), ada.phones.map { it.number })
        val grace = people.getValue("Grace Hopper")
        val photo = c.records.read(grace.id, fullPhoto = true)!!.raws.flatMap { it.rows }.single { it.blob != null }.blob
        assertArrayEquals("$version: the photo comes back whole", ByteArray(1500) { (it * 13 + 7).toByte() }, photo)

        assertEquals("sales", c.blocks.allRules().single().note)
        assertEquals(1, c.db.blockDao().blockedCallsNow().size)
        assertEquals("Ada", c.prefs.speedDials.first().single { it.key == 2 }.label)
        val s = c.settings.current()
        assertTrue(s.confirmBeforeCall)
        assertTrue(s.screening.blockHidden)
        assertEquals("Prefers mornings", c.meta.meta(ada.lookupKey)?.pinnedNote)
        assertEquals("Asked about the engine", c.meta.allCallNotesNow().single().text)
        val met = c.circle.interactions.interactionsFor(ada.lookupKey).single()
        assertEquals("Tea at the Royal Society", met.note)
        assertEquals(InteractionType.MEET, met.type)
        assertEquals(listOf("Private Person"), c.vault.summariesNow().map { it.name })
        opened.reader.close()
    }

    private companion object {
        const val PASSPHRASE = "golden backup passphrase"
    }
}
