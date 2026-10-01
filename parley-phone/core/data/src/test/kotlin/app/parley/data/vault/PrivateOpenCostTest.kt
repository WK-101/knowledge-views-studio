package app.parley.data.vault

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.db.AppDatabase
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What opening a private contact's page costs: Keystore operations and the bytes they decrypt (on a phone whose
 * detail key is in StrongBox, every decrypted kB is slow). A contact made private carries its whole address-book
 * record, photo included, beside its details; the page must open only the details.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateOpenCostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var vault: VaultRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        // Keys made without a secure lock screen, so a later one can be upgraded to (see the last test).
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(false)
        VaultCrypto.appContext = context
        context.getSharedPreferences("vault_keys", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        vault = VaultRepository(context, db, scope)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** A display photo as Android keeps it for a contact (VaultMoves keeps up to 512 kB). */
    private val photo = ByteArray(400_000) { (it * 31 % 251).toByte() }

    private val ana = ContactDetails(
        given = "Ana", family = "Lima", company = "Acme", title = "Engineer", pinnedNote = "Call after 6",
        phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE), DataItem(null, "+44 20 7946 0001", Phone.TYPE_WORK)),
        emails = listOf(DataItem(null, "ana@example.com", 1)), note = "Met at the conference",
    )

    private val record = ContactRecord(
        key = "lookup-ana", displayName = "Ana Lima",
        raws = listOf(
            RawRecord(
                "com.google", "me@example.com",
                rows = listOf(
                    DataRow(Mime.NAME, mapOf(Col.D1 to "Ana Lima", Col.D2 to "Ana", Col.D3 to "Lima")),
                    DataRow(Mime.PHONE, mapOf(Col.D1 to "+44 20 7946 0000", Col.D2 to "2")),
                    DataRow(Mime.PHOTO, emptyMap(), blob = photo),
                ),
            ),
        ),
    )

    private val interactions = "x".repeat(20_000)

    /** Ana as "Make private" leaves her, and 30 other private contacts. Returns Ana's id. */
    private fun fill(): Long = runBlocking {
        repeat(30) { i -> vault.save(null, ContactDetails(given = "P$i", phones = listOf(DataItem(null, "+1 202 555 01${"%02d".format(i)}", 2)))) }
        vault.save(null, ana, record = record, interactions = interactions)
    }

    /** The entry sealed as before this change: one blob with the details, the record, its photo and interactions. */
    private fun asSingleBlob(id: Long): ByteArray = runBlocking {
        val e = db.vaultDao().get(id)!!
        val whole = JSONObject(String(VaultCrypto.openDetailMain(e.detailBlob)))
        val extra = JSONObject(String(VaultCrypto.openDetailExtra(e.detailBlob)!!))
        extra.keys().forEach { k -> whole.put(k, extra.get(k)) }
        VaultCrypto.sealDetail(whole.toString().toByteArray()).also { db.vaultDao().setDetailBlob(id, it); vault.forgetOpened() }
    }

    private data class Work(val callerOpens: Int, val detailOpens: Int, val detailBytes: Long, val keyLookups: Int)

    private fun measure(block: () -> Unit): Work {
        VaultCrypto.forgetKeyHandles()
        VaultCrypto.Meter.reset()
        block()
        val m = VaultCrypto.Meter
        return Work(m.callerOpens.get(), m.detailOpens.get(), m.detailBytes.get(), m.keyLookups.get())
    }

    @Test fun a_page_opens_only_the_details_and_each_entry_is_split_once() = runBlocking {
        val id = fill()
        val single = asSingleBlob(id)

        // 4.4's page open, step by step (ContactDetailViewModel.loadPrivate): every entry listed and its caller-ID copy
        // opened to find this one, details() (its summary, its caller-ID copy, the whole blob), then detailsLost()
        // (the whole blob again).
        val before = measure {
            runBlocking {
                db.vaultDao().all().forEach { VaultCrypto.openCallerId(it.callerIdBlob) }
                val e = db.vaultDao().get(id)!!
                VaultCrypto.openCallerId(e.callerIdBlob)
                VaultCrypto.openCallerId(e.callerIdBlob)
                VaultCrypto.openDetail(e.detailBlob)
                VaultCrypto.openDetail(e.detailBlob)
            }
        }
        assertEquals(2 * single.size.toLong(), before.detailBytes)

        // Now: the caller-ID copy first (the page shows it at once), then the details once. A single blob is opened
        // whole this one last time and split.
        val first = measure { runBlocking { vault.summary(id); vault.callerCopy(id); vault.open(id) } }
        assertEquals(1, first.detailOpens)
        assertEquals(single.size.toLong(), first.detailBytes)
        val stored = db.vaultDao().get(id)!!.detailBlob
        assertTrue("split into two parts", VaultCrypto.isParts(stored))

        // A later open (after the minute in memory): only the small main part.
        vault.forgetOpened()
        val later = measure { runBlocking { vault.summary(id); vault.callerCopy(id); vault.open(id) } }
        assertEquals(1, later.detailOpens)
        assertTrue("main part ${later.detailBytes} B", later.detailBytes < 4_000)
        // Within the minute: nothing sealed is opened again.
        val again = measure { runBlocking { vault.open(id) } }
        assertEquals(0, again.detailOpens)

        println(
            "Private page open, Ana (made private with a 400 kB photo) among 31 private contacts:\n" +
                "  4.4:     $before (whole blob ${single.size} B, twice)\n" +
                "  first:   $first (the last whole opening, then split)\n" +
                "  later:   $later\n" +
                "  cached:  $again",
        )

        // Nothing was lost on the way: details, record with its photo, interactions.
        val d = vault.details(id)!!
        assertEquals("Ana Lima", d.displayName)
        assertEquals("Call after 6", d.pinnedNote)
        val kept = vault.storedRecord(id)!!
        assertArrayEquals(photo, kept.record.raws.single().rows.first { it.mimeType == Mime.PHOTO }.blob)
        assertFalse("not edited", kept.editedSince)
        assertEquals(interactions, vault.storedInteractions(id))
    }

    @Test fun an_edit_keeps_the_record_without_opening_it() = runBlocking {
        val id = fill()
        val edit = measure { runBlocking { vault.save(id, vault.details(id)!!.copy(note = "Changed")) } }
        // The editor's own details open (from memory) and nothing else: the extra part is kept sealed as it is.
        assertEquals(0, edit.detailOpens)
        val kept = vault.storedRecord(id)!!
        assertTrue(kept.editedSince)
        assertArrayEquals(photo, kept.record.raws.single().rows.first { it.mimeType == Mime.PHOTO }.blob)
        assertEquals(interactions, vault.storedInteractions(id))
        vault.forgetOpened()
        assertEquals("Changed", vault.details(id)!!.note)
    }

    @Test fun the_split_migration_a_key_upgrade_and_recently_deleted_keep_both_parts() = runBlocking {
        val id = fill()
        asSingleBlob(id)
        assertEquals(1, vault.splitDetails())
        assertTrue(VaultCrypto.isParts(db.vaultDao().get(id)!!.detailBlob))
        assertEquals(0, vault.splitDetails())

        // A stronger key re-seals both parts.
        val gen = VaultCrypto.generationOf(db.vaultDao().get(id)!!.detailBlob)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(true)
        assertTrue(vault.upgradeDetailKey())
        assertTrue(VaultCrypto.generationOf(db.vaultDao().get(id)!!.detailBlob) > gen)
        vault.forgetOpened()
        assertEquals("Call after 6", vault.details(id)!!.pinnedNote)
        assertArrayEquals(photo, vault.storedRecord(id)!!.record.raws.single().rows.first { it.mimeType == Mime.PHOTO }.blob)

        // "Recently deleted" keeps the sealed blob as it is and gives it back whole.
        val copy = vault.sealedCopy(id)
        assertNotNull(copy)
        vault.delete(id)
        val back = vault.restoreSealed(copy!!)
        assertEquals("Ana Lima", vault.details(back)!!.displayName)
        assertEquals(interactions, vault.storedInteractions(back))
    }
}
