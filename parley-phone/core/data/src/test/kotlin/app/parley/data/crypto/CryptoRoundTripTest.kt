package app.parley.data.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.InteractionType
import app.parley.data.circle.InteractionStore
import app.parley.data.db.AppDatabase
import app.parley.data.history.HistoryCrypto
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Seal and open round trips for the vault keys, the call-history archive envelope and interaction notes. */
@RunWith(RobolectricTestRunner::class)
class CryptoRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun vaultCallerIdAndDetailKeysRoundTrip() {
        val plain = "Ada Lovelace · +44 20 7946 0000".toByteArray()
        val caller = VaultCrypto.sealCallerId(plain)
        val detail = VaultCrypto.sealDetail(plain)
        assertArrayEquals(plain, VaultCrypto.openCallerId(caller))
        assertArrayEquals(plain, VaultCrypto.openDetail(detail))
        // Fresh IV per seal, and nothing readable in the blob.
        assertFalse(VaultCrypto.sealCallerId(plain).contentEquals(caller))
        assertFalse(String(caller, Charsets.ISO_8859_1).contains("Lovelace"))
        // The two keys are distinct: a caller-ID blob doesn't open with the detail key.
        assertThrows(Exception::class.java) { VaultCrypto.openDetail(caller) }
    }

    @Test fun vaultBlobFromALostKeyNeverOpens() {
        val blob = VaultCrypto.sealCallerId("secret".toByteArray())
        FakeAndroidKeyStore.delete("parley_vault_callerid_v1")
        assertThrows(Exception::class.java) { VaultCrypto.openCallerId(blob) }
    }

    @Test fun vaultHmacIsStableAndKeyed() {
        val a = VaultCrypto.hmac("+15551234567")
        assertEquals(a, VaultCrypto.hmac("+15551234567"))
        assertNotEquals(a, VaultCrypto.hmac("+15551234568"))
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun historyArchiveRoundTripAndKeyPersistsAcrossInstances() {
        File(context.noBackupFilesDir, "history.keys").delete()
        val first = HistoryCrypto(context)
        val blob = first.seal("call from +15551234567".toByteArray())
        val mac = first.mac("+15551234567")
        assertEquals(32, mac.length)
        // A new instance (process restart) unwraps the stored data key and opens old rows.
        val second = HistoryCrypto(context)
        assertEquals("call from +15551234567", String(second.open(blob)))
        assertEquals(mac, second.mac("+15551234567"))
        assertThrows(IllegalArgumentException::class.java) { second.open(byteArrayOf(9, 1, 2)) }
    }

    @Test fun historyArchiveReportsALostWrappingKeyAndRecoversAfterReset() {
        File(context.noBackupFilesDir, "history.keys").delete()
        HistoryCrypto(context).seal("x".toByteArray())
        FakeAndroidKeyStore.delete("parley_history_wrap_v1")
        val crypto = HistoryCrypto(context)
        assertThrows(HistoryCrypto.KeyLostException::class.java) { crypto.seal("y".toByteArray()) }
        crypto.reset("lost")
        // The old wrapped key is kept aside, never deleted; new rows go under a fresh key.
        assertTrue(File(context.noBackupFilesDir, "history.keys.lost").exists())
        assertEquals("z", String(crypto.open(crypto.seal("z".toByteArray()))))
    }

    private lateinit var db: AppDatabase

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test fun interactionNotesAreSealedAtRestAndOpenedOnRead() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val store = InteractionStore(db.interactionDao())
        val id = store.log("key1", 7L, InteractionType.MEET, InteractionChannel.SMS, 1_000L, "  Lunch at Rosa's  ", "d1")!!
        assertNull("the same dedupe key records once", store.log("key1", 7L, InteractionType.MEET, null, 2_000L, null, "d1"))

        val raw = db.interactionDao().get(id)!!
        assertFalse(String(raw.noteBlob!!, Charsets.ISO_8859_1).contains("Rosa"))
        assertEquals("Lunch at Rosa's", store.interactionsFor("key1").single().note)

        store.setNote(id, "")
        assertNull(db.interactionDao().get(id)!!.noteBlob)
        store.edit(id, InteractionType.VIDEO, "Call back")
        assertEquals("Call back", store.noteOf(id))
        assertEquals(InteractionType.VIDEO, store.interactionsFor("key1").single().type)

        // A note whose key is gone reads as none; the entry itself stays.
        FakeAndroidKeyStore.delete("parley_vault_callerid_v1")
        assertNull(store.noteOf(id))
        assertEquals(1, store.all().size)
    }
}
