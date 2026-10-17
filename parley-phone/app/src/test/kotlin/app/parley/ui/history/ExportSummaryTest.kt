package app.parley.ui.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.R
import app.parley.common.history.CallTotals
import app.parley.common.history.FilterPeriod
import app.parley.common.history.HistoryFilter
import app.parley.common.history.TypeGroup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The call-history PDF carries a totals line and the applied filter, so the record says what it covers and what it
 * leaves out. These are the exact strings [ExportSheet] and [RecentsExportHost] put on the PDF's header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExportSummaryTest {
    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    @Test fun the_totals_line_states_the_count_and_the_talk_time() {
        val line = HistoryText.totals(res, CallTotals(outgoing = 2, missed = 1, talkOutSec = 125))
        assertTrue("three calls in all", line.contains("3"))
        assertTrue("the missed count is called out", line.contains("1") && line.contains("missed", ignoreCase = true))
    }

    @Test fun the_coverage_line_names_the_filter() {
        val line = HistoryText.describe(res, HistoryFilter(types = setOf(TypeGroup.MISSED), period = FilterPeriod.LAST_7_DAYS))
        assertTrue("the covered types are named", line.contains("Missed", ignoreCase = true))
        assertTrue("an empty filter covers everything, so nothing is shown", HistoryFilter().isEmpty)
    }

    @Test fun the_covers_label_wraps_the_filter() {
        assertTrue(res.getString(R.string.hist_pdf_covers, "Missed · Last 7 days").contains("Missed · Last 7 days"))
    }
}
