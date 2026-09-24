package app.parley.ui.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import app.parley.common.CallEntry
import app.parley.common.history.CallExport
import app.parley.common.history.ExportFormat
import app.parley.common.history.ExportNote
import app.parley.common.history.ExportRow
import app.parley.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Readable exports of call history (H2): CSV, JSON, ICS and PDF files shared through the app's FileProvider,
 * and printing through [PrintManager] with a PDF drawn locally (no WebView, nothing loaded from anywhere).
 * Files go to `cache/transfer/export/` and are deleted on the next app start (or by the daily worker).
 */
object ExportFiles {
    private fun dir(context: Context) = File(context.cacheDir, "transfer/export")

    /** Builds export rows: names from contacts (or the call log's cached name), SIM labels and call notes. */
    suspend fun rows(context: Context, calls: List<CallEntry>, names: (CallEntry) -> String?): List<ExportRow> = withContext(Dispatchers.IO) {
        val c = context.container
        val sims = c.sims.accounts().associate { it.id to it.label }
        val notes = c.meta.allCallNotes().first().map { ExportNote(it.numberKey, it.callDate, it.text) }
        CallExport.rows(calls, names, { id -> id?.let { sims[it] } }, notes)
    }

    /** Writes [rows] in [format] and returns the file (older exports are removed first). */
    suspend fun write(context: Context, rows: List<ExportRow>, subject: String?, format: ExportFormat): File = withContext(Dispatchers.IO) {
        cleanup(context, olderThanMillis = 10 * 60_000L)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val d = dir(context).apply { mkdirs() }
        val file = File(d, CallExport.fileName(subject, now, zone, format))
        when (format) {
            ExportFormat.CSV -> file.writeText(CallExport.csv(rows, zone, bom = context.container.history.prefs.current().csvBom), Charsets.UTF_8)
            ExportFormat.JSON -> file.writeText(CallExport.json(rows, zone), Charsets.UTF_8)
            ExportFormat.ICS -> file.writeText(CallExport.ics(rows, now), Charsets.UTF_8)
            ExportFormat.PDF -> {
                val doc = PdfDocument()
                try {
                    val layout = PdfLayout(A4_WIDTH, A4_HEIGHT)
                    val pages = layout.paginate(rows)
                    pages.forEachIndexed { i, range ->
                        val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, i + 1).create())
                        layout.draw(page.canvas, title(subject), rows, range, i, pages.size, zone)
                        doc.finishPage(page)
                    }
                    FileOutputStream(file).use { doc.writeTo(it) }
                } finally {
                    doc.close()
                }
            }
        }
        file
    }

    fun share(context: Context, file: File, format: ExportFormat) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, "Share call history").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the system print dialog ("Save as PDF" included). */
    fun print(context: Context, rows: List<ExportRow>, subject: String?) {
        val pm = context.getSystemService(PrintManager::class.java) ?: return
        val name = CallExport.fileName(subject, System.currentTimeMillis(), ZoneId.systemDefault(), ExportFormat.PDF).removeSuffix(".pdf")
        pm.print(name, CallPrintAdapter(context.applicationContext, title(subject), rows), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }

    /** Deletes export files (all of them by default). Plaintext exports never outlive the next start. */
    fun cleanup(context: Context, olderThanMillis: Long = 0) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        dir(context).listFiles()?.forEach { f -> if (olderThanMillis == 0L || f.lastModified() < cutoff) f.delete() }
    }

    private fun title(subject: String?) = if (subject.isNullOrBlank()) "Call history" else "Call history · $subject"

    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842

    /** Table layout in PostScript points, shared by the PDF file and the print adapter. */
    internal class PdfLayout(private val width: Int, private val height: Int) {
        private val margin = 36f
        private val rowH = 15f
        private val noteH = 12f
        private val headerH = 64f
        private val footerH = 24f
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.BLACK }
        private val bold = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        private val grey = TextPaint(text).apply { color = Color.DKGRAY; textSize = 8f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
        private val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }

        private fun rowHeight(r: ExportRow) = rowH + r.notes.size.coerceAtMost(3) * noteH

        /** Index ranges of [rows] per page. Always at least one page. */
        fun paginate(rows: List<ExportRow>): List<IntRange> {
            val usable = height - 2 * margin - headerH - footerH
            val pages = ArrayList<IntRange>()
            var start = 0
            var used = 0f
            rows.forEachIndexed { i, r ->
                val h = rowHeight(r)
                if (used + h > usable && i > start) {
                    pages += start until i
                    start = i
                    used = 0f
                }
                used += h
            }
            pages += start until rows.size
            return pages
        }

        fun draw(canvas: android.graphics.Canvas, title: String, rows: List<ExportRow>, range: IntRange, page: Int, pageCount: Int, zone: ZoneId) {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            var y = margin + 14f
            canvas.drawText(title, margin, y, titleP)
            y += 16f
            canvas.drawText("${rows.size} calls · made with Parley on ${fmt.format(Instant.now().atZone(zone))}", margin, y, grey)
            y += 24f
            val w = width - 2 * margin
            val cols = floatArrayOf(0f, 92f, 170f, w - 110f, w - 55f)
            val heads = listOf("Date", "Type", "Name / number", "Duration", "SIM")
            heads.forEachIndexed { i, h -> canvas.drawText(h, margin + cols[i], y, bold) }
            y += 4f
            canvas.drawLine(margin, y, width - margin, y, line)
            y += rowH - 4f
            for (i in range) {
                val r = rows[i]
                val who = listOfNotNull(r.name, r.number.ifBlank { "Private number" }.takeIf { r.name == null || r.number.isNotBlank() }).joinToString(" · ")
                val cells = listOf(
                    fmt.format(Instant.ofEpochMilli(r.date).atZone(zone)),
                    CallExport.typeLabel(r.type),
                    who,
                    if (r.durationSec > 0) CallExport.hms(r.durationSec) else "–",
                    r.simLabel.orEmpty(),
                )
                cells.forEachIndexed { c, s ->
                    val colW = (if (c + 1 < cols.size) cols[c + 1] else w) - cols[c] - 4f
                    canvas.drawText(TextUtils.ellipsize(s, text, colW, TextUtils.TruncateAt.END).toString(), margin + cols[c], y, text)
                }
                r.notes.take(3).forEach { n ->
                    y += noteH
                    canvas.drawText(TextUtils.ellipsize("Note: " + n.replace('\n', ' '), grey, w - cols[2], TextUtils.TruncateAt.END).toString(), margin + cols[2], y, grey)
                }
                y += 4f
                canvas.drawLine(margin, y, width - margin, y, line)
                y += rowH - 4f
            }
            canvas.drawText("Page ${page + 1} of $pageCount", margin, height - margin, grey)
        }
    }

    private class CallPrintAdapter(private val context: Context, private val title: String, private val rows: List<ExportRow>) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null
        private var layout: PdfLayout? = null
        private var pages: List<IntRange> = emptyList()

        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            attributes = new
            val media = new.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
            val l = PdfLayout(media.widthMils * 72 / 1000, media.heightMils * 72 / 1000)
            layout = l
            pages = l.paginate(rows)
            val info = PrintDocumentInfo.Builder("calls.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages.size).build()
            callback.onLayoutFinished(info, old != new)
        }

        override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
            val attrs = attributes ?: return callback.onWriteFailed("Not laid out")
            val l = layout ?: return callback.onWriteFailed("Not laid out")
            val doc = PrintedPdfDocument(context, attrs)
            try {
                val zone = ZoneId.systemDefault()
                pages.forEachIndexed { i, range ->
                    if (cancel?.isCanceled == true) {
                        callback.onWriteCancelled()
                        return
                    }
                    if (ranges.none { i in it.start..it.end } && ranges.none { it == PageRange.ALL_PAGES }) return@forEachIndexed
                    val page = doc.startPage(i)
                    l.draw(page.canvas, title, rows, range, i, pages.size, zone)
                    doc.finishPage(page)
                }
                FileOutputStream(destination.fileDescriptor).use { doc.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES).takeIf { ranges.any { it == PageRange.ALL_PAGES } } ?: ranges.map { it }.toTypedArray())
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                doc.close()
            }
        }
    }

    /** Name for a call row: the contact, else the call log's cached name. Never a placeholder. */
    fun nameFor(e: CallEntry, contactName: (String) -> String?): String? =
        (if (e.number.isNotBlank()) contactName(e.number) else null) ?: e.cachedName?.takeIf { it.isNotBlank() }
}
