package app.parley.common.history

import app.parley.common.CallType
import app.parley.common.Codecs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryFilterRangeTest {
    private val march1 = LocalDate.of(2026, 3, 1).toEpochDay()
    private val march31 = LocalDate.of(2026, 3, 31).toEpochDay()
    private val now = at(2026, 10, 12)

    @Test fun a_range_keeps_both_end_days_whole() {
        val f = HistoryFilter().withRange(march1, march31)
        assertTrue(f.hasRange)
        assertFalse(f.isEmpty)
        assertTrue(f.matches(call("1", CallType.OUTGOING, at(2026, 3, 1, 0, 0)), now, UTC))
        assertTrue(f.matches(call("1", CallType.OUTGOING, at(2026, 3, 31, 23, 59)), now, UTC))
        assertFalse(f.matches(call("1", CallType.OUTGOING, at(2026, 2, 28, 23, 59)), now, UTC))
        assertFalse(f.matches(call("1", CallType.OUTGOING, at(2026, 4, 1, 0, 0)), now, UTC))
    }

    @Test fun days_are_read_in_the_phone_time_zone() {
        val paris = ZoneId.of("Europe/Paris")
        val f = HistoryFilter().withRange(march1, march1)
        // 23:30 UTC on 28 Feb is already 1 March in Paris.
        assertTrue(f.matches(call("1", CallType.MISSED, at(2026, 2, 28, 23, 30)), now, paris))
        assertFalse(f.matches(call("1", CallType.MISSED, at(2026, 2, 28, 23, 30)), now, UTC))
    }

    @Test fun open_ends_and_swapped_ends() {
        val from = HistoryFilter().withRange(march1, null)
        assertTrue(from.matches(call("1", CallType.INCOMING, now), now, UTC))
        assertFalse(from.matches(call("1", CallType.INCOMING, at(2026, 2, 1)), now, UTC))
        val swapped = HistoryFilter().withRange(march31, march1)
        assertEquals(march1, swapped.fromDay)
        assertEquals(march31, swapped.toDay)
    }

    @Test fun a_range_replaces_the_period_and_counts_as_a_criterion() {
        val f = HistoryFilter(period = FilterPeriod.LAST_7_DAYS).withRange(march1, march31)
        assertEquals(FilterPeriod.ANY, f.period)
        assertFalse(f.sameCriteria(f.copy(toDay = march31 - 1)))
        assertTrue(f.sameCriteria(f.copy(name = "Accountant")))
    }

    @Test fun a_range_survives_the_codec() {
        val f = HistoryFilter("March").withRange(march1, march31)
        assertEquals(listOf(f), HistoryFilter.decodeList(HistoryFilter.encodeList(listOf(f))))
    }

    @Test fun filters_saved_before_ranges_still_read() {
        val old = """[{"name":"Work","types":["OUTGOING"],"period":"LAST_7_DAYS"}]"""
        val f = HistoryFilter.decodeList(old).single()
        assertEquals(FilterPeriod.LAST_7_DAYS, f.period)
        assertNull(f.fromDay)
        assertNull(f.toDay)
    }

    /** What an older Parley (no range fields) does with a backup or saved list that holds a range: it keeps every filter. */
    @Test fun an_older_version_reads_a_saved_range_as_any_time() {
        val written = HistoryFilter.encodeList(listOf(HistoryFilter("March").withRange(march1, march31), HistoryFilter("Out", setOf(TypeGroup.OUTGOING))))
        val read = Codecs.stored.decodeFromString(ListSerializer(OlderFilter.serializer()), written)
        assertEquals(listOf("March", "Out"), read.map { it.name })
        assertEquals(FilterPeriod.ANY, read[0].period)
    }

    /** [HistoryFilter] as Parley wrote and read it before ranges of days. */
    @Serializable
    private data class OlderFilter(
        val name: String = "",
        val types: Set<TypeGroup> = emptySet(),
        val simId: String? = null,
        val period: FilterPeriod = FilterPeriod.ANY,
        val minDurationSec: Long? = null,
        val maxDurationSec: Long? = null,
    )
}
