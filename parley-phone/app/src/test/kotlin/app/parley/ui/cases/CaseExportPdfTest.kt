package app.parley.ui.cases

import android.app.Application
import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseReference
import app.parley.common.cases.CaseReport
import app.parley.common.cases.CaseStatus
import app.parley.testing.AppTestbed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Export as PDF" builds on a single tap. [CaseExport.lines] assembles the whole report in one pass and reads the
 * timeline itself when the screen hasn't produced one yet, so one action makes the report the PDF is drawn from. The
 * report used to be gated on the screen's reactive timeline, so the first tap did nothing and the person had to tap
 * again. ([CasePdf.write] draws these lines on the page; its Android PdfDocument can't run under Robolectric.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CaseExportPdfTest {
    private lateinit var t: AppTestbed

    @Before fun setUp() { t = AppTestbed() }

    @After fun tearDown() = t.close()

    private val case = CaseFile(
        id = "a", name = "Northwind Energy", numbers = listOf("+442079460000"),
        references = listOf(CaseReference("r1", "Complaint", "CMP-7781", 1_000L)),
        status = CaseStatus.WAITING, statusAt = 2_000L,
    )

    @Test fun one_action_builds_the_report_without_a_preloaded_timeline() = runBlocking {
        // timeline = null: the person tapped before the screen's reactive timeline arrived. One call still builds it all.
        val lines = CaseExport.lines(
            t.c, case, timeline = null, withReferences = true,
            openReference = { it.value }, words = CasePdf.Words(t.context), iso = "GB", now = 10_000L,
        )
        assertTrue("the complaint names the organisation", lines.any { it.text.contains("Northwind Energy") })
        assertTrue("where the case stands is in it", lines.any { it.text.contains("Waiting", ignoreCase = true) })
        assertTrue("the chosen reference number is in it", lines.any { it.text.contains("CMP-7781") })
        // The lines turn into at least one drawable page, as CasePdf lays them out.
        val pages = CaseReport.paginate(lines, lines.map { 10f }, 100f)
        assertTrue("the report is drawable on at least one page", pages.isNotEmpty() && pages.first().first == 0)
    }

    @Test fun reference_numbers_are_left_out_unless_chosen() = runBlocking {
        val lines = CaseExport.lines(
            t.c, case, timeline = null, withReferences = false,
            openReference = { error("references must not be opened when they were not chosen") },
            words = CasePdf.Words(t.context), iso = "GB",
        )
        assertFalse("the sealed reference number is never shown", lines.any { it.text.contains("CMP-7781") })
    }
}
