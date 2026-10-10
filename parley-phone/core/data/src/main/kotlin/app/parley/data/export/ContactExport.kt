package app.parley.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.parley.common.ExplainedFailure
import app.parley.common.PhoneIdentity
import app.parley.common.catching
import app.parley.common.circle.InteractionType
import app.parley.common.circle.Promises
import app.parley.common.people.ContactRef
import app.parley.common.people.PrivateLabels
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.record.withoutMessengers
import app.parley.common.vcard.CardNotes
import app.parley.common.vcard.CsvExports
import app.parley.common.vcard.CsvFormat
import app.parley.common.vcard.SealedVCard
import app.parley.common.vcard.VCardStream
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.R
import app.parley.data.RecordDetails
import app.parley.data.VCardIO
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.security.Privacy
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import kotlin.coroutines.coroutineContext

/**
 * Getting everything out in open formats: vCard 4.0 (plain, or encrypted with a passphrase, [SealedVCard]), CSV in
 * Parley's, Google's or Outlook's columns, or the notes alone as plain text. Private contacts go in only when asked
 * ([Choice.includePrivate]); archived contacts always do, flagged as archived. Parley's notes about each person ride
 * along as vCard properties of Parley's own and a readable NOTE ([CardNotes]). The other way, [VCardIO] reads such a
 * file back: private contacts land private again and archived ones archived, with their notes ([NotesSink]).
 */
class ContactExport(private val context: Context, private val c: DataContainer) : VCardIO.NotesSink {
    enum class Format(val mime: String, val fileName: String) {
        VCARD("text/x-vcard", "contacts.vcf"),
        SEALED_VCARD(SealedVCard.MIME, "contacts" + SealedVCard.EXTENSION),
        CSV_PARLEY("text/csv", "contacts.csv"),
        CSV_GOOGLE("text/csv", "contacts-google.csv"),
        CSV_OUTLOOK("text/csv", "contacts-outlook.csv"),
        NOTES_TEXT("text/plain", "notes.txt"),
        ;

        val csv: CsvFormat?
            get() = when (this) {
                CSV_PARLEY -> CsvFormat.PARLEY
                CSV_GOOGLE -> CsvFormat.GOOGLE
                CSV_OUTLOOK -> CsvFormat.OUTLOOK
                else -> null
            }

        /** Formats that carry Parley's notes (CSV has no place for them). */
        val carriesNotes: Boolean get() = this == VCARD || this == SEALED_VCARD || this == NOTES_TEXT
    }

    /** What to put in: [includePrivate] private contacts, [includeNotes] Parley's notes about each person. */
    data class Choice(val format: Format, val includePrivate: Boolean = false, val includeNotes: Boolean = true)

    /** One person as exported: their card and Parley's notes about them. */
    private class Person(val record: ContactRecord, val notes: CardNotes)

    /** One export's running state: what was done, for progress, and who couldn't be written. */
    private class Run(val total: Int, val progress: (Int, Int) -> Unit) {
        val failures = ArrayList<String>()
        private var done = 0

        fun step() {
            if (++done % PROGRESS_EVERY == 0) progress(done, total)
        }
    }

    /**
     * Writes [choice] to [target]. [passphrase] encrypts a [Format.SEALED_VCARD] (required there); [words] word the
     * readable summary. Throws [ExplainedFailure] when private contacts were asked for but are locked. A run that fails
     * or is cancelled removes [target]: an empty or cut-off file is no use, and a plain one may already hold private cards.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure, cancellation included, removes the file, then goes on up.
    suspend fun export(
        target: Uri,
        choice: Choice,
        words: CardNotes.Words,
        passphrase: CharArray? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): VCardIO.ExportResult = withContext(Dispatchers.IO) {
        try {
            write(target, choice, words, passphrase, progress)
        } catch (e: Throwable) {
            discard(context, target)
            throw e
        }
    }

    private suspend fun write(
        target: Uri,
        choice: Choice,
        words: CardNotes.Words,
        passphrase: CharArray?,
        progress: (Int, Int) -> Unit,
    ): VCardIO.ExportResult {
        val sealed = choice.format == Format.SEALED_VCARD
        require(!sealed || (passphrase?.size ?: 0) > 0) { "An encrypted export needs a passphrase" }
        val privates = privatesFor(choice)
        val notes = if (choice.includeNotes && choice.format.carriesNotes) Notes.read(c) else Notes.NONE
        val ids = c.contacts.snapshot().map { it.id }
        // Archived contacts go in like any saved contact, flagged as archived (they come back archived).
        val archived = catching { c.archive.recordsForExport() }.getOrDefault(emptyList())
        val run = Run(ids.size + privates.size + archived.size, progress)
        val out = context.contentResolver.openOutputStream(target, "wt") ?: run {
            discard(context, target)
            return VCardIO.ExportResult(0, listOf(context.getString(R.string.data_file_write_failed)))
        }
        val photos = choice.format.csv == null
        // Visible contacts streamed from the address book (full photos for vCards), then the private ones.
        val people = sequence {
            for (r in c.records.readAll(ids, fullPhoto = photos)) yield(Person(r.withoutMessengers(), notes.of(r.key, r)))
        }
        val rest = suspend {
            privatePeople(privates, choice, notes, photos, run) + archived.map { r -> Person(r, notes.of(r.key, r).copy(archived = true)) }
        }
        val written = out.use { raw ->
            val csv = choice.format.csv
            when {
                choice.format == Format.NOTES_TEXT -> writeNotes(raw, people, rest(), words, run)
                csv != null -> writeCsv(raw, csv, people, rest(), run, archived.mapTo(HashSet()) { it.key })
                else -> writeCards(if (sealed) SealedVCard.seal(raw, passphrase!!) else raw, people, rest, words, run)
            }
        }
        progress(run.total, run.total)
        return VCardIO.ExportResult(written, run.failures)
    }

    /**
     * The private contacts [choice] asks for: none after a duress unlock (they don't exist as far as anything outside
     * can tell). Throws when they can't be opened now (a key lost for good still exports what's left).
     */
    private suspend fun privatesFor(choice: Choice): List<app.parley.data.vault.VaultSummary> {
        if (!choice.includePrivate || Privacy.duressOnly().hiding) return emptyList()
        val list = c.vault.summariesNow()
        if (list.isNotEmpty() && VaultCrypto.detailNeedsUnlock() && !VaultCrypto.detailKeyLost()) {
            throw ExplainedFailure(context.getString(R.string.data_export_private_locked))
        }
        return list
    }

    private suspend fun privatePeople(
        privates: List<app.parley.data.vault.VaultSummary>,
        choice: Choice,
        notes: Notes,
        photos: Boolean,
        run: Run,
    ): List<Person> = privates.mapNotNull { v ->
        val d = catching { c.vault.details(v.id) }.getOrNull()
        if (d == null) {
            run.failures += v.name
            return@mapNotNull null
        }
        val key = ContactRef.privateKey(v.id)
        val record = RecordDetails.toRecord(d, key, v.labels.map { it.title }, if (photos) c.vault.photoBytes(v.id) else null)
        val own = if (choice.includeNotes) notes.of(key, record).copy(forCalls = d.pinnedNote, context = d.context) else notes.of(key, record)
        Person(record, own.copy(private = true))
    }

    private suspend fun writeNotes(raw: OutputStream, people: Sequence<Person>, privates: List<Person>, words: CardNotes.Words, run: Run): Int =
        writer(raw).use { w ->
            var n = 0
            for (p in people + privates.asSequence()) {
                coroutineContext.ensureActive()
                val text = p.notes.copy(private = false).summary(words)
                if (text.isNotEmpty()) {
                    // The heading line is the file's subject already: each person starts with their name instead.
                    w.write(p.record.displayName.ifBlank { "…" } + "\n" + text.substringAfter('\n') + "\n\n")
                    n++
                }
                run.step()
            }
            n
        }

    private fun writeCsv(raw: OutputStream, format: CsvFormat, people: Sequence<Person>, privates: List<Person>, run: Run, archived: Set<String>): Int {
        // CSV has no photos: every record fits in memory, as in VCardIO.exportCsv.
        val records = ArrayList<ContactRecord>(run.total)
        people.forEach { records += it.record; run.step() }
        privates.forEach { records += it.record }
        writer(raw).use { CsvExports.write(format, records, it, c.records.groupTitles(), archived = archived) }
        return records.size
    }

    @Suppress("TooGenericExceptionCaught") // One card that can't be written is named in the result; the rest go on.
    private suspend fun writeCards(
        stream: OutputStream,
        people: Sequence<Person>,
        privates: suspend () -> List<Person>,
        words: CardNotes.Words,
        run: Run,
    ): Int {
        var n = 0
        VCardStream.CardWriter(writer(stream)).use { w ->
            val titles = c.records.groupTitles()
            fun one(p: Person) {
                try {
                    val own = p.notes.takeUnless { it.isEmpty }
                    w.write(p.record, titles, own, own?.summary(words))
                    n++
                } catch (e: Exception) {
                    Log.w(TAG, "Export failed for one contact", e)
                    run.failures += p.record.displayName.ifBlank { "…" }
                }
                run.step()
            }
            for (p in people) {
                coroutineContext.ensureActive()
                one(p)
            }
            privates().forEach(::one)
        }
        return n
    }

    private fun writer(out: OutputStream): Writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))

    /** Parley's notes, read once for the whole export and handed out per person. */
    private class Notes(
        private val meta: Map<String, ContactMetaEntity>,
        private val callNotes: Map<String, List<CallNoteEntity>>,
        private val moments: Map<String, List<app.parley.data.circle.Interaction>>,
        private val region: String?,
    ) {
        fun of(key: String, record: ContactRecord): CardNotes {
            if (meta.isEmpty() && callNotes.isEmpty() && moments.isEmpty()) return CardNotes()
            val m = meta[key]
            val lines = record.raws.flatMap { it.rows }.filter { it.mimeType == Mime.PHONE }.mapNotNull { it[Col.D1] }
                .flatMap { PhoneIdentity.lookupKeys(it, region) }.toSet()
            val calls = lines.flatMap { callNotes[it].orEmpty() }.distinctBy { it.id }
            val met = moments[key].orEmpty()
            val promises = (listOf(m?.pinnedNote) + met.map { it.note } + calls.map { it.text }).flatMap { Promises.open(it) }.map { it.text }.distinct()
            return CardNotes(
                forCalls = if (ContactRef.isPrivateKey(key)) "" else m?.pinnedNote.orEmpty(),
                keepInTouchDays = m?.reachOutDays,
                callNotes = calls.map { CardNotes.CallNote(it.numberKey, it.callDate, it.text) },
                moments = met.map { CardNotes.Moment(it.type.name.lowercase(), it.time, it.note) },
                promises = promises,
            )
        }

        companion object {
            val NONE = Notes(emptyMap(), emptyMap(), emptyMap(), null)

            suspend fun read(c: DataContainer) = Notes(
                c.meta.allMetaNow().associateBy { it.lookupKey },
                c.meta.allCallNotesNow().groupBy { it.numberKey },
                catching { c.circle.interactions.all() }.getOrDefault(emptyList()).groupBy { it.lookupKey },
                PhoneEnv.countryIso(c.appContext),
            )
        }
    }

    // ------------------------------------------------------------------ import ([VCardIO.NotesSink])

    /** A card marked private becomes a private contact again, with its labels, photo and notes. */
    override suspend fun savePrivate(record: ContactRecord, notes: CardNotes) {
        val d = RecordDetails.toDetails(record).copy(pinnedNote = notes.forCalls.trim(), context = notes.context.trim())
        val id = c.vault.save(null, d)
        val rows = record.raws.flatMap { it.rows }
        rows.firstOrNull { it.mimeType == Mime.PHOTO && (it.blob?.size ?: 0) > 0 }?.blob?.let { catching { c.vault.setPhoto(id, it) } }
        val titles = rows.filter { it.mimeType == Mime.GROUP }.mapNotNull { it[Col.GROUP_TITLE]?.takeIf(String::isNotBlank) }.distinct()
        if (titles.isNotEmpty()) catching { c.vault.updateCallerChoices(id) { s -> s.copy(labels = titles.map { PrivateLabels.Membership(0, it) }) } }
        attach(ContactRef.privateKey(id), null, notes.copy(forCalls = ""))
    }

    /** A visible contact just imported gets its notes; what this phone already has is never overwritten. */
    override suspend fun saveNotes(contactId: Long, notes: CardNotes) {
        val key = c.contacts.lookupKeyOf(contactId) ?: return
        attach(key, contactId, notes)
        // A card exported archived is archived again, its notes with it (they follow the contact to the archive).
        if (notes.archived) catching { c.archive.archive(contactId) }
    }

    private suspend fun attach(key: String, contactId: Long?, notes: CardNotes) {
        val note = notes.forCalls.trim().ifEmpty { null }
        if (note != null) {
            val have = c.meta.meta(key)
            if (have == null) c.meta.setMeta(ContactMetaEntity(key, pinnedNote = note, contactId = contactId))
            else if (have.pinnedNote.isNullOrBlank()) c.meta.setPersonalMeta(key, note, have.preferredMessenger, have.relationLinks, null)
        }
        notes.keepInTouchDays?.let { days -> if (!c.circle.isMember(key)) c.circle.setRhythm(key, contactId, days) }
        for (n in notes.callNotes) {
            if (c.meta.countCallNote(n.line, n.time, n.text) == 0) {
                c.meta.addCallNote(CallNoteEntity(numberKey = n.line, callDate = n.time, text = n.text, createdAt = n.time))
            }
        }
        for (m in notes.moments) {
            val type = InteractionType.entries.firstOrNull { it.name.equals(m.kind, ignoreCase = true) } ?: InteractionType.OTHER
            // The same moment imported twice is recorded once.
            catching { c.circle.interactions.log(key, contactId, type, null, m.time, m.note, "import:${m.kind}:${m.time}:${key.hashCode()}") }
        }
    }

    companion object {
        private const val TAG = "ContactExport"
        private const val PROGRESS_EVERY = 25

        /**
         * Removes a file the system's "Save as" created for an export that didn't happen (best effort: a provider that
         * can't delete keeps it, empty).
         */
        fun discard(context: Context, target: Uri) {
            runCatching {
                if (target.scheme == "file") target.path?.let { File(it).delete() } else DocumentsContract.deleteDocument(context.contentResolver, target)
            }
                .onFailure { Log.w(TAG, "Couldn't remove an unfinished export", it) }
        }

        /**
         * Removes what the folder export of notes, which this export replaced, left on the phone: its folder's access
         * grant, its preferences and its record of the files it wrote (their names are people's names). Cheap when
         * there is nothing to remove. True when it was keeping a folder up to date (its "Keep it up to date", on unless
         * turned off): that folder stops changing now, so the user is told once.
         */
        fun forgetFolderExport(context: Context): Boolean {
            val prefs = context.getSharedPreferences("markdown_export", Context.MODE_PRIVATE)
            val folder = prefs.getString("folder", null)
            val wasUpdating = folder != null && prefs.getBoolean("auto", true)
            folder?.let { u ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            if (prefs.all.isNotEmpty()) context.deleteSharedPreferences("markdown_export")
            File(context.filesDir, "markdown_export_state.json").delete()
            return wasUpdating
        }
    }
}
