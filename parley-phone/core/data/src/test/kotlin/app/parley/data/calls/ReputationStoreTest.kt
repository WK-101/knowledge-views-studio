package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.spam.RepReason
import app.parley.common.spam.RepSignal
import app.parley.common.spam.Reputation
import app.parley.common.spam.ReputationIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** I2: the learned index is stored keyed and sealed, and looked up by line, then by range. */
@RunWith(RobolectricTestRunner::class)
class ReputationStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("parley_reputation", Context.MODE_PRIVATE) }

    private class Keys : ReputationStore.Keys {
        var broken = false
        override fun mac(value: String) = "mac(" + value.reversed() + ")"
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(blob: ByteArray): ByteArray {
            if (broken) error("Key unavailable")
            return blob.copyOfRange(1, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val sales = Reputation(60, listOf(RepReason(RepSignal.SHORT_RINGS, 2, 30), RepReason(RepSignal.NEVER_ANSWERED, 3, 30)))
    private val range = Reputation(50, listOf(RepReason(RepSignal.RANGE_BURST, 4, 35), RepReason(RepSignal.RANGE_UNANSWERED, 4, 15)))

    @Before fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test fun looks_up_lines_then_ranges_in_any_form() {
        val keys = Keys()
        ReputationStore(context, keys).replace(ReputationIndex(mapOf("+33612345601" to sales), mapOf("+33698765" to range)))
        val store = ReputationStore(context, keys)
        assertEquals(sales, store.lookup("06 12 34 56 01", "FR"))
        assertEquals(sales, store.lookup("+33 6 12 34 56 01", "GB"))
        // A number never seen, from a tagged range.
        assertEquals(range, store.lookup("+33698765999", "FR"))
        assertNull(store.lookup("+33611111111", "FR"))
        assertNull(store.lookup("112", "FR"))
        assertTrue(store.mayHaveEntries)
    }

    @Test fun nothing_is_stored_in_plain_text() {
        ReputationStore(context, Keys()).replace(ReputationIndex(mapOf("+33612345601" to sales), emptyMap()))
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains("612345601"))
        assertFalse(raw.contains("SHORT_RINGS"))
    }

    @Test fun unreadable_index_means_no_tag_and_later_reads_work() {
        val keys = Keys()
        ReputationStore(context, keys).replace(ReputationIndex(mapOf("+33612345601" to sales), emptyMap()))
        keys.broken = true
        val store = ReputationStore(context, keys)
        assertNull(store.lookup("+33612345601", "FR"))
        keys.broken = false
        assertEquals(sales, store.lookup("+33612345601", "FR"))
    }

    @Test fun clear_forgets_everything() {
        val store = ReputationStore(context, Keys())
        store.replace(ReputationIndex(mapOf("+33612345601" to sales), emptyMap()))
        store.clear()
        assertNull(store.lookup("+33612345601", "FR"))
        assertFalse(store.mayHaveEntries)
        assertNull(ReputationStore(context, Keys()).lookup("+33612345601", "FR"))
    }
}
