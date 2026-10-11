package app.parley.data.calls

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.CallQualityCodec
import app.parley.common.calls.CallQualityFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one-time move of per-call facts from a preferences value (one line per row, re-sealed whole on every call) into
 * the history database's `call_facts` table: every row comes across byte for byte, unreadable ones too; the copy is
 * made once even when it is cut short before the preferences value goes; and rows forgotten since never come back.
 */
@RunWith(RobolectricTestRunner::class)
class SealedLineStoreMoveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("parley_call_quality", Context.MODE_PRIVATE) }
    private val table by lazy { memoryFactTable(context) }
    private val now = System.currentTimeMillis()

    private class Keys : SealedLineStore.Keys {
        override fun lineMac(number: String) = "mac-$number"
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain
        override fun open(blob: ByteArray): ByteArray {
            check(blob[0].toInt() == 7) { "Not this key" }
            return blob.copyOfRange(1, blob.size)
        }
    }

    @Before fun setUp() {
        prefs.edit().clear().commit()
    }

    /** A row as 6.4 wrote it: "<line key>\t<sealed facts, Base64>". */
    private fun line(number: String, at: Long): String {
        val facts = CallQualityCodec.encode(listOf(CallQualityFacts(startedAt = at, incoming = true))).toByteArray()
        return "m:mac-$number\t" + Base64.encodeToString(byteArrayOf(7) + facts, Base64.NO_WRAP)
    }

    /** A row sealed with a key that can't be used now. */
    private val unreadable = "m:mac-+12025550177\t" + Base64.encodeToString(byteArrayOf(9, 1, 2, 3), Base64.NO_WRAP)

    private fun legacy(vararg lines: String) = prefs.edit().putString("rows_v1", lines.joinToString("\n")).commit()

    @Test fun every_row_moves_byte_for_byte_and_the_preferences_value_goes() {
        val a = line("+12025550100", now - 2_000)
        val b = line("+12025550199", now - 1_000)
        legacy(a, b, unreadable)
        val store = CallQualityStore(context, Keys(), table)
        assertEquals(listOf(now - 2_000), store.forNumber("+12025550100").map { it.startedAt })
        assertEquals(listOf(now - 1_000), store.forNumber("+12025550199").map { it.startedAt })
        assertNull(prefs.getString("rows_v1", null))
        // The sealed bytes are the ones the preferences held, the unreadable row's too (kept, never written over).
        val stored = table.rows("call_quality").associate { it.lineKey + "\t" + Base64.encodeToString(it.blob, Base64.NO_WRAP) to it.startedAt }
        assertEquals(setOf(a, b, unreadable), stored.keys)
        assertEquals(now - 2_000, stored[a])
    }

    @Test fun a_move_cut_short_before_the_preferences_value_went_is_not_made_twice() {
        val a = line("+12025550100", now - 2_000)
        legacy(a, unreadable)
        CallQualityStore(context, Keys(), table).forNumber("+12025550100")
        // A crash after the table's transaction, before the preferences value was removed: it is still there.
        legacy(a, unreadable)
        val again = CallQualityStore(context, Keys(), table)
        assertEquals(1, again.forNumber("+12025550100").size)
        assertEquals(2, table.count("call_quality"))
        assertNull(prefs.getString("rows_v1", null))
    }

    @Test fun rows_forgotten_after_the_move_never_come_back() {
        val a = line("+12025550100", now - 2_000)
        legacy(a)
        CallQualityStore(context, Keys(), table).forget("+12025550100")
        legacy(a)
        assertTrue(CallQualityStore(context, Keys(), table).forNumber("+12025550100").isEmpty())
    }

    @Test fun a_new_call_writes_its_own_row_and_leaves_the_others_as_they_were() {
        legacy(line("+12025550100", now - 2_000), unreadable)
        val store = CallQualityStore(context, Keys(), table)
        val before = table.rows("call_quality").associate { it.id to it.blob.toList() }
        store.add("+12025550111", CallQualityFacts(startedAt = now - 500, incoming = false), now)
        val after = table.rows("call_quality").associate { it.id to it.blob.toList() }
        assertEquals(3, after.size)
        // No row was sealed again: each one before is there unchanged.
        before.forEach { (id, blob) -> assertEquals(blob, after[id]) }
    }

    @Test fun pruning_keeps_the_newest_rows_within_the_days() {
        val store = CallQualityStore(context, Keys(), table)
        val day = 86_400_000L
        store.add("+12025550100", CallQualityFacts(startedAt = now - 61 * day, incoming = true), now - 61 * day)
        (0 until 405).forEach { i -> store.add("+1202555${1000 + i}", CallQualityFacts(startedAt = now - i * 1_000L, incoming = true), now) }
        assertEquals(400, table.count("call_quality"))
        assertTrue(store.forNumber("+12025550100").isEmpty())
        assertEquals(400, store.all().size)
        assertEquals(now, store.all().first().second.startedAt)
    }
}
