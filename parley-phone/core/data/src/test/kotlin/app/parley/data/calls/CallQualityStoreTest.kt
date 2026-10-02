package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.CallQualityFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Rows that can't be opened for a moment are kept, never wiped by the next call's facts. */
@RunWith(RobolectricTestRunner::class)
class CallQualityStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("parley_call_quality", Context.MODE_PRIVATE) }

    /** A stand-in for the archive key: "sealed" is the bytes behind a marker; [broken] makes opening fail. */
    private class Keys : CallQualityStore.Keys {
        var broken = false
        override fun lineMac(number: String) = "mac-$number"
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain
        override fun open(blob: ByteArray): ByteArray {
            if (broken) error("Key unavailable")
            return blob.copyOfRange(1, blob.size)
        }
    }

    @Before fun setUp() {
        prefs.edit().clear().commit()
    }

    private fun facts(at: Long) = CallQualityFacts(startedAt = at, incoming = true)

    @Test fun unreadable_rows_survive_a_write_and_read_back_later() {
        val now = System.currentTimeMillis()
        val keys = Keys()
        CallQualityStore(context, keys).apply {
            add("+12025550100", facts(now - 2_000), now)
            add("+12025550199", facts(now - 1_000), now)
        }

        // A new process while the key can't be used: nothing reads, and a new call's facts don't wipe the old ones.
        keys.broken = true
        val store = CallQualityStore(context, keys)
        assertTrue(store.forNumber("+12025550100").isEmpty())
        store.add("+12025550111", facts(now - 500), now)
        keys.broken = false

        assertEquals(listOf(now - 2_000), store.forNumber("+12025550100").map { it.startedAt })
        assertEquals(listOf(now - 1_000), store.forNumber("+12025550199").map { it.startedAt })
        // The facts written while the others couldn't be read are kept as well.
        assertEquals(listOf(now - 500), store.forNumber("+12025550111").map { it.startedAt })
    }

    @Test fun forgetting_a_number_also_drops_its_unreadable_rows() {
        val now = System.currentTimeMillis()
        val keys = Keys()
        CallQualityStore(context, keys).add("+12025550100", facts(now - 2_000), now)
        keys.broken = true
        val store = CallQualityStore(context, keys)
        store.forget("+12025550100")
        keys.broken = false
        assertTrue(store.forNumber("+12025550100").isEmpty())
    }

    @Test fun the_diary_reads_every_row_by_line_key_never_by_number() {
        val now = System.currentTimeMillis()
        val store = CallQualityStore(context, Keys())
        store.add("+12025550100", facts(now - 3_000), now)
        store.add(null, facts(now - 2_000), now)
        store.add("+12025550199", facts(now - 1_000), now)
        val all = store.all()
        assertEquals(listOf(now - 1_000, now - 2_000, now - 3_000), all.map { it.second.startedAt })
        assertEquals(store.keyOf("+12025550199"), all.first().first)
        assertEquals(store.keyOf(null), all[1].first)
        // The key is the line's fingerprint (here the stand-in "mac-…"), not the number itself.
        assertEquals("m:mac-+12025550100", store.keyOf("+12025550100"))
    }
}
