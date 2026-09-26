package app.parley.data.extras

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import app.parley.common.backup.RecordJson
import app.parley.common.circle.InteractionType
import app.parley.common.extras.MarkdownNotes
import app.parley.data.DataContainer
import app.parley.data.EventItem
import app.parley.data.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/** C5: the Markdown export's folder and switches, and how the last run went. */
data class MarkdownStatus(
    val folderUri: String? = null,
    val folderName: String? = null,
    /** Export again with the hourly folder-sync run. */
    val auto: Boolean = true,
    /** Only people in your Circle (else every contact). */
    val onlyCircle: Boolean = false,
    val lastAt: Long = 0,
    val lastWritten: Int = 0,
    val lastPeople: Int = 0,
    /** A [app.parley.common.StoredStatus] kind when the last run couldn't write (see [MarkdownExport]). */
    val lastProblem: String? = null,
)

/**
 * C5: one-way export of every person as a Markdown file ([MarkdownNotes]) into a folder picked with the system
 * picker (SAF): Obsidian vaults, Syncthing folders, a USB drive. Parley only writes: it never reads the files back.
 * Files are rewritten only when their content changed, files of people who are gone (or left the Circle, with
 * "only Circle") are removed, and files Parley didn't write are never touched (a clash gets another name).
 * Private (vault) contacts are never exported: they aren't in the system contacts this reads.
 */
class MarkdownExport(private val context: Context, private val c: DataContainer) {
    /** The worded parts of a file, in the app's language (see [MarkdownNotes]). */
    class Texts(
        val headings: MarkdownNotes.Headings,
        val phoneLabel: (Int, String?) -> String,
        val emailLabel: (Int, String?) -> String,
        val eventLabel: (EventItem) -> String,
        val keepInTouch: (Int) -> String,
        val call: (CallType, Long) -> String,
        val interaction: (InteractionType) -> String,
        val callNote: String,
    )

    private val cr = context.contentResolver
    private val prefs = context.getSharedPreferences("markdown_export", Context.MODE_PRIVATE)
    private val stateFile = File(context.filesDir, "markdown_export_state.json")
    private val mutex = Mutex()

    private val _status = MutableStateFlow(load())
    val status: StateFlow<MarkdownStatus> = _status

    private fun load() = MarkdownStatus(
        prefs.getString("folder", null), prefs.getString("folderName", null), prefs.getBoolean("auto", true), prefs.getBoolean("onlyCircle", false),
        prefs.getLong("lastAt", 0), prefs.getInt("lastWritten", 0), prefs.getInt("lastPeople", 0), prefs.getString("lastProblem", null),
    )

    fun setFolder(uri: Uri?, name: String?) {
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.edit().putString("folder", uri?.toString()).putString("folderName", name).remove("lastProblem").apply()
        stateFile.delete() // a new folder: nothing there is ours yet
        _status.value = load()
    }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        _status.value = load()
    }

    fun setOnlyCircle(on: Boolean) {
        prefs.edit().putBoolean("onlyCircle", on).apply()
        _status.value = load()
    }

    /** Files Parley wrote: name → content hash. */
    private fun readState(): MutableMap<String, String> = runCatching {
        val o = JSONObject(stateFile.readText())
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeState(s: Map<String, String>) {
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(JSONObject(s).toString())
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
    }

    private fun finish(written: Int, people: Int, problem: String?) {
        prefs.edit().putLong("lastAt", System.currentTimeMillis()).putInt("lastWritten", written).putInt("lastPeople", people)
            .apply { if (problem == null) remove("lastProblem") else putString("lastProblem", problem) }.apply()
        _status.value = load()
    }

    /** Runs one export; returns how many files were written. Does nothing without a folder. */
    suspend fun exportNow(texts: Texts): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val folder = status.value.folderUri?.let(Uri::parse) ?: return@withContext 0
            if (!Permissions.has(context, android.Manifest.permission.READ_CONTACTS)) {
                finish(0, 0, NO_PERMISSION)
                return@withContext 0
            }
            val treeId = DocumentsContract.getTreeDocumentId(folder)
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(folder, treeId)
            val existing = HashMap<String, Uri>()
            val listed = try {
                cr.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(folder, treeId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val name = cur.getString(1) ?: continue
                        if (name.endsWith(".md", ignoreCase = true)) existing[name] = DocumentsContract.buildDocumentUriUsingTree(folder, cur.getString(0))
                    }
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
            if (!listed) {
                finish(0, 0, FOLDER_GONE)
                return@withContext 0
            }

            val state = readState()
            // Files that aren't ours keep their names: ours pick another.
            val taken = existing.keys.filter { it !in state }.map { it.lowercase(java.util.Locale.ROOT) }.toMutableSet()
            val members = c.circle.members().associate { it.lookupKey to it.everyDays }
            val onlyCircle = status.value.onlyCircle
            val people = c.contacts.snapshot().filter { !onlyCircle || it.lookupKey in members }
                .sortedWith(compareBy<app.parley.common.ContactSummary> { it.displayName.lowercase() }.thenBy { it.id })
            val interactions = c.circle.interactions.all().groupBy { it.lookupKey }
            val notes = runCatching { c.meta.allCallNotes().first() }.getOrDefault(emptyList()).groupBy { it.numberKey }
            val index = c.history.index.value ?: withTimeoutOrNull(10_000) { c.history.index.filterNotNull().first() }
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()

            var written = 0
            val produced = HashSet<String>()
            for (s in people) {
                val d = runCatching { c.contacts.details(s.id) }.getOrNull() ?: continue
                val keys = d.phones.map { PhoneNumbers.matchKey(it.value) }.filter { it.isNotEmpty() }.toSet()
                val calls = index?.calls(personKey = "c:${s.lookupKey}").orEmpty().take(MAX_CALLS)
                val timeline = calls.map { MarkdownNotes.Entry(it.date, texts.call(it.type, it.durationSec)) } +
                    interactions[s.lookupKey].orEmpty().map { MarkdownNotes.Entry(it.time, texts.interaction(it.type), it.note) } +
                    keys.flatMap { notes[it].orEmpty() }.distinctBy { it.id }.map { MarkdownNotes.Entry(it.callDate, texts.callNote, it.text) }
                val days = members[s.lookupKey]
                val pinned = runCatching { c.meta.meta(s.lookupKey)?.pinnedNote }.getOrNull().orEmpty()
                // R9: promises from every note of this person, not only the ones in the (capped) timeline.
                val promises = (listOf(pinned) + interactions[s.lookupKey].orEmpty().mapNotNull { it.note } +
                    keys.flatMap { notes[it].orEmpty() }.distinctBy { it.id }.map { it.text })
                    .flatMap { app.parley.common.circle.Promises.parse(it) }
                val person = MarkdownNotes.Person(
                    name = d.displayName.ifBlank { s.displayName },
                    phones = d.phones.map { MarkdownNotes.Field(texts.phoneLabel(it.type, it.label), it.value) },
                    emails = d.emails.map { MarkdownNotes.Field(texts.emailLabel(it.type, it.label), it.value) },
                    dates = d.events.map { MarkdownNotes.Field(texts.eventLabel(it), it.date) },
                    labels = runCatching { c.contacts.labelTitlesOf(s.id).toList() }.getOrDefault(emptyList()),
                    company = d.company,
                    pinnedNote = pinned,
                    note = d.note,
                    keepInTouch = days?.let(texts.keepInTouch),
                    keepInTouchDays = days,
                    timeline = timeline,
                    promises = promises,
                )
                val name = MarkdownNotes.fileName(person.name, taken)
                val bytes = MarkdownNotes.render(person, zone, now, texts.headings).toByteArray(Charsets.UTF_8)
                // The export date is in every file: compare without it, so unchanged people aren't rewritten daily.
                val hash = RecordJson.sha256Hex(MarkdownNotes.render(person, zone, 0, texts.headings).toByteArray(Charsets.UTF_8))
                produced += name
                if (state[name] == hash && name in existing) continue
                val ok = try {
                    val uri = existing[name] ?: DocumentsContract.createDocument(cr, parentDoc, "text/markdown", name)
                    if (uri == null) false else {
                        cr.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                        existing[name] = uri
                        true
                    }
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    state[name] = hash
                    written++
                }
            }
            // People who are gone: remove only files Parley wrote.
            for (name in state.keys.toList()) {
                if (name in produced) continue
                existing[name]?.let { u -> runCatching { DocumentsContract.deleteDocument(cr, u) } }
                state.remove(name)
            }
            writeState(state)
            finish(written, people.size, null)
            written
        }
    }

    companion object {
        const val NO_PERMISSION = "no_permission"
        const val FOLDER_GONE = "folder_gone"

        /** Newest calls kept in one file (the whole history of a daily caller would drown the notes). */
        private const val MAX_CALLS = 200
    }
}
