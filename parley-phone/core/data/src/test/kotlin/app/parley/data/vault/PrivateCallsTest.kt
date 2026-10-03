package app.parley.data.vault

import android.content.Context
import android.provider.CallLog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.data.StartGate
import app.parley.data.db.AppDatabase
import app.parley.data.db.PrivateCallEntity
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Private calls: sealed with a software key wrapped by the Keystore, so listing them costs no Keystore operation per
 * call; calls sealed before with the caller-ID key still read and move to the new key once; the listing waits for the
 * full app; and they follow the history's retention.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateCallsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = StartGate()
    private lateinit var vault: VaultRepository

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        VaultCrypto.appContext = context
        File(context.noBackupFilesDir, PrivateCallSeal.KEY_FILE).delete()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        vault = VaultRepository(context, db, scope, gate)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test fun newCallsOpenWithoutTheKeystore() = runBlocking {
        val now = System.currentTimeMillis()
        repeat(50) { i -> assertTrue(vault.storePrivateCall(7, "+44 7700 900${100 + i}", "Ana", now - i * 1000L, 30, CallLog.Calls.INCOMING_TYPE)) }
        assertTrue("sealed with the calls' own key", db.vaultDao().allPrivateCalls().all { it.blob[0] == 1.toByte() })
        VaultCrypto.Meter.reset()
        val calls = vault.privateCallsNow()
        assertEquals(50, calls.size)
        assertEquals("Ana", calls.first().name)
        assertEquals(0, VaultCrypto.Meter.callerOpens.get())
        assertEquals(50, vault.privateCallCount(7))
        assertEquals(50, vault.privateCallsOf(7).size)
    }

    @Test fun olderCallsStillReadAndMoveToTheNewKeyOnce() = runBlocking {
        val now = System.currentTimeMillis()
        val old = VaultCrypto.sealCallerId(JSONObject().put("n", "+44 7700 900001").put("name", "Bo").put("v", true).toString().toByteArray())
        db.vaultDao().addPrivateCall(PrivateCallEntity(vaultId = 3, blob = old, date = now, durationSec = 12, type = CallLog.Calls.OUTGOING_TYPE))
        val before = vault.privateCallsNow().single()
        assertEquals("+44 7700 900001", before.number)
        assertTrue(before.video)

        // The listing starts with the full app, then re-seals what it opened with the caller-ID key.
        assertTrue(vault.privateCalls.value.isEmpty())
        gate.open()
        withTimeout(10_000) {
            while (db.vaultDao().allPrivateCalls().single().blob[0] != 1.toByte()) delay(20)
        }
        VaultCrypto.Meter.reset()
        assertEquals(before, vault.privateCallsNow().single())
        assertEquals(0, VaultCrypto.Meter.callerOpens.get())
        assertEquals(listOf(before), vault.privateCalls.first { it.isNotEmpty() })
    }

    @Test fun theListingWaitsForTheFullApp() = runBlocking {
        vault.storePrivateCall(1, "+44 7700 900001", "Ana", 1000, 5, CallLog.Calls.INCOMING_TYPE)
        delay(300)
        assertTrue("nothing listed in a process started for a call", vault.privateCalls.value.isEmpty())
        gate.open()
        assertEquals(1, vault.privateCalls.first { it.isNotEmpty() }.size)
    }
}
