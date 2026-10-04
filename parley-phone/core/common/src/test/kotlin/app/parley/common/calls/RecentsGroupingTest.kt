package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsGroupingTest {
    private fun call(id: Long, number: String, day: Long, type: CallType = CallType.INCOMING, hidden: Boolean = false) =
        CallEntry(id, number, null, type, day * 86_400_000L + id, 0, null, false, hidden)

    private val calls = listOf(
        call(9, "111", 2), call(8, "111", 2), call(7, "222", 2), call(6, "111", 2),
        call(5, "111", 1), call(4, "333", 1),
    )

    private fun rows(layout: RecentsLayout) = RecentsGrouping.group(calls, layout, { it.number }, { it.date / 86_400_000L }).map { r -> r.map { it.id } }

    @Test fun grouped_merges_only_consecutive_calls_on_the_same_day() {
        assertEquals(listOf(listOf(9L, 8L), listOf(7L), listOf(6L), listOf(5L), listOf(4L)), rows(RecentsLayout.GROUPED))
    }

    @Test fun chronological_gives_every_call_its_own_row() {
        assertEquals(calls.map { listOf(it.id) }, rows(RecentsLayout.CHRONOLOGICAL))
    }

    @Test fun by_day_gives_one_row_per_number_per_day() {
        // 111 called three times on day 2 (with 222 in between): one row, placed at its newest call.
        assertEquals(listOf(listOf(9L, 8L, 6L), listOf(7L), listOf(5L), listOf(4L)), rows(RecentsLayout.BY_DAY))
    }

    @Test fun recents_layout_is_a_searchable_setting() {
        listOf("recents_layout", "clear_history", "call_haptics", "default_dialer_help").forEach { SettingsCatalog[it] }
    }
}
