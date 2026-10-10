package app.parley.data.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.ContactSummary
import app.parley.common.people.ContactSort
import app.parley.common.people.ListHead
import app.parley.common.people.PrivateListing
import app.parley.common.ux.ListSections
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.StartGate
import app.parley.data.db.AppDatabase
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 * What a cold start costs to list the private contacts, with a stand-in Keystore that counts its
 * operations: before, one AndroidKeyStore decryption per private contact; now, the rows kept from the last run open
 * with one key unwrap for all of them, and only a changed or new contact is opened itself. The rows are the same
 * either way. Timings print to the test log, with each Keystore operation costed as on a mid-range phone.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateListingCostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val scopes = ArrayList<CoroutineScope>()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(false)
        VaultCrypto.appContext = context
        context.getSharedPreferences("vault_keys", Context.MODE_PRIVATE).edit().clear().commit()
        // Numbers fingerprinted as this version does: no one-time re-keying runs in these processes.
        context.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().clear().putInt("number_keys_version", 4).commit()
        File(context.noBackupFilesDir, "vault_summaries").delete()
        File(context.noBackupFilesDir, "vault_summaries.keys").delete()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
    }

    /** A new process's vault over the same database: its full start never opens here, so nothing runs behind the test. */
    private fun process(): VaultRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return VaultRepository(context, db, scope, StartGate())
    }

    private fun fill(vault: VaultRepository, n: Int): List<Long> = runBlocking {
        (0 until n).map { i ->
            vault.save(
                null,
                ContactDetails(
                    given = "Private%04d".format(i), family = "Person", company = if (i % 7 == 0) "Acme" else "",
                    pinnedNote = "Note for calls $i", phones = listOf(DataItem(null, "+1 202 55%05d".format(i), 2)),
                    starred = i % 50 == 0,
                ),
            )
        }
    }

    private data class Cost(val keystoreOps: Int, val callerOpens: Int, val unwraps: Int, val ms: Long)

    private fun cold(block: suspend () -> Unit): Cost {
        VaultCrypto.forgetKeyHandles()
        VaultCrypto.Meter.reset()
        FakeAndroidKeyStore.reads.clear()
        val start = System.nanoTime()
        runBlocking { block() }
        val ms = (System.nanoTime() - start) / 1_000_000
        val opens = VaultCrypto.Meter.callerOpens.get()
        val unwraps = FakeAndroidKeyStore.reads[WRAP]?.get() ?: 0
        return Cost(opens + unwraps, opens, unwraps, ms)
    }

    @Test fun a_cold_start_lists_500_private_contacts_with_one_keystore_operation() {
        val ids = fill(process(), PRIVATE)

        // Before: no kept rows (the first run after this change, or the rows couldn't be read): every copy is opened.
        lateinit var opened: List<VaultSummary>
        val before = cold { opened = process().summariesNow() }
        assertEquals(PRIVATE, before.callerOpens)

        // Now: a later cold start lists them from the kept rows.
        lateinit var kept: List<VaultSummary>
        val after = cold { kept = process().summariesNow() }
        assertEquals("no caller-ID copy opened one by one", 0, after.callerOpens)
        assertEquals("one key unwrap for all of them", 1, after.unwraps)
        assertEquals("the same rows, field for field", opened, kept)
        assertEquals(ids.toSet(), kept.map { it.id }.toSet())

        // The Contacts list's first screen with 10,000 address-book contacts among them: drawn from the kept head.
        val device = (1L..10_000L).map { i -> ContactSummary(i, "lk$i", "Contact%05d".format(i), null, false, emptyList()) }
        val private = kept.map { v -> PrivateListing.row(v.id, v.name, v.numbers, v.starred, null, v.nameAlt) }
        val merged = PrivateListing.merge(device, private, compareBy { it })
        val text = ListHead.encode(ListSections.interleave(merged) { ListSections.letterOf(it.sortName) }, withPrivate = true, sort = ContactSort.NAME.name)
        val headStart = System.nanoTime()
        val head = ListHead.shown(ListHead.decode(text), ListHead.Access(privateListed = true, privateMayShow = true, sort = ContactSort.NAME.name))
        val headMs = (System.nanoTime() - headStart) / 1_000_000
        assertTrue(head!!.isNotEmpty())

        // Four openers on a TEE that mostly serialises: counted as one and a half at once.
        val beforeModel = before.callerOpens * KEYSTORE_OP_MS * 2 / 3
        val afterModel = after.keystoreOps * KEYSTORE_OP_MS
        println(
            "B9 cold listing of $PRIVATE private contacts (10,000 in the address book):\n" +
                "  before: ${before.keystoreOps} Keystore operations, ${before.ms} ms on the JVM, ~${beforeModel + before.ms} ms on a phone\n" +
                "  now:    ${after.keystoreOps} Keystore operation (${after.unwraps} unwrap), ${after.ms} ms on the JVM, " +
                "~${afterModel + after.ms} ms on a phone\n" +
                "  first screen from the kept head: $headMs ms to open and check (plus one records-key unwrap, ~$KEYSTORE_OP_MS ms)",
        )
        assertTrue(after.keystoreOps < before.keystoreOps)
    }

    @Test fun only_a_changed_or_new_contact_is_opened_and_a_deleted_one_is_ignored() = runBlocking {
        val first = process()
        val ids = fill(first, 20)
        first.summariesNow()
        // Changed, deleted and added after the rows were kept (as a run killed before its next listing would leave them).
        val other = process()
        other.updateCallerChoices(ids[5]) { it.copy(starred = true) }
        other.delete(ids[6])
        val added = other.save(null, ContactDetails(given = "New", phones = listOf(DataItem(null, "+1 202 5550999", 2))))

        lateinit var listed: List<VaultSummary>
        val cost = cold { listed = process().summariesNow() }
        assertEquals("the changed and the new one", 2, cost.callerOpens)
        assertTrue(listed.single { it.id == ids[5] }.starred)
        assertFalse(listed.any { it.id == ids[6] })
        assertTrue(listed.any { it.id == added })

        // The same as opening every copy afresh.
        File(context.noBackupFilesDir, "vault_summaries").delete()
        assertEquals(process().summariesNow(), listed)
    }

    @Test fun rows_that_cant_be_read_are_opened_one_by_one_and_replaced() = runBlocking {
        fill(process(), 10)
        val expected = process().summariesNow()
        // Its key is gone (app data partly cleared): every copy opens itself, and new rows are kept.
        FakeAndroidKeyStore.delete(WRAP)
        val cost = cold { assertEquals(expected, process().summariesNow()) }
        assertEquals(10, cost.callerOpens)
        assertEquals(0, cold { process().summariesNow() }.callerOpens)
        // A damaged file: the same.
        File(context.noBackupFilesDir, "vault_summaries").writeBytes(ByteArray(64) { 7 })
        assertEquals(expected, process().summariesNow())
    }

    @Test fun kept_rows_are_sealed_and_hold_no_caller_card() = runBlocking {
        fill(process(), 3)
        process().summariesNow()
        val raw = String(File(context.noBackupFilesDir, "vault_summaries").readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("Private0001") || raw.contains("20255"))
        val o = JSONObject().put("name", "Ana").put("numbers", org.json.JSONArray(listOf("+1"))).put("note", "Gate code 1234").put("ctx", "Plumber")
            .put(CallerIdCopy.C_ARCHIVED, 5L)
        val part = CallerIdCopy.summaryPart(o)
        assertFalse(part.has("note") || part.has("ctx"))
        assertEquals(5L, part.getLong(CallerIdCopy.C_ARCHIVED))
    }

    @Test fun the_kept_rows_key_is_dropped_with_opened_details() = runBlocking {
        val vault = process()
        val ids = fill(vault, 3)
        vault.summariesNow()
        FakeAndroidKeyStore.reads.clear()
        // The app lock, the screen off, "Lock private contacts": the next write unwraps the key again.
        vault.forgetOpened()
        vault.updateCallerChoices(ids[1]) { it.copy(starred = true) }
        vault.summariesNow()
        assertEquals(1, FakeAndroidKeyStore.reads[WRAP]?.get())
    }

    private companion object {
        const val PRIVATE = 500
        const val WRAP = "parley_vault_summaries_wrap"

        /** One AndroidKeyStore AES operation on a mid-range phone's TEE (2–5 ms). */
        const val KEYSTORE_OP_MS = 3L
    }
}
