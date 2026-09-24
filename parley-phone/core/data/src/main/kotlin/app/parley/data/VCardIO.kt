package app.parley.data

import android.content.Context
import android.net.Uri
import android.util.Log
import app.parley.common.ContactSummary
import app.parley.common.DuplicateIndex
import app.parley.common.record.ContactRecord
import app.parley.common.record.withoutMessengers
import app.parley.common.vcard.ContactCsv
import app.parley.common.vcard.ImportReport
import app.parley.common.vcard.ImportReportBuilder
import app.parley.common.vcard.ParsedCard
import app.parley.common.vcard.VCardStream
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import kotlin.coroutines.coroutineContext

/**
 * vCard and CSV import/export built on [app.parley.common.vcard.VCardMapper] (lossless vCard 4.0) and
 * [ContactRecordStore] (every Data row, full-resolution photos).
 *
 * Exports stream contact by contact to the chosen document. Imports stream card by card, insert in batches,
 * and return an [ImportReport] listing what failed and what could not be mapped, so nothing is lost silently.
 */
class VCardIO(
    private val context: Context,
    private val contacts: ContactsRepository,
    private val store: ContactRecordStore,
    /** Numbers of private (vault) contacts, so an import doesn't duplicate them as visible contacts (F17). */
    private val vaultNumbers: suspend () -> List<String> = { emptyList() },
) {
    private val cr = context.contentResolver

    /** Result of an export: how many contacts were written, and a line for each one that failed. */
    data class ExportResult(val exported: Int, val failures: List<String> = emptyList())

    /** Writes the given contacts to one .vcf file (vCard 4.0). */
    suspend fun export(target: Uri, list: List<ContactSummary>, progress: (Int, Int) -> Unit = { _, _ -> }): ExportResult =
        exportIds(target, list.map { it.id }, progress)

    /** Writes the contacts with [ids] to one .vcf file (vCard 4.0), streaming. */
    suspend fun exportIds(target: Uri, ids: List<Long>, progress: (Int, Int) -> Unit = { _, _ -> }): ExportResult = withContext(Dispatchers.IO) {
        var n = 0
        val failures = ArrayList<String>()
        val titles = store.groupTitles()
        val out = cr.openOutputStream(target, "wt") ?: return@withContext ExportResult(0, listOf("Could not open the file for writing"))
        VCardStream.CardWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))).use { w ->
            var done = 0
            for (record in store.readAll(ids)) {
                coroutineContext.ensureActive()
                try {
                    w.write(record.withoutMessengers(), titles)
                    n++
                } catch (e: Exception) {
                    Log.w(TAG, "Export failed for one contact", e)
                    failures += "${record.displayName.ifBlank { "(no name)" }}: ${e.message ?: e.javaClass.simpleName}"
                }
                if (++done % PROGRESS_EVERY == 0) progress(done, ids.size)
            }
            progress(ids.size, ids.size)
        }
        ExportResult(n, failures)
    }

    /** Writes the given contacts as a structured CSV (see [ContactCsv]). */
    suspend fun exportCsv(target: Uri, list: List<ContactSummary>, progress: (Int, Int) -> Unit = { _, _ -> }): ExportResult = withContext(Dispatchers.IO) {
        // CSV has no photos, so records are read without them and fit in memory even for large books.
        val records = ArrayList<ContactRecord>(list.size)
        for (r in store.readAll(list.map { it.id }, fullPhoto = false)) {
            coroutineContext.ensureActive()
            records += r.withoutMessengers()
            if (records.size % PROGRESS_EVERY == 0) progress(records.size, list.size)
        }
        val out = cr.openOutputStream(target, "wt") ?: return@withContext ExportResult(0, listOf("Could not open the file for writing"))
        BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { ContactCsv.write(records, it, store.groupTitles()) }
        progress(list.size, list.size)
        ExportResult(records.size)
    }

    /**
     * Imports a .vcf (2.1, 3.0 or 4.0) or a CSV in [ContactCsv] format from [source] into [account]; the format is
     * detected from the content. With [skipDuplicates], cards matching an existing contact (same number or e-mail,
     * or same name when the card has neither) are not imported, and neither are repeats within the file.
     */
    suspend fun import(
        source: Uri,
        account: AccountRef,
        progress: (Int, Int) -> Unit = { _, _ -> },
        skipDuplicates: Boolean = false,
    ): ImportReport = if (looksLikeCsv(source)) importCsv(source, account, progress, skipDuplicates) else importVCard(source, account, progress, skipDuplicates)

    suspend fun importVCard(source: Uri, account: AccountRef, progress: (Int, Int) -> Unit = { _, _ -> }, skipDuplicates: Boolean = false): ImportReport =
        withContext(Dispatchers.IO) {
            val total = countCards(source)
            runImport(account, total, progress, skipDuplicates) { report, sink ->
                val input = cr.openInputStream(source) ?: throw java.io.FileNotFoundException("Could not open the file")
                VCardStream.reader(input).use { VCardStream.read(it, report, sink) }
            }
        }

    suspend fun importCsv(source: Uri, account: AccountRef, progress: (Int, Int) -> Unit = { _, _ -> }, skipDuplicates: Boolean = false): ImportReport =
        withContext(Dispatchers.IO) {
            runImport(account, 0, progress, skipDuplicates) { report, sink ->
                val input = cr.openInputStream(source) ?: throw java.io.FileNotFoundException("Could not open the file")
                VCardStream.reader(input).use { ContactCsv.read(it, report, sink) }
            }
        }

    /**
     * M12: the start of a CSV file for the column-mapping screen: its separator, the first [rows] lines and whether it
     * is Parley's own format (then [import] reads it without asking). Null when the file isn't a CSV (a vCard).
     */
    data class CsvPreview(val delimiter: Char, val rows: List<List<String>>, val parley: Boolean, val numberList: Boolean)

    suspend fun csvPreview(source: Uri, rows: Int = 30): CsvPreview? = withContext(Dispatchers.IO) {
        if (!looksLikeCsv(source)) return@withContext null
        val input = cr.openInputStream(source) ?: throw java.io.FileNotFoundException("Could not open the file")
        VCardStream.reader(input).buffered().use { r ->
            val (delimiter, numberList) = ContactCsv.sniff(r)
            val head = ContactCsv.parse(r, delimiter).take(rows).toList()
            CsvPreview(delimiter, head, parley = head.firstOrNull()?.let { app.parley.common.vcard.CsvColumnMapping.isParleyHeader(it) } == true, numberList = numberList)
        }
    }

    /** M12: imports a CSV with the columns the user mapped ([mapping], one per column). */
    suspend fun importMapped(
        source: Uri,
        account: AccountRef,
        delimiter: Char,
        mapping: List<app.parley.common.vcard.ColumnTarget>,
        hasHeader: Boolean,
        progress: (Int, Int) -> Unit = { _, _ -> },
        skipDuplicates: Boolean = false,
    ): ImportReport = withContext(Dispatchers.IO) {
        runImport(account, 0, progress, skipDuplicates) { report, sink ->
            val input = cr.openInputStream(source) ?: throw java.io.FileNotFoundException("Could not open the file")
            VCardStream.reader(input).use { app.parley.common.vcard.CsvColumnMapping.read(it, delimiter, mapping, hasHeader, report, sink) }
        }
    }

    private suspend fun runImport(
        account: AccountRef,
        total: Int,
        progress: (Int, Int) -> Unit,
        skipDuplicates: Boolean,
        parse: (ImportReportBuilder, (ParsedCard) -> Unit) -> Unit,
    ): ImportReport {
        val report = ImportReportBuilder()
        val ctx = coroutineContext
        // Straight from the provider (the observed list may not have loaded yet on a cold start), plus private contacts.
        val existing = if (skipDuplicates) {
            DuplicateIndex().apply {
                contacts.snapshot().forEach { add(it) }
                val vault = runCatching { vaultNumbers() }.getOrDefault(emptyList())
                if (vault.isNotEmpty()) add(ContactSummary(0, "", "", null, false, vault.map { app.parley.common.PhoneEntry(it, 2, null) }))
            }
        } else {
            null
        }
        val groups = store.groupResolver()
        val pending = ArrayList<ParsedCard>()
        var seen = 0
        fun flush() {
            if (pending.isEmpty()) return
            val results = store.insertAll(pending.map { it.record }, account, groups)
            results.forEachIndexed { i, res ->
                if (res.contactId != null) report.imported++
                res.error?.let { report.fail(pending[i].index, it, pending[i].raw) }
            }
            pending.clear()
        }
        parse(report) { card ->
            ctx.ensureActive()
            seen++
            if (existing != null && existing.matches(card.record)) {
                report.skippedDuplicates++
            } else {
                existing?.add(card.record)
                pending += card
                if (pending.size >= INSERT_BATCH) flush()
            }
            if (seen % PROGRESS_EVERY == 0) progress(seen, maxOf(total, seen))
        }
        flush()
        progress(seen, maxOf(total, seen))
        contacts.refresh()
        return report.build()
    }

    /** Counts BEGIN:VCARD lines so progress can show a total. Cheap: one streaming pass, no parsing. */
    private fun countCards(source: Uri): Int = try {
        cr.openInputStream(source)?.use { input ->
            VCardStream.reader(input).buffered().useLines { lines -> lines.count { it.trimStart().startsWith("BEGIN:VCARD", ignoreCase = true) } }
        } ?: 0
    } catch (_: Exception) {
        0
    }

    /**
     * A file is treated as CSV when its first non-blank text is not a vCard and its first line has a comma, semicolon
     * or tab, or when it is a plain list of phone numbers, one per line (F17).
     */
    private fun looksLikeCsv(source: Uri): Boolean = try {
        cr.openInputStream(source)?.use { input ->
            val head = ByteArray(4096)
            val n = BufferedInputStream(input).read(head)
            if (n <= 0) return@use false
            val text = String(head, 0, n, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            val first = text.substringBefore('\n')
            !text.startsWith("BEGIN:VCARD", ignoreCase = true) &&
                (first.any { it == ',' || it == ';' || it == '\t' } || ContactCsv.looksLikeNumberList(text.lines().let { if (n == head.size) it.dropLast(1) else it }))
        } ?: false
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "VCardIO"
        private const val INSERT_BATCH = 50
        private const val PROGRESS_EVERY = 25
    }
}
