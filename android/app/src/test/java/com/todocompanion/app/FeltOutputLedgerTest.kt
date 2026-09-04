package com.todocompanion.app

import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.domain.FeltOutputLedger
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Track 3.1 — the felt × output ledger: output-by-day counting, Pearson correlation, and the honest,
 * descriptive findings (including "your best-rated days weren't your busiest").
 */
class FeltOutputLedgerTest {

    private val base = LocalDate.of(2026, 1, 1).toEpochDay()

    private fun task(day: Long, id: String, win: Boolean = false) =
        Accomplishment(kind = DoneKind.TASK, refId = id, title = "t$id", whenMillis = day * 86_400_000L, epochDay = day, isWin = win)

    private fun focus(day: Long, id: String, min: Int) =
        Accomplishment(kind = DoneKind.FOCUS, refId = id, title = "focus", whenMillis = day * 86_400_000L, epochDay = day, durationMin = min)

    private fun log(day: Long, rating: Int) = DayLogEntity(epochDay = day, dayRating = rating)

    @Test fun outputByDayCountsTaskLikeFocusAndWins() {
        val feed = listOf(
            task(base, "a"), task(base, "b", win = true), focus(base, "f", 40),
            task(base + 1, "c"),
        )
        val out = FeltOutputLedger.outputByDay(base, base + 2, feed)
        assertEquals(2, out.getValue(base).tasks)
        assertEquals(1, out.getValue(base).wins)
        assertEquals(40, out.getValue(base).focusMin)
        assertEquals(1, out.getValue(base + 1).tasks)
        assertEquals(0, out.getValue(base + 2).tasks) // an empty day still has a (zero) entry
    }

    @Test fun pearsonNullOnFlatOrTooFew() {
        assertNull(FeltOutputLedger.pearson(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0))) // no x variance
        assertNull(FeltOutputLedger.pearson(listOf(1.0, 2.0), listOf(1.0, 2.0)))            // too few
        val r = FeltOutputLedger.pearson(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0))
        assertNotNull(r)
        assertEquals(-1.0, r!!, 1e-9)
    }

    @Test fun bestRatedDaysWerentBusiestWhenOutputInvertsRating() {
        // 6 high-rated days with NO output, 6 low-rated days with lots of output → negative link,
        // and the best-rated third is clearly not the busiest.
        val logs = ArrayList<DayLogEntity>()
        val feed = ArrayList<Accomplishment>()
        for (i in 0 until 6) logs += log(base + i, 5)
        for (i in 6 until 12) {
            logs += log(base + i, 2)
            repeat(3) { k -> feed += task(base + i, "t$i-$k") }
        }
        val ledger = FeltOutputLedger.compute(base, base + 11, feed, logs)
        assertTrue("enough paired days to report", ledger.hasData)
        assertEquals(12, ledger.pairedDays)
        assertNotNull(ledger.correlation)
        assertTrue("busier days link to a lower rating", ledger.correlation!! < 0)
        assertTrue("names the best-rated-vs-busiest finding",
            ledger.findings.any { it.text.contains("weren't your busiest") })
        assertTrue("ships the non-causal disclaimer", ledger.disclaimer.contains("not cause and effect"))
    }

    @Test fun tooLittleHistoryYieldsNoFindings() {
        val logs = (0 until 4).map { log(base + it.toLong(), 4) }
        val ledger = FeltOutputLedger.compute(base, base + 3, emptyList(), logs)
        assertFalse(ledger.hasData)
        assertTrue(ledger.findings.isEmpty())
        assertNull(ledger.correlation)
    }
}
