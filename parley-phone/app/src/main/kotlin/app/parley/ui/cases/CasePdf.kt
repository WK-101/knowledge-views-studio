package app.parley.ui.cases

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.parley.R
import app.parley.common.CallType
import app.parley.common.cases.CaseEntry
import app.parley.common.cases.CaseReport
import app.parley.common.cases.CaseStatus
import app.parley.ui.common.Format
import app.parley.ui.history.ExportFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * A case file as a PDF for a complaint: [CaseReport]'s lines drawn on A4 pages with Android's own [PdfDocument]
 * (nothing loaded from anywhere), written to the export folder that [ExportFiles] shares, prints and sweeps.
 */
object CasePdf {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val MARGIN = 48f
    private const val FOOTER = 24f
    private const val GAP = 4f

    /** The report's words from the app's resources. */
    class Words(private val context: Context) : CaseReport.Words {
        private val res = context.resources

        override fun title(name: String) = res.getString(R.string.case_title_who, name)
        override fun madeOn(date: String) = res.getString(R.string.case_pdf_made, date)
        override fun numbers(numbers: List<String>) = res.getString(R.string.case_pdf_numbers, numbers.joinToString(", "))
        override val summary: String get() = res.getString(R.string.case_summary)
        override fun status(status: CaseStatus, since: String?) = CaseStatusText.line(res, status, since)
        override fun calls(count: Int, first: String, last: String) = res.getQuantityString(R.plurals.case_pdf_calls, count, count, first, last)
        override val noCalls: String get() = res.getString(R.string.case_no_calls)
        override fun hold(total: Long, average: Long, longest: Long, calls: Int) =
            res.getQuantityString(R.plurals.case_pdf_hold, calls, Format.duration(total), Format.duration(average), Format.duration(longest), calls)
        override fun menu(keys: String) = res.getString(R.string.case_pdf_menu, menuLabel(keys))
        override val references: String get() = res.getString(R.string.case_references_title)
        override fun reference(label: String, value: String, date: String) =
            res.getString(R.string.case_pdf_reference, label.ifEmpty { res.getString(R.string.case_reference_unnamed) }, value, date)
        override fun referencesLeftOut(count: Int) = res.getQuantityString(R.plurals.case_pdf_references_left_out, count, count)
        override val promises: String get() = res.getString(R.string.case_promises_title)
        override fun promise(text: String) = res.getString(R.string.case_pdf_promise, text)
        override val timeline: String get() = res.getString(R.string.case_timeline_title)
        override fun call(entry: CaseEntry.Call) = callLine(context, entry)
        override fun note(text: String) = res.getString(R.string.case_pdf_note, text.replace('\n', ' '))
        override fun referenceAdded(label: String) =
            res.getString(R.string.case_reference_added_on, label.ifEmpty { res.getString(R.string.case_reference_unnamed) })
        override fun date(at: Long) = Format.fullDate(context, at)
    }

    /** Writes [lines] to a PDF named for [name] in the export folder (older exports are removed first). */
    suspend fun write(context: Context, name: String, lines: List<CaseReport.Line>): File = withContext(Dispatchers.IO) {
        ExportFiles.cleanup(context)
        val dir = File(context.cacheDir, "transfer/export").apply { mkdirs() }
        val file = File(dir, fileName(context, name))
        val width = (WIDTH - 2 * MARGIN).toInt()
        val layouts = lines.map { l ->
            StaticLayout.Builder.obtain(l.text, 0, l.text.length, paint(l.style), width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        }
        val heights = lines.indices.map { i -> layouts[i].height + space(lines[i].style) }
        val pages = CaseReport.paginate(lines, heights, HEIGHT - 2 * MARGIN - FOOTER)
        val footer = paint(CaseReport.Style.DETAIL)
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { p, range ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, p + 1).create())
                val canvas = page.canvas
                var y = MARGIN
                for (i in range) {
                    y += space(lines[i].style)
                    canvas.save()
                    canvas.translate(MARGIN, y)
                    layouts[i].draw(canvas)
                    canvas.restore()
                    y += layouts[i].height
                }
                canvas.drawText(context.getString(R.string.case_pdf_page, p + 1, pages.size), MARGIN, HEIGHT - MARGIN + FOOTER / 2, footer)
                doc.finishPage(page)
            }
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    /** "Case file - Barclays.pdf": the name kept to letters, digits and spaces (no path, nothing hidden). */
    private fun fileName(context: Context, name: String): String {
        val safe = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().take(MAX_NAME)
        return context.getString(R.string.case_pdf_file, safe).trim() + ".pdf"
    }

    private const val MAX_NAME = 40

    private fun space(style: CaseReport.Style): Float = when (style) {
        CaseReport.Style.HEADING -> 14f
        CaseReport.Style.DETAIL -> GAP + 2f
        else -> GAP
    }

    private fun paint(style: CaseReport.Style): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        when (style) {
            CaseReport.Style.TITLE -> { textSize = 17f; typeface = Typeface.DEFAULT_BOLD }
            CaseReport.Style.SUBTITLE -> { textSize = 9f; color = Color.DKGRAY }
            CaseReport.Style.HEADING -> { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
            CaseReport.Style.BODY -> textSize = 10f
            CaseReport.Style.DETAIL -> { textSize = 8.5f; color = Color.DKGRAY }
        }
    }
}

/** A call of the timeline in words: who called, how it went, how long, and the hold time and menu keys. */
internal fun callLine(context: Context, e: CaseEntry.Call): String {
    val res = context.resources
    val kind = when {
        e.type == CallType.MISSED -> R.string.case_call_missed
        e.type == CallType.REJECTED -> R.string.case_call_declined
        e.type == CallType.BLOCKED -> R.string.case_call_blocked
        e.type == CallType.VOICEMAIL -> R.string.blk_line_voicemail
        !e.incoming && e.durationSec <= 0 -> R.string.case_call_unanswered
        e.incoming -> R.string.case_call_incoming
        else -> R.string.case_call_outgoing
    }
    return listOfNotNull(
        res.getString(kind),
        res.getString(R.string.case_call_length, Format.duration(e.durationSec)).takeIf { e.durationSec > 0 },
        res.getString(R.string.case_call_hold, Format.duration(e.holdSec)).takeIf { e.holdSec > 0 },
        res.getString(R.string.case_menu, menuLabel(e.menu)).takeIf { e.menu.isNotEmpty() },
    ).joinToString(res.getString(R.string.main_separator))
}
