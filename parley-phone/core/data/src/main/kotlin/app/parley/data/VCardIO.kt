package app.parley.data

import app.parley.common.ExplainedFailure
import app.parley.common.catching
import app.parley.data.people.IcuCalendars
import android.content.Context
import android.net.Uri
import android.util.Log
import app.parley.common.ContactSummary
import app.parley.common.DuplicateIndex
import app.parley.common.PhoneEntry
import app.parley.common.record.ContactRecord
import app.parley.common.record.withoutMessengers
import app.parley.common.vcard.ColumnTarget
import app.parley.common.vcard.ContactCsv
import app.parley.common.vcard.CsvColumnMapping
import app.parley.common.vcard.CsvExports
import app.parley.common.vcard.CsvFormat
import app.parley.common.vcard.ImportReport
import app.parley.common.vcard.ImportReportBuilder
import app.parley.common.vcard.ParsedCard
import app.parley.common.vcard.VCardStream
import app.parley.common.vcard.CardNotes
import app.parley.common.vcard.ImportGuard
import app.parley.common.qr.ScannedCard
import app.parley.common.vcard.SealedVCard
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.CancellationException
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
    /** Numbers of private (vault) contacts, so an import doesn't duplicate them as visible contacts. */
    private val vaultNumbers: suspend () -> List<String> = { emptyList() },
) {
    private val cr = context.contentResolver

    /**
     * Where Parley's own notes on imported cards go ([CardNotes], from an open export): a card marked private becomes a
     * private contact again; a visible one gets its notes once it is in the address book. Set by the container.
     */
    interface NotesSink {
        /** Saves [record] as a private contact with [notes]. Throws when it can't (private contacts locked). */
        suspend fun savePrivate(record: ContactRecord, notes: CardNotes)

        /** Attaches [notes] to the contact just imported as [contactId]. */
        suspend fun saveNotes(contactId: Long, notes: CardNotes)
    }

    var notesSink: NotesSink? = null

    /** Whether [source] is an encrypted vCard (or a backup): [import] then needs its passphrase. */
    suspend fun isSealed(source: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            cr.openInputStream(source)?.use { input -> SealedVCard.looksSealed(ByteArray(8).also { b -> input.read(b) }) } ?: false
        } catch (_: Exception) {
            false
        }
    }

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
        val out = cr.openOutputStream(target, "wt") ?: return@withContext ExportResult(0, listOf(context.getString(R.string.data_file_write_failed)))
        VCardStream.CardWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))).use { w ->
            var done = 0
            for (record in store.readAll(ids)) {
                coroutineContext.ensureActive()
                try {
                    w.write(record.withoutMessengers(), titles)
                    n++
                } catch (e: Exception) {
                    Log.w(TAG, "Export failed for one contact", e)
                    // The name only: the exception's own text is for the log, never for the person.
                    failures += record.displayName.ifBlank { "…" }
                }
                if (++done % PROGRESS_EVERY == 0) progress(done, ids.size)
            }
            progress(ids.size, ids.size)
        }
        ExportResult(n, failures)
    }

    /** Writes the given contacts as a CSV in [format]: Parley's own ([ContactCsv]), Google's or Outlook's ([CsvExports]). */
    suspend fun exportCsv(
        target: Uri,
        list: List<ContactSummary>,
        format: CsvFormat = CsvFormat.PARLEY,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        // CSV has no photos, so records are read without them and fit in memory even for large books.
        val records = ArrayList<ContactRecord>(list.size)
        for (r in store.readAll(list.map { it.id }, fullPhoto = false)) {
            coroutineContext.ensureActive()
            records += r.withoutMessengers()
            if (records.size % PROGRESS_EVERY == 0) progress(records.size, list.size)
        }
        val out = cr.openOutputStream(target, "wt") ?: return@withContext ExportResult(0, listOf(context.getString(R.string.data_file_write_failed)))
        BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { CsvExports.write(format, records, it, store.groupTitles()) }
        progress(list.size, list.size)
        ExportResult(records.size)
    }

    /**
     * Imports a .vcf (2.1, 3.0 or 4.0) or a CSV in [ContactCsv] format from [source] into [account]; the format is
     * detected from the content. With [skipDuplicates], cards matching an existing contact (same number or e-mail,
     * or same name when the card has neither) are not imported, and neither are repeats within the file. A file that
     * isn't encrypted plants no hidden or trusted contact ([ImportGuard]).
     */
    suspend fun import(
        source: Uri,
        account: AccountRef,
        progress: (Int, Int) -> Unit = { _, _ -> },
        skipDuplicates: Boolean = false,
        passphrase: CharArray? = null,
    ): ImportReport = when {
        passphrase != null -> importVCard(source, account, progress, skipDuplicates, passphrase)
        looksLikeCsv(source) -> importCsv(source, account, progress, skipDuplicates)
        else -> importVCard(source, account, progress, skipDuplicates)
    }

    /**
     * Imports a vCard file; with [passphrase], an encrypted one ([SealedVCard]). Cards marked private by an open export
     * become private contacts again ([NotesSink]). [keep] are the flags the user ticked back on for a scanned card
     * ([ScannedCard]); a plain file keeps no other ([ImportGuard]).
     */
    suspend fun importVCard(
        source: Uri,
        account: AccountRef,
        progress: (Int, Int) -> Unit = { _, _ -> },
        skipDuplicates: Boolean = false,
        passphrase: CharArray? = null,
        keep: Set<ScannedCard.Flag> = emptySet(),
    ): ImportReport = withContext(Dispatchers.IO) {
        // An encrypted file can't be counted without opening it: its progress has no total.
        val total = if (passphrase == null) countCards(source) else 0
        runImport(account, total, progress, skipDuplicates, fromSealed = passphrase != null, keep = keep) { report, sink ->
            val raw = cr.openInputStream(source) ?: throw ExplainedFailure(context.getString(R.string.data_file_read_failed))
            val input = if (passphrase != null) SealedVCard.open(raw, passphrase) else raw
            VCardStream.reader(input).use { VCardStream.read(it, report, IcuCalendars, sink) }
        }
    }

    suspend fun importCsv(source: Uri, account: AccountRef, progress: (Int, Int) -> Unit = { _, _ -> }, skipDuplicates: Boolean = false): ImportReport =
        withContext(Dispatchers.IO) {
            runImport(account, 0, progress, skipDuplicates) { report, sink ->
                val input = cr.openInputStream(source) ?: throw ExplainedFailure(context.getString(R.string.data_file_read_failed))
                VCardStream.reader(input).use { ContactCsv.read(it, report, sink) }
            }
        }

    /**
     * The start of a CSV file for the column-mapping screen: its separator, the first [rows] lines and whether it
     * is Parley's own format (then [import] reads it without asking). Null when the file isn't a CSV (a vCard).
     */
    data class CsvPreview(val delimiter: Char, val rows: List<List<String>>, val parley: Boolean, val numberList: Boolean)

    suspend fun csvPreview(source: Uri, rows: Int = 30): CsvPreview? = withContext(Dispatchers.IO) {
        if (!looksLikeCsv(source)) return@withContext null
        val input = cr.openInputStream(source) ?: throw ExplainedFailure(context.getString(R.string.data_file_read_failed))
        VCardStream.reader(input).buffered().use { r ->
            val (delimiter, numberList) = ContactCsv.sniff(r)
            val head = ContactCsv.parse(r, delimiter).take(rows).toList()
            CsvPreview(delimiter, head, parley = head.firstOrNull()?.let { CsvColumnMapping.isParleyHeader(it) } == true, numberList = numberList)
        }
    }

    /** Imports a CSV with the columns the user mapped ([mapping], one per column). */
    suspend fun importMapped(
        source: Uri,
        account: AccountRef,
        delimiter: Char,
        mapping: List<ColumnTarget>,
        hasHeader: Boolean,
        progress: (Int, Int) -> Unit = { _, _ -> },
        skipDuplicates: Boolean = false,
    ): ImportReport = withContext(Dispatchers.IO) {
        runImport(account, 0, progress, skipDuplicates) { report, sink ->
            val input = cr.openInputStream(source) ?: throw ExplainedFailure(context.getString(R.string.data_file_read_failed))
            VCardStream.reader(input).use { CsvColumnMapping.read(it, delimiter, mapping, hasHeader, report, sink) }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One pass: duplicates skipped, private cards to the vault, the rest in batches.
    private suspend fun runImport(
        account: AccountRef,
        total: Int,
        progress: (Int, Int) -> Unit,
        skipDuplicates: Boolean,
        fromSealed: Boolean = false,
        keep: Set<ScannedCard.Flag> = emptySet(),
        parse: (ImportReportBuilder, (ParsedCard) -> Unit) -> Unit,
    ): ImportReport {
        val region = PhoneEnv.countryIso(context)
        val report = ImportReportBuilder()
        val ctx = coroutineContext
        val existing = if (skipDuplicates) duplicateIndex() else null
        val groups = store.groupResolver()
        val pending = ArrayList<ParsedCard>()
        // Cards of an open export: private ones wait for the vault, visible ones' notes for their new contact.
        val noteTarget = notesSink
        val privates = ArrayList<ParsedCard>()
        val withNotes = ArrayList<Pair<Long, CardNotes>>()
        var seen = 0
        fun flush() {
            if (pending.isEmpty()) return
            // Imported photos are resized, turned upright and cropped like the editor's.
            // The report says where Android 16 put them when it refused the account chosen.
            val results = store.insertAll(pending.map { it.record }, account, groups, processPhotos = true, announceRedirect = false)
            results.forEachIndexed { i, res ->
                pending[i].notes?.takeUnless { it.isEmpty }?.let { n -> res.contactId?.let { withNotes += it to n } }
                if (res.contactId != null) report.imported++
                res.redirectedTo?.let { report.savedInstead = it.displayLabel }
                res.error?.let { report.fail(pending[i].index, it, pending[i].raw) }
            }
            pending.clear()
        }
        parse(report) { read ->
            ctx.ensureActive()
            // Parley's notes count only from a file Parley encrypted, and only on the card's own numbers (CardNotes.forImport);
            // a plain file's archive, favourite, voicemail, ringtone and other apps' data are left out (ImportGuard).
            val notes = read.notes?.forImport(read.record, fromSealed, region)
            val guarded = ImportGuard.guard(read.record, notes, fromSealed, keep)
            val card = ParsedCard(read.index, guarded.record, read.raw, guarded.notes)
            seen++
            if (existing?.matches(card.record) == true) {
                report.skippedDuplicates++
            } else if (noteTarget?.let { card.notes?.private } == true) {
                // Never into the address book, where every app could read it.
                existing?.add(card.record)
                privates += card
            } else {
                existing?.add(card.record)
                pending += card
                if (pending.size >= INSERT_BATCH) flush()
            }
            if (seen % PROGRESS_EVERY == 0) progress(seen, maxOf(total, seen))
        }
        flush()
        if (noteTarget != null) saveOpenExportParts(noteTarget, privates, withNotes, report)
        progress(seen, maxOf(total, seen))
        contacts.refresh()
        return report.build()
    }

    /** Straight from the provider (the observed list may not have loaded yet on a cold start), plus private contacts. */
    private suspend fun duplicateIndex(): DuplicateIndex = DuplicateIndex().apply {
        contacts.snapshot().forEach { add(it) }
        val vault = catching { vaultNumbers() }.getOrDefault(emptyList())
        if (vault.isNotEmpty()) add(ContactSummary(0, "", "", null, false, vault.map { PhoneEntry(it, 2, null) }))
    }

    /** The parts of an open export that wait for the import: private contacts into the vault, notes onto new contacts. */
    @Suppress("TooGenericExceptionCaught") // A private card that can't be saved is reported, never imported visible.
    private suspend fun saveOpenExportParts(
        target: NotesSink,
        privates: List<ParsedCard>,
        withNotes: List<Pair<Long, CardNotes>>,
        report: ImportReportBuilder,
    ) {
        for (card in privates) {
            coroutineContext.ensureActive()
            try {
                target.savePrivate(card.record, card.notes!!)
                report.imported++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A private contact couldn't be imported", e)
                report.fail(card.index, context.getString(R.string.data_import_private_locked), "")
            }
        }
        for ((id, notes) in withNotes) catching { target.saveNotes(id, notes) }
    }

    /**
     * A quick, bounded look at [source] before it is imported ([ImportGuard.Scan]): about how many contacts (vCards, or
     * non-blank CSV lines less a header), so a large import can offer "Back up first?"; how many marked private, so
     * their unlock can be asked first; and what a plain import will leave out. It never
     * reads more than [ImportGuard.SCAN_CHARS] or one over-long line, so a crafted endless file can't exhaust the
     * memory of the process that also hosts the call screen. Empty when the file can't be read.
     */
    suspend fun preScan(source: Uri): ImportGuard.Scan = withContext(Dispatchers.IO) {
        val csv = looksLikeCsv(source)
        try {
            cr.openInputStream(source)?.use { input ->
                VCardStream.reader(input).use { r ->
                    if (!csv) return@use ImportGuard.scanVCards(r)
                    ImportGuard.scanLines(r).let { it.copy(entries = (it.entries - 1).coerceAtLeast(0)) }
                }
            } ?: EMPTY_SCAN
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            EMPTY_SCAN
        }
    }

    /** Counts cards so progress can show a total ([preScan], bounded). */
    private fun countCards(source: Uri): Int = try {
        cr.openInputStream(source)?.use { input -> VCardStream.reader(input).use { ImportGuard.scanVCards(it).entries } } ?: 0
    } catch (_: Exception) {
        0
    }

    /**
     * A file is treated as CSV when its first non-blank text is not a vCard and its first line has a comma, semicolon
     * or tab, or when it is a plain list of phone numbers, one per line.
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
        private val EMPTY_SCAN = ImportGuard.Scan(0, 0, ImportGuard.Dropped.NONE, capped = false)
    }
}
