package app.parley.data.vault

import android.Manifest
import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.WorkProfile
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * "Recently deleted" for private contacts on its own: what is kept is sealed (no name or number in the file), a file
 * outside its folder is never touched, an unreadable copy is skipped, and removing or clearing is for good.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateTrashTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val dir get() = File(app.noBackupFilesDir, "vault_trash")

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        WorkProfile::class.java.getDeclaredField("cached").apply { isAccessible = true }.set(null, null)
        VaultCrypto.appContext = app
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
        dir.deleteRecursively()
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private fun ada(): Long = runBlocking {
        c.vault.save(null, ContactDetails(given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE))))
    }

    @Test fun a_kept_copy_is_sealed_and_names_its_key_generation() = runBlocking {
        val trash = c.privateTrash
        val stamp = trash.memoryStamp()
        val file = trash.keepFile(ada())!!
        val bytes = File(dir, file).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("no name in the file", "Lovelace" in bytes)
        assertFalse("no number in the file", "7946" in bytes)
        assertEquals(1, trash.generations().size)
        assertNotEquals(stamp, trash.memoryStamp())
        assertEquals("Ada Lovelace", trash.list().single().name)
    }

    @Test fun nothing_outside_its_folder_is_restored_or_removed() = runBlocking {
        val outside = File(app.noBackupFilesDir, "elsewhere.bin").apply { writeText("keep me") }
        assertNull(c.privateTrash.restore("../elsewhere.bin"))
        c.privateTrash.remove("../elsewhere.bin")
        assertTrue(outside.exists())
    }

    @Test fun an_unreadable_copy_is_skipped_not_fatal() = runBlocking {
        c.privateTrash.keep(ada())
        File(dir, "${System.currentTimeMillis()}-9-g1.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(2, c.privateTrash.count())
        assertEquals(listOf("Ada Lovelace"), c.privateTrash.list().map { it.name })
    }

    @Test fun removing_one_and_clearing_all_are_for_good() = runBlocking {
        val first = c.privateTrash.keepFile(ada())!!
        c.privateTrash.keep(ada())
        c.privateTrash.remove(first)
        assertEquals(1, c.privateTrash.count())
        assertEquals(1, c.privateTrash.clear())
        assertEquals(0, c.privateTrash.count())
        assertTrue(c.privateTrash.list().isEmpty())
    }
}
