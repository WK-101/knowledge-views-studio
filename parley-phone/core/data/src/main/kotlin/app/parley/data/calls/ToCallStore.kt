package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallState
import app.parley.common.storage.PersistentStores
import app.parley.data.backup.BackupExtras
import app.parley.data.security.RecordSealing
import app.parley.data.security.SealedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "To call" list ([ToCall]): one small [SealedDocument] (`docs/to_call` in no-backup storage), sealed with the
 * small-records key (it holds numbers, a private contact's among them), so the call screen and the reminder can write
 * it while the phone is locked. Read on first use, off the main thread.
 *
 * [isPrivate] tells a private contact's number: those items stay out of the backup's settings part (like every
 * other trace of a private contact outside its own section); they are short-lived reminders, not contact data.
 * [onChanged] runs after every change.
 *
 * A stored list that can't be opened right now (a Keystore hiccup) is never taken for an empty one: the store stays
 * unloaded, refuses changes and reads again later. The list is never stored as plain text: when sealing fails, a change
 * stays in memory until a later write (or [RecordSealing]) can seal it.
 */
class ToCallStore internal constructor(
    context: Context,
    private val isPrivate: suspend (String) -> Boolean,
    /** Seals the encoded list; null when sealing isn't possible right now (tests replace it). */
    sealOverride: ((String) -> String?)?,
) : RecordSealing.Resealable {
    constructor(context: Context, isPrivate: suspend (String) -> Boolean) : this(context, isPrivate, null)

    private val appContext = context.applicationContext

    /** The strip's fold, and the list as earlier versions kept it (copied into [doc] once). */
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    private val doc = SealedDocument(
        appContext, File(appContext.noBackupFilesDir, "docs/to_call"), SealedDocument.Whole(ToCall::encode, ToCall::decode),
        legacy = SealedDocument.Legacy({ prefs }, KEY, ToCall::decode), sealOverride = sealOverride,
    )

    /** The list as stored; empty until [load] ran (the screens call it). */
    val state: StateFlow<ToCallState> = doc.state

    @Volatile var onChanged: (() -> Unit)? = null

    private val _folded = MutableStateFlow(false)

    /** The strip at the top of Recents is folded to one quiet line (this phone's choice, not backed up). */
    val folded: StateFlow<Boolean> = _folded.asStateFlow()

    @Volatile private var foldRead = false

    fun setFolded(folded: Boolean) {
        _folded.value = folded
        foldRead = true
        prefs.edit().putBoolean(K_FOLDED, folded).apply()
    }

    /** Whether the stored list could be read (false while its sealed value can't be opened: try again later). */
    val available: Boolean get() = doc.available

    /** Whether a change waits to be written because sealing failed (the next write, or [resealPlain], retries). */
    val pendingWrite: Boolean get() = doc.pendingWrite

    /**
     * Reads the stored list once. A sealed value that can't be opened right now leaves the store unloaded (and its
     * [state] as it was): it is read again on the next call, never taken for an empty list.
     */
    suspend fun load(): ToCallState {
        readFold()
        return doc.load()
    }

    private suspend fun readFold() {
        if (foldRead) return
        _folded.value = withContext(Dispatchers.IO) { prefs.getBoolean(K_FOLDED, false) }
        foldRead = true
    }

    /** What a [write] did. */
    data class Write(
        val state: ToCallState,
        /** The list changed (in memory; [saved] says whether it reached storage). */
        val changed: Boolean,
        /** The list is stored as [state] (false: unreadable now, or waiting to be sealed). */
        val saved: Boolean,
    )

    /**
     * Applies [f] and writes the result. Nothing is applied while the stored list can't be read (the change is
     * refused, so nothing stored is ever overwritten). When sealing fails the change stays in memory and is written by
     * a later write or [resealPlain]; the list is never stored as plain text.
     */
    suspend fun write(f: (ToCallState) -> ToCallState): Write {
        readFold()
        val w = doc.write(f)
        if (w.changed) runCatching { onChanged?.invoke() }
        return Write(w.state, w.changed, w.saved)
    }

    /** Applies [f] and writes the result; returns the list (see [write]). */
    suspend fun update(f: (ToCallState) -> ToCallState): ToCallState = write(f).state

    /** RecordSealing's run: a list waiting to be sealed (or stored plain by an older version) is written sealed. */
    override suspend fun resealPlain(): Boolean = doc.resealPlain()

    /** Inside the encrypted backup's settings section; private contacts' numbers stay out. */
    val backupExtras: BackupExtras = object : BackupExtras {
        override val section = "to call"
        override val sections = setOf(PersistentStores.Sections.TO_CALL)

        override suspend fun export(): Map<String, String> {
            val s = load()
            // Never an empty list in place of one that can't be read right now: the backup reports this part instead.
            check(available) { "The To call list can't be read right now" }
            val leaveOut = s.items.map { it.number }.filter { runCatching { isPrivate(it) }.getOrDefault(true) }.toSet()
            return mapOf(X_STATE to ToCall.encode(ToCall.without(s) { it in leaveOut }))
        }

        override suspend fun import(values: Map<String, String>) {
            val restored = values[X_STATE]?.let { ToCall.decode(it) } ?: return
            update { ToCall.merge(it, restored) }
        }
    }

    private companion object {
        const val FILE = "to_call"
        const val KEY = "state_v1"
        const val K_FOLDED = "strip_folded"
        const val X_STATE = "${BackupExtras.PREFIX}tocall.state"
    }
}
