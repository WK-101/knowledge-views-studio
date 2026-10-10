package app.parley.data.cases

import android.content.Context
import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseFiles
import app.parley.common.cases.CaseReference
import app.parley.common.cases.CaseState
import app.parley.common.catching
import app.parley.common.storage.PersistentStores
import app.parley.data.backup.BackupExtras
import app.parley.data.backup.RestorePart
import app.parley.data.security.Privacy
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import app.parley.data.security.SealedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Case files ([CaseFiles]): a [SealedDocument] with one file per case (`docs/case_files` in no-backup storage), so a
 * call rewrites only its own case. Sealed with the small-records key (so the call path can add a call while the phone
 * is locked). Each reference number is sealed again on its own: it stays
 * sealed in memory and on screen until you ask to see it, and is opened only for that, for a PDF you chose to include
 * it in, and for the encrypted backup.
 *
 * Like menu memory: a stored document that can't be opened right now is never taken for an empty one (changes are
 * refused until it can be read), and it is never stored as plain text (a change waits in memory until it can be
 * sealed). Private contacts' case files stay out of the backup ([isPrivate]); a duress unlock hides every case file,
 * as it hides notes ([shown]).
 */
class CaseFileStore internal constructor(
    context: Context,
    private val isPrivate: suspend (String) -> Boolean,
    /** Whether private contacts are hidden now (discreet mode), for [shown]. */
    private val discreet: () -> Flow<Boolean>,
    /** The phone's country, for numbers a backup wrote nationally. */
    private val region: () -> String?,
    /** Seals text; null when sealing isn't possible right now (tests replace it). */
    sealOverride: ((String) -> String?)?,
    openOverride: ((String) -> String?)?,
    /** Emits when contacts move into or out of the private contacts, so [shown] asks [isPrivate] again. */
    private val vaultChanges: () -> Flow<Any?> = { flowOf(Unit) },
) : RecordSealing.Resealable {
    constructor(
        context: Context,
        isPrivate: suspend (String) -> Boolean,
        discreet: () -> Flow<Boolean>,
        vaultChanges: () -> Flow<Any?> = { flowOf(Unit) },
        region: () -> String?,
    ) : this(context, isPrivate, discreet, region, null, null, vaultChanges)

    private val appContext = context.applicationContext
    private val crypto by lazy { RecordCrypto.get(appContext) }
    private val seal: (String) -> String? = sealOverride ?: { text -> crypto.sealText(text)?.takeIf { crypto.isSealed(it) } }
    private val open: (String) -> String? = openOverride ?: { text -> if (crypto.isSealed(text)) crypto.openText(text) else null }

    private val doc = SealedDocument(
        appContext, File(appContext.noBackupFilesDir, "docs/case_files"), PerCase,
        // Where earlier versions kept every case in one value: copied into the files once.
        legacy = SealedDocument.Legacy({ appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }, KEY, CaseFiles::decode),
        sealOverride = sealOverride,
    )

    /** The state as stored (reference numbers sealed); empty until [load] ran. */
    val state: StateFlow<CaseState> = doc.state

    /** Whether the stored state could be read. */
    val available: Boolean get() = doc.available

    /**
     * What may show now ([CaseFiles.visible]): nothing during a duress unlock, and in discreet mode no case of a
     * private contact. Whether a case is a private contact's is asked live ([isPrivate], as the backup does), not
     * taken from the flag the case got when it was made: a contact made private since must hide its case too. A
     * number that can't be checked counts as private.
     */
    val shown: Flow<CaseState> by lazy {
        combine(state, Privacy.duressChanges, discreet(), vaultChanges()) { s, _, hidePrivate, _ -> s to hidePrivate }
            .map { (s, hidePrivate) ->
                val notesHidden = !Privacy.duressOnly().notesShown
                if (notesHidden || !hidePrivate) return@map CaseFiles.visible(s, notesHidden, hidePrivate)
                val numbers = s.cases.filter { !it.private }.flatMap { it.numbers }.distinct()
                val privateNow = numbers.filter { n -> catching { isPrivate(n) }.getOrDefault(true) }.toSet()
                CaseFiles.visible(s, notesHidden = false, privateHidden = true) { it in privateNow }
            }
            .flowOn(Dispatchers.IO)
    }

    /** Whether [case] belongs to a private contact now: its flag, or any of its numbers saved privately (checked live). */
    suspend fun isPrivateNow(case: CaseFile): Boolean =
        case.private || case.numbers.any { catching { isPrivate(it) }.getOrDefault(true) }

    suspend fun load(): CaseState = doc.load()

    /**
     * Applies [f] and writes the result; returns the state, or null when the stored state can't be read right now
     * (nothing is applied then, so nothing stored is overwritten).
     */
    suspend fun update(f: (CaseState) -> CaseState): CaseState? = doc.write(f).takeIf { doc.available }?.state

    /**
     * Adds a reference number to case [caseId], sealed on its own; false when it couldn't be (nothing is kept plain)
     * or the case is gone.
     */
    suspend fun addReference(caseId: String, label: String, value: String, typed: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val clean = CaseFiles.cleanReference(value) ?: return false
        val sealed = withContext(Dispatchers.IO) { seal(clean) } ?: return false
        val ref = CaseReference(UUID.randomUUID().toString(), CaseFiles.cleanLabel(label), sealed, now, typed)
        val after = update { CaseFiles.addReference(it, caseId, ref) } ?: return false
        return CaseFiles.byId(after, caseId)?.references?.any { it.id == ref.id } == true
    }

    /** A reference number's value, opened; null when it can't be opened right now. */
    suspend fun openReference(ref: CaseReference): String? = withContext(Dispatchers.IO) { catching { open(ref.value) }.getOrNull() }

    override suspend fun resealPlain(): Boolean = doc.resealPlain()

    /**
     * Inside the encrypted backup, restored with the contacts: every case file but private contacts', with its
     * reference numbers opened (the next phone seals them with its own key). A backup made while a duress unlock hides
     * things has none, as the screens show none then (notes, promises and reference numbers are what it hides).
     */
    val backupExtras: BackupExtras = object : BackupExtras {
        override val section = "case files"
        override val sections = setOf(PersistentStores.Sections.CASE_FILES)
        override val restoreWith = RestorePart.CONTACTS

        override suspend fun export(): Map<String, String> {
            if (!Privacy.duressOnly().notesShown) return emptyMap()
            val s = load()
            check(available) { "Case files can't be read right now" }
            if (s.cases.isEmpty()) return emptyMap()
            val leaveOut = s.cases.filter { isPrivateNow(it) }.map { it.id }.toSet()
            val out = CaseFiles.forBackup(s, { it.id in leaveOut }) { v -> catching { open(v) }.getOrNull() }
            return mapOf(X_STATE to CaseFiles.encode(out))
        }

        override suspend fun import(values: Map<String, String>) {
            val restored = values[X_STATE]?.let { CaseFiles.decode(it) } ?: return
            val sealed = withContext(Dispatchers.IO) { CaseFiles.sealed(restored, seal) }
            check(update { CaseFiles.merge(it, sealed, region()) } != null) { "Case files couldn't be stored" }
        }
    }

    /** One file per case, by its id, and an index with their order and the auto-start switch. */
    private object PerCase : SealedDocument.Layout<CaseState> {
        override fun empty() = CaseState()

        override fun split(s: CaseState): Map<String, String> {
            val index = JSONObject().put(I_AUTO, s.autoStart).put(I_CASES, JSONArray(s.cases.map { it.id }))
            return s.cases.associate { partOf(it.id) to CaseFiles.encode(CaseState(listOf(it))) } + (SealedDocument.INDEX to index.toString())
        }

        override fun parts(index: String) = ids(index).map(::partOf)

        override fun join(index: String, parts: Map<String, String>): CaseState {
            val cases = ids(index).mapNotNull { id -> parts[partOf(id)]?.let { CaseFiles.decode(it).cases.firstOrNull() } }
            val auto = catching { JSONObject(index).optBoolean(I_AUTO, true) }.getOrDefault(true)
            return CaseState(cases, auto)
        }

        private fun ids(index: String): List<String> = catching {
            val a = JSONObject(index).optJSONArray(I_CASES) ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())

        private fun partOf(id: String) = SealedDocument.partName("case-", id)

        private const val I_AUTO = "autoStart"
        private const val I_CASES = "cases"
    }

    private companion object {
        const val FILE = "case_files"
        const val KEY = "state"
        const val X_STATE = "${BackupExtras.PREFIX}cases.state"
    }
}
