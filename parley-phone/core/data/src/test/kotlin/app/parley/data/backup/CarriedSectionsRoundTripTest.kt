package app.parley.data.backup

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.Unlock
import app.parley.common.calls.CallerTune
import app.parley.common.calls.CarDevice
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import app.parley.common.calls.Helper
import app.parley.common.calls.SafeWord
import app.parley.data.DataContainer
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * What moved into backups so a new phone keeps it: family safety (safe words, helpers, expected-call windows), ringtones
 * made from a name (the audio files) and the drive and abroad switches. This phone's own wins where both have something,
 * and what belongs to this phone (the cars) stays.
 */
@RunWith(RobolectricTestRunner::class)
class CarriedSectionsRoundTripTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val passphrase = "correct horse battery staple"
    private val file by lazy { File(app.cacheDir, "carried.parleybackup") }
    private val tunes by lazy { File(app.filesDir, "tunes") }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
        tunes.deleteRecursively()
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    @Test fun familySafetyTunesAndSwitchesComeBack() = runBlocking {
        val now = System.currentTimeMillis()
        val safety = c.familySafety
        assertTrue(safety.setSafeWord("Family", SafeWord("First pet?", "Rexford the terrier")))
        assertTrue(safety.setSafeWord("Work", SafeWord("Floor?", "Third")))
        assertTrue(safety.setHelpers(listOf(Helper("Samira Okonkwo", "+44 7700 900001"))))
        assertTrue(safety.setConsent(ExpectedSource.DELIVERY_QR, true))
        assertTrue(safety.putWindow(ExpectedWindow(now, now + 3_600_000, ExpectedSource.DELIVERY_QR, "parcel"), now))
        val tune = CallerTune.wav(CallerTune.render(CallerTune.compose("Ada", 1)))
        val tuneName = CallerTune.fileName("Ada", 1)
        tunes.mkdirs()
        File(tunes, tuneName).writeBytes(tune)
        File(tunes, "notes.txt").writeText("not a tune")
        c.driveProfile.update { it.copy(cars = listOf(CarDevice("00:11:22:33:44:55", "Car")), answerFavourites = true) }
        c.roaming.update { it.copy(localSimHint = false) }

        c.backup.setupKeys(passphrase.toCharArray())
        val out = c.backup.backupNow(scheduled = false, target = Uri.fromFile(file))
        assertTrue(out.message, out.ok)
        assertEquals(emptyList<String>(), out.failedSections)
        // Safe words are secrets: nothing of them is readable in the file. The strings looked for are long: the file is
        // ciphertext, where any three given letters turn up by chance in a few runs out of a hundred.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        for (s in listOf("Rexford the terrier", "First pet", "Samira Okonkwo", "parley-tune")) assertFalse(s, raw.contains(s))

        // The new phone: its own safe word for Family, no helpers, no tune, other switches, its own car.
        assertTrue(safety.setSafeWord("Family", SafeWord("Street?", "Elm")))
        assertTrue(safety.setSafeWord("Work", null))
        assertTrue(safety.setHelpers(emptyList()))
        assertTrue(safety.removeWindow(ExpectedSource.DELIVERY_QR, "parcel"))
        tunes.deleteRecursively()
        c.driveProfile.update { it.copy(cars = listOf(CarDevice("66:77:88:99:AA:BB", "New car")), answerFavourites = false) }
        c.roaming.update { it.copy(localSimHint = true) }

        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val report = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals(report.skipped.toString(), emptyList<String>(), report.skipped)

        assertEquals(SafeWord("Street?", "Elm"), safety.safeWord("Family"))
        assertEquals(SafeWord("Floor?", "Third"), safety.safeWord("Work"))
        assertEquals(listOf("Samira Okonkwo"), safety.helpers().map { it.name })
        assertEquals(listOf("parcel"), safety.windows(now + 1000).map { it.key })
        assertArrayEquals(tune, File(tunes, tuneName).readBytes())
        assertFalse(File(tunes, "notes.txt").exists())
        assertTrue(c.driveProfile.config.value.answerFavourites)
        assertEquals(listOf("New car"), c.driveProfile.config.value.cars.map { it.name })
        assertFalse(c.roaming.config.value.localSimHint)
    }

    @Test fun aBackupWithoutTheNewPartsStillRestores() = runBlocking {
        // A backup of an older version has none of these parts: nothing here changes, nothing fails.
        c.backup.setupKeys(passphrase.toCharArray())
        c.backup.tuneFiles = null
        val parts = c.backup.extras().filter { it !is FamilySafetyBackup && it !is CallSwitchesBackup }
        c.backup.extras = { parts }
        assertTrue(c.backup.backupNow(scheduled = false, target = Uri.fromFile(file)).ok)
        c.backup.tuneFiles = CallerTuneFiles(app)
        assertTrue(c.familySafety.setSafeWord("Family", SafeWord("Pet?", "Rex")))
        val opened = c.backup.open(Uri.fromFile(file), Unlock.Passphrase(passphrase.toCharArray()))
        val report = c.backup.restore(opened, c.backup.plan(opened, RestoreMode.MERGE), RestoreOptions(settings = true))
        assertEquals(report.skipped.toString(), emptyList<String>(), report.skipped)
        assertEquals(SafeWord("Pet?", "Rex"), c.familySafety.safeWord("Family"))
    }
}
