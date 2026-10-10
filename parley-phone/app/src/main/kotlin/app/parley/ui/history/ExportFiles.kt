package app.parley.ui.history

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
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
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import app.parley.common.CallEntry
import app.parley.common.history.CallExport
import app.parley.common.history.ExportFormat
import app.parley.common.history.ExportNote
import app.parley.common.history.ExportRow
import app.parley.container
import app.parley.jobs.UserJobs
import app.parley.data.PhoneEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import app.parley.R

/**
 * Readable exports of call history: CSV, JSON, ICS and PDF files shared through the app's FileProvider,
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
        CallExport.rows(calls, names, { id -> id?.let { sims[it] } }, notes, PhoneEnv.countryIso(context))
    }

    /**
     * Writes [rows] in [format] and returns the file (older exports are removed first). [excelBom]: a CSV starts with a
     * byte-order mark so Excel reads accents right (the export sheet's tick box; null: the last choice, on by default).
     */
    suspend fun write(
        context: Context,
        rows: List<ExportRow>,
        subject: String?,
        format: ExportFormat,
        excelBom: Boolean? = null,
    ): File = withContext(Dispatchers.IO) {
        cleanup(context)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val d = dir(context).apply { mkdirs() }
        val file = File(d, CallExport.fileName(subject, now, zone, format))
        when (format) {
            ExportFormat.CSV -> file.writeText(CallExport.csv(rows, zone, bom = excelBom ?: context.container.history.prefs.current().csvBom), Charsets.UTF_8)
            ExportFormat.JSON -> file.writeText(CallExport.json(rows, zone), Charsets.UTF_8)
            ExportFormat.ICS -> file.writeText(CallExport.ics(rows, now), Charsets.UTF_8)
            ExportFormat.PDF -> {
                val doc = PdfDocument()
                try {
                    val layout = PdfLayout(context, A4_WIDTH, A4_HEIGHT)
                    val pages = layout.paginate(rows)
                    pages.forEachIndexed { i, range ->
                        val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, i + 1).create())
                        layout.draw(page.canvas, title(context, subject), rows, range, i, pages.size, zone)
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

    fun share(context: Context, file: File, format: ExportFormat) = share(context, file, format.mime)

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.hist_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Hands a prepared export ([UserJobs.Opener]) to the share sheet, or to the print dialog ("Save as PDF" included),
     * from [activity]: only when the person tapped its notification or snackbar. False when the file is gone.
     */
    fun open(activity: Activity, o: UserJobs.Opener): Boolean {
        // Only a plain name in the export folder, never a path from the intent.
        if (o.file.isEmpty() || o.file.contains('/') || o.file.startsWith(".")) return false
        val file = File(dir(activity), o.file)
        if (!file.isFile) return false
        if (o.print) {
            val pm = activity.getSystemService(PrintManager::class.java) ?: return false
            pm.print(file.nameWithoutExtension, PdfFileAdapter(file), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        } else {
            share(activity, file, o.mime.ifEmpty { "application/octet-stream" })
        }
        return true
    }

    /** What a job hands back once [file] (written by [write]) is ready: share it, or with [print] print it. */
    fun opener(file: File, format: ExportFormat, print: Boolean = false): UserJobs.Opener = UserJobs.Opener(file.name, format.mime, print)

    /**
     * Deletes export files and what other screens handed to apps through the share cache once they are older than
     * [olderThanMillis]: contact cards, voicemail audio, rule exports and transfer files. Run at every start and by the
     * upkeep; a scanned card is deleted as soon as its import has read it.
     */
    fun cleanup(context: Context, olderThanMillis: Long = SAFE_AGE_MS, now: Long = System.currentTimeMillis()) {
        val cutoff = now - olderThanMillis
        fun sweep(d: File) = d.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }
        sweep(dir(context))
        SHARED_DIRS.forEach { sweep(File(context.cacheDir, it)) }
    }

    /**
     * How old a shared file must be before a sweep deletes it. Never "all at start": a process started by another
     * app opening a shared file (an e-mail draft attaching it late) must still find it.
     */
    const val SAFE_AGE_MS = 60 * 60_000L

    /**
     * Cache folders other screens share files from (FileProvider paths "share" and "transfer", and the shared labels'
     * update files: a farewell update holds the label's contacts and has its title in its name).
     */
    private val SHARED_DIRS = listOf("share", "transfer", "label_updates")

    private fun title(context: Context, subject: String?) =
        if (subject.isNullOrBlank()) context.getString(R.string.hist_settings_title) else context.getString(R.string.hist_export_title_subject, subject)

    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842

    /** Table layout in PostScript points, shared by the PDF file and the print adapter. */
    internal class PdfLayout(private val context: Context, private val width: Int, private val height: Int) {
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

        fun draw(canvas: Canvas, title: String, rows: List<ExportRow>, range: IntRange, page: Int, pageCount: Int, zone: ZoneId) {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            var y = margin + 14f
            canvas.drawText(title, margin, y, titleP)
            y += 16f
            canvas.drawText(context.resources.getQuantityString(R.plurals.hist_pdf_made_with, rows.size, rows.size, fmt.format(Instant.now().atZone(zone))), margin, y, grey)
            y += 24f
            val w = width - 2 * margin
            val cols = floatArrayOf(0f, 92f, 170f, w - 110f, w - 55f)
            val heads = listOf(R.string.hist_pdf_col_date, R.string.hist_pdf_col_type, R.string.hist_pdf_col_who, R.string.hist_pdf_col_duration, R.string.blk_editor_sim).map { context.getString(it) }
            heads.forEachIndexed { i, h -> canvas.drawText(h, margin + cols[i], y, bold) }
            y += 4f
            canvas.drawLine(margin, y, width - margin, y, line)
            y += rowH - 4f
            for (i in range) {
                val r = rows[i]
                val who = listOfNotNull(r.name, r.number.ifBlank { context.getString(R.string.blk_private_number) }.takeIf { r.name == null || r.number.isNotBlank() }).joinToString(" · ")
                val cells = listOf(
                    fmt.format(Instant.ofEpochMilli(r.date).atZone(zone)),
                    context.getString(HistoryText.callType(r.type)),
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
                    canvas.drawText(TextUtils.ellipsize(context.getString(R.string.case_pdf_note, n.replace('\n', ' ')), grey, w - cols[2], TextUtils.TruncateAt.END).toString(), margin + cols[2], y, grey)
                }
                y += 4f
                canvas.drawLine(margin, y, width - margin, y, line)
                y += rowH - 4f
            }
            canvas.drawText(context.getString(R.string.case_pdf_page, page + 1, pageCount), margin, height - margin, grey)
        }
    }

    /** Prints a PDF Parley already wrote (an export prepared in the background), page for page. */
    private class PdfFileAdapter(private val file: File) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
        }

        @Suppress("TooGenericExceptionCaught") // The print framework wants a failure, whatever the reason.
        override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
            try {
                file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.javaClass.simpleName)
            }
        }
    }

    /** Name for a call row: the contact, else the call log's cached name. Never a placeholder. */
    fun nameFor(e: CallEntry, contactName: (String) -> String?): String? =
        (if (e.number.isNotBlank()) contactName(e.number) else null) ?: e.cachedName?.takeIf { it.isNotBlank() }
}
