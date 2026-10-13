package app.parley.data.vault

import android.content.Context
import android.provider.CallLog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.data.StartGate
import app.parley.data.db.AppDatabase
import app.parley.data.db.PrivateCallEntity
import app.parley.data.security.LineKeys
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
import org.junit.Assert.assertFalse
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
        vault = VaultRepository(context, db, scope, gate).also { it.start() }
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

    @Test fun deletedPrivateCallsComeBackWithUndoStillSealed() = runBlocking {
        vault.storePrivateCall(4, "+44 7700 900001", "Ana", 1000, 5, CallLog.Calls.INCOMING_TYPE)
        vault.storePrivateCall(4, "+44 7700 900001", "Ana", 2000, 9, CallLog.Calls.OUTGOING_TYPE)
        vault.storePrivateCall(5, "+44 7700 900002", "Bo", 3000, 1, CallLog.Calls.MISSED_TYPE)
        val before = vault.privateCallsNow().sortedBy { it.date }
        val sealed = db.vaultDao().allPrivateCalls().associate { it.id to it.blob.toList() }

        val kept = vault.deletePrivateCallsForUndo(before.filter { it.vaultId == 4L }.map { it.id })
        assertEquals(2, kept.size)
        assertEquals(listOf(5L), vault.privateCallsNow().map { it.vaultId })

        vault.restorePrivateCalls(kept)
        // Undo tapped twice (or a retry) never doubles a call.
        vault.restorePrivateCalls(kept)
        assertEquals(before, vault.privateCallsNow().sortedBy { it.date })
        assertEquals(sealed, db.vaultDao().allPrivateCalls().associate { it.id to it.blob.toList() })
    }

    /** A call's time, length and direction are sealed with it: the row keeps its entry and a keyed fingerprint only. */
    @Test fun aCallsTimeIsSealedWithIt() = runBlocking {
        assertTrue(vault.storePrivateCall(6, "+44 7700 900001", "Ana", 1_700_000_000_000L, 42, CallLog.Calls.OUTGOING_TYPE))
        // The same call again (a sweep retried): stored once.
        assertTrue(vault.storePrivateCall(6, "+44 7700 900001", "Ana", 1_700_000_000_000L, 42, CallLog.Calls.OUTGOING_TYPE))
        vault.storePrivateCall(6, "+44 7700 900001", "Ana", 1_700_000_500_000L, 7, CallLog.Calls.INCOMING_TYPE)
        val rows = db.vaultDao().allPrivateCalls()
        assertEquals(2, rows.size)
        for (r in rows) {
            assertEquals(listOf(0L, 0L, 0L), listOf(r.date, r.durationSec, r.type.toLong()))
            assertTrue(r.dedupeKey!!.startsWith(LineKeys.PREFIX))
            assertFalse(r.dedupeKey!!.contains("1700000"))
        }
        val calls = vault.privateCallsOf(6)
        assertEquals("newest first", listOf(1_700_000_500_000L, 1_700_000_000_000L), calls.map { it.date })
        assertEquals(listOf(7L, 42L), calls.map { it.durationSec })
        assertEquals(listOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE), calls.map { it.type })

        // What "Recently deleted" keeps of each row has its time, read from the seal.
        val seal = PrivateCallSeal(context)
        assertEquals(setOf(1_700_000_000_000L, 1_700_000_500_000L), rows.map { seal.facts(it).date }.toSet())
        assertEquals(setOf(42L, 7L), rows.map { seal.facts(it).durationSec }.toSet())
    }

    /** Rows stored with their time beside the seal (older versions) are sealed whole once listed; a second copy goes. */
    @Test fun olderRowsAreSealedWholeOnce() = runBlocking {
        val now = System.currentTimeMillis()
        vault.storePrivateCall(8, "+44 7700 900003", "Cy", now, 30, CallLog.Calls.INCOMING_TYPE)
        val plain = JSONObject().put("n", "+44 7700 900003").put("name", "Cy").toString().toByteArray()
        // The same call stored plainly beside the seal before (no fingerprint), and an older one.
        val old = VaultCrypto.sealCallerId(plain)
        db.vaultDao().addPrivateCall(PrivateCallEntity(vaultId = 8, blob = old, date = now, durationSec = 30, type = CallLog.Calls.INCOMING_TYPE))
        db.vaultDao().addPrivateCall(
            PrivateCallEntity(vaultId = 8, blob = VaultCrypto.sealCallerId(plain), date = now - 60_000, durationSec = 5, type = CallLog.Calls.MISSED_TYPE),
        )
        gate.open()
        withTimeout(10_000) {
            while (db.vaultDao().allPrivateCalls().any { it.date != 0L }) delay(20)
        }
        val rows = db.vaultDao().allPrivateCalls()
        assertEquals("the second copy went", 2, rows.size)
        assertTrue(rows.all { it.durationSec == 0L && it.dedupeKey!!.startsWith(LineKeys.PREFIX) })
        assertEquals(listOf(now, now - 60_000), vault.privateCallsOf(8).map { it.date })
        assertEquals(listOf(30L, 5L), vault.privateCallsOf(8).map { it.durationSec })
    }
}
