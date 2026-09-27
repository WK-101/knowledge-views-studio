package app.parley.data.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.db.AppDatabase
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Detail keys in generations, and a lost key never overwriting the sealed details on its own. */
@RunWith(RobolectricTestRunner::class)
class VaultKeyLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        VaultCrypto.appContext = context
        context.getSharedPreferences("vault_keys", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun currentDetailAlias(): String = FakeAndroidKeyStore.keys.keys.single { it.startsWith("parley_vault_detail") }

    @Test fun detail_blobs_name_their_key_generation_and_a_generation_is_never_reused() {
        val blob = VaultCrypto.sealDetail("x".toByteArray())
        assertEquals(1, VaultCrypto.generationOf(blob))
        assertArrayEquals("x".toByteArray(), VaultCrypto.openDetail(blob))
        FakeAndroidKeyStore.delete(currentDetailAlias())
        assertThrows(VaultCrypto.KeyLostException::class.java) { VaultCrypto.openDetail(blob) }
        val next = VaultCrypto.sealDetail("y".toByteArray())
        assertEquals(2, VaultCrypto.generationOf(next))
        assertThrows(VaultCrypto.KeyLostException::class.java) { VaultCrypto.openDetail(blob) }
    }

    @Test fun legacy_blobs_without_a_marker_still_open() {
        // Blobs of the first detail key (before generations) have no marker: generation 0.
        assertEquals(0, VaultCrypto.generationOf(byteArrayOf(12, 1, 2, 3)))
    }

    @Test fun a_lost_key_keeps_the_sealed_details_until_the_user_decides() = runBlocking {
        val vault = VaultRepository(context, db, scope)
        val id = vault.save(null, ContactDetails(given = "Grace", family = "Hopper", phones = listOf(DataItem(null, "+1 202 555 0100", 2)), note = "Admiral", pinnedNote = "Call after 6"))
        val before = db.vaultDao().get(id)!!.detailBlob
        FakeAndroidKeyStore.delete(currentDetailAlias())

        val d = vault.details(id)!!
        assertEquals("Grace Hopper", d.displayName)
        assertEquals("Call after 6", d.pinnedNote)
        assertTrue(vault.detailsLost(id))
        // Reading didn't write anything.
        assertArrayEquals(before, db.vaultDao().get(id)!!.detailBlob)

        assertTrue(vault.keepWhatIsLeft(id))
        assertNotEquals(before.toList(), db.vaultDao().get(id)!!.detailBlob.toList())
        assertFalse(vault.detailsLost(id))
        val aside = File(context.noBackupFilesDir, "vault-unreadable").listFiles().orEmpty()
        assertTrue(aside.any { it.readBytes().contentEquals(before) })
    }
}
