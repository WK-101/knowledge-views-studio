package app.parley.data.perf

import android.Manifest
import android.app.Application
import android.content.ContentProviderOperation
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.CallHistoryLine
import app.parley.common.backup.CallLogRecord
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.RingFacts
import app.parley.common.people.ArchivedCard
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.recall.RecallSources
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * The scale of the measurement gate on the JVM (Robolectric, software crypto, an in-memory contacts provider): 10,000
 * contacts, 500 private, 1,000 archived and 50,000 archived calls, and the paths the 6.5 storage work changed. JVM
 * figures, not a phone's: they compare one build with another on the same machine, and show what grows with the data.
 *
 * Off by default (it seeds for minutes); run it with `PARLEY_BENCH=1` and `--tests '*ScaleBenchmarkTest'`. Each figure
 * is printed as `BENCH <name> <ms>` for docs/PERFORMANCE_BENCHMARKS.md. It uses only APIs that 6.4 has as well, so the
 * same file measures the build before.
 */
@RunWith(RobolectricTestRunner::class)
class ScaleBenchmarkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val containers = ArrayList<DataContainer>()
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    private fun container() = DataContainer(app).also { containers += it }

    @After fun tearDown() {
        containers.forEach { c ->
            c.scope.cancel()
            runCatching { c.db.close() }
        }
    }

    private fun number(i: Int) = "+1555" + String.format(Locale.ROOT, "%07d", i)

    private fun report(name: String, ms: Long) = println("BENCH $name $ms")

    private inline fun <T> timed(name: String, block: () -> T): T {
        val t = System.nanoTime()
        val out = block()
        report(name, (System.nanoTime() - t) / 1_000_000)
        return out
    }

    private fun seedContacts() {
        (0 until CONTACTS).chunked(150).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>()
            for (i in chunk) {
                val back = ops.size
                ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "Bench $i").build()
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, back)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, number(i)).withValue(Phone.TYPE, Phone.TYPE_MOBILE).build()
            }
            app.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }
    }

    @Test fun the_gate_at_ten_thousand_contacts() = runBlocking {
        assumeTrue("Set PARLEY_BENCH=1 to run the scale benchmark", System.getenv("PARLEY_BENCH") != null)
        FakeAndroidKeyStore.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        FakeContactsProvider.install()
        val c = container()
        c.startFull()
        c.settings.update { AppSettings() }
        c.history.setArchiveEnabled(true)

        timed("seed.contacts_10k") { seedContacts() }
        timed("seed.private_500") {
            repeat(PRIVATE) { i ->
                c.vault.save(null, ContactDetails(given = "Private", family = "$i", phones = listOf(DataItem(null, number(PRIVATE_BASE + i), Phone.TYPE_MOBILE))))
            }
        }
        timed("seed.archived_1k") {
            repeat(ARCHIVED) { i ->
                val n = number(ARCHIVED_BASE + i)
                val record = ContactRecord("lk-a$i", "Archived $i", raws = listOf(RawRecord(null, null, rows = listOf(DataRow(Mime.PHONE, mapOf("data1" to n))))))
                c.archive.restore(ArchivedCard(0, "Archived $i", listOf(n), now - day, emptyList(), "lk-a$i"), record)
            }
        }
        // 50,000 calls over two years: every contact some, a third of them from numbers nobody saved.
        timed("seed.calls_50k") {
            (0 until CALLS).chunked(5_000).forEach { chunk ->
                c.history.restoreLines(
                    chunk.map { i ->
                        val n = if (i % 3 == 0) number(UNSAVED_BASE + i % 4_000) else number(i % CONTACTS)
                        CallHistoryLine(call = CallLogRecord(n, now - (i.toLong() * 2 * 365 * day) / CALLS, 30, Calls.INCOMING_TYPE))
                    },
                )
            }
        }
        val listed = timed("contacts.load_10k") { c.contacts.loadNow() }
        assertEquals(CONTACTS, listed.size)

        callEnd(c)
        recall(c)
        tally(c)

        // A process started for a ringing call: the archived contacts' cards, then one caller looked up.
        val ring = container()
        val found = timed("ring.archived_lookup_cold") { ring.archive.lookup(number(ARCHIVED_BASE + 7), "US") }
        assertNotNull(found)
    }

    /** One call ending: its ring facts, quality and network name written, with each store already full. */
    private fun callEnd(c: DataContainer) {
        timed("seed.call_facts_full") {
            repeat(1_000) { i ->
                val at = now - i * 60_000L
                val n = number(UNSAVED_BASE + i)
                if (i < 400) c.ringFacts.add(n, RingFacts(startedAt = at), at)
                if (i < 400) c.callQuality.add(n, CallQualityFacts(startedAt = at, incoming = true), at)
                c.networkNames.record(n, "Caller $i", at, null, "US", at)
            }
        }
        val times = (0 until 20).map { i ->
            val at = now + i * 1_000L
            val n = number(UNSAVED_BASE + 2_000 + i)
            val t = System.nanoTime()
            c.ringFacts.add(n, RingFacts(startedAt = at), at)
            c.callQuality.add(n, CallQualityFacts(startedAt = at, incoming = true), at)
            c.networkNames.record(n, "Late caller $i", at, null, "US", at)
            (System.nanoTime() - t) / 1_000
        }.sorted()
        report("call_end.facts_write_median_us", times[times.size / 2])
        assertEquals(1, c.callQuality.forNumber(number(UNSAVED_BASE + 2_000)).size)
    }

    /** The day's snapshot and the versions number memory reads; Recall opened, then again with nothing changed. */
    private suspend fun recall(c: DataContainer) {
        timed("memory.snapshot_take") { c.timeMachine.snapshotIfDue() }
        timed("memory.snapshot_versions_first") { c.timeMachine.peopleForMemory() }
        timed("memory.snapshot_versions_again") { c.timeMachine.peopleForMemory() }
        val sources = RecallSources(c)
        val access = RecallSources.Access(privateShown = false)
        timed("recall.corpus_first") { sources.load(access) }
        timed("recall.corpus_again") { sources.load(access) }
    }

    /** Number memory's daily tally of the archive: built, then again after retention took the older half. */
    private suspend fun tally(c: DataContainer) {
        val first = timed("memory.tally_full") { c.history.tallyForMemory(null, "US") }
        assertNotNull(first)
        timed("history.retention_365_days") { c.history.applyRetention(365) }
        val again = timed("memory.tally_after_retention") { c.history.tallyForMemory(first, "US") }
        assertNotNull(again)
    }

    private companion object {
        const val CONTACTS = 10_000
        const val PRIVATE = 500
        const val ARCHIVED = 1_000
        const val CALLS = 50_000
        const val PRIVATE_BASE = 100_000
        const val ARCHIVED_BASE = 200_000
        const val UNSAVED_BASE = 300_000
    }
}
