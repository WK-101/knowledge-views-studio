package app.parley.calls

import app.parley.common.CallType
import app.parley.common.PhoneIdentity
import app.parley.common.testing.testCall
import app.parley.data.DataItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Move to note" and the health check's call history: what undo puts back, and which calls show a line works. */
class NumberSignalsTest {
    private val same = { p: DataItem -> PhoneIdentity.same(p.value, "+447700900123", "GB") }

    @Test fun undo_puts_back_every_row_on_the_line_with_its_label() {
        val mobile = DataItem(id = 1, value = "+44 7700 900123", type = 2)
        val work = DataItem(id = 2, value = "07700 900123", type = 3, label = "Desk")
        val other = DataItem(id = 3, value = "+44 20 7946 0000", type = 1)
        val now = listOf(other)
        val back = NumberSignals.restored(now, listOf(mobile, work), same)
        assertEquals(listOf(other, mobile.copy(id = null), work.copy(id = null)), back)
        // Saved again since: left as it is now, never doubled.
        val again = listOf(other, DataItem(value = "07700900123"))
        assertEquals(again, NumberSignals.restored(again, listOf(mobile, work), same))
    }

    @Test fun the_call_history_passed_in_clears_a_line_that_works() {
        fun call(type: CallType, date: Long, sec: Long = 0) = testCall(date, "07700 900123", null, type, date, sec)
        val calls = listOf(call(CallType.OUTGOING, 30), call(CallType.OUTGOING, 20, sec = 45), call(CallType.MISSED, 10))
        assertEquals(20L, NumberSignals.aliveSince(calls, "GB")["+447700900123"])
        assertNull(NumberSignals.aliveSince(listOf(call(CallType.OUTGOING, 30)), "GB")["+447700900123"])
    }

    @Test fun a_call_in_an_app_over_the_internet_never_shows_the_phone_line_works() {
        val whatsApp = CallEntry(40, "07700 900123", null, CallType.INCOMING, 40, 60, "acc", false, false, appPackage = "com.whatsapp")
        val phone = CallEntry(10, "07700 900123", null, CallType.INCOMING, 10, 60, null, false, false)
        assertNull(NumberSignals.aliveSince(listOf(whatsApp), "GB")["+447700900123"])
        assertEquals(10L, NumberSignals.aliveSince(listOf(whatsApp, phone), "GB")["+447700900123"])
    }
}
