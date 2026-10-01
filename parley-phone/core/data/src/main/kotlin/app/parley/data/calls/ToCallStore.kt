package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallState
import app.parley.common.storage.PersistentStores
import app.parley.data.backup.BackupExtras
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The "To call" list ([ToCall]): one small document in its own preferences file, sealed with the small-records key
 * (it holds numbers, a private contact's among them), so the call screen and the reminder can write it while the
 * phone is locked. Read on first use, off the main thread.
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
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val crypto by lazy { RecordCrypto.get(appContext) }
    private val seal: (String) -> String? = sealOverride ?: { text -> crypto.sealText(text)?.takeIf { crypto.isSealed(it) } }
    private val mutex = Mutex()

    @Volatile private var loaded = false

    /** The list in memory is newer than the stored one: sealing failed, so it waits to be written (never plain). */
    @Volatile private var unsaved = false
    private val _state = MutableStateFlow(ToCallState())

    /** The list as stored; empty until [load] ran (the screens call it). */
    val state: StateFlow<ToCallState> = _state.asStateFlow()

    @Volatile var onChanged: (() -> Unit)? = null

    private val _folded = MutableStateFlow(false)

    /** The strip at the top of Recents is folded to one quiet line (this phone's choice, not backed up). */
    val folded: StateFlow<Boolean> = _folded.asStateFlow()

    fun setFolded(folded: Boolean) {
        _folded.value = folded
        prefs.edit().putBoolean(K_FOLDED, folded).apply()
    }

    /** Whether the stored list could be read (false while its sealed value can't be opened: try again later). */
    val available: Boolean get() = loaded

    /** Whether a change waits to be written because sealing failed (the next write, or [resealPlain], retries). */
    val pendingWrite: Boolean get() = unsaved

    /**
     * Reads the stored list once. A sealed value that can't be opened right now leaves the store unloaded (and its
     * [state] as it was): it is read again on the next call, never taken for an empty list.
     */
    suspend fun load(): ToCallState = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            flushLocked()
        }
        _state.value
    }

    /** True once the stored list is in memory. */
    private fun loadLocked(): Boolean {
        if (loaded) return true
        _folded.value = prefs.getBoolean(K_FOLDED, false)
        val stored = prefs.getString(KEY, null)
        val text = try {
            crypto.openTextOrThrow(stored)
        } catch (_: RecordCrypto.UnreadableException) {
            // A Keystore hiccup or an unavailable key: the stored list must be kept as it is, never overwritten.
            return false
        }
        _state.value = ToCall.decode(text)
        loaded = true
        // Stored plain by an older version when sealing failed: sealed now (or kept for RecordSealing's next run).
        if (!stored.isNullOrEmpty() && !crypto.isSealed(stored)) unsaved = true
        return true
    }

    /** Writes the list in memory when it waits to be; false when it still can't be sealed. */
    private fun flushLocked(): Boolean {
        if (!loaded || !unsaved) return true
        val sealed = seal(ToCall.encode(_state.value))
        if (sealed == null) {
            RecordSealing.markPending(appContext)
            return false
        }
        prefs.edit().putString(KEY, sealed).apply()
        unsaved = false
        return true
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
    suspend fun write(f: (ToCallState) -> ToCallState): Write = withContext(Dispatchers.IO) {
        val w = mutex.withLock {
            if (!loadLocked()) return@withLock Write(_state.value, changed = false, saved = false)
            val now = _state.value
            val next = f(now)
            if (next == now) return@withLock Write(now, changed = false, saved = flushLocked())
            _state.value = next
            unsaved = true
            Write(next, changed = true, saved = flushLocked())
        }
        if (w.changed) runCatching { onChanged?.invoke() }
        w
    }

    /** Applies [f] and writes the result; returns the list (see [write]). */
    suspend fun update(f: (ToCallState) -> ToCallState): ToCallState = write(f).state

    /** RecordSealing's run: a list waiting to be sealed (or stored plain by an older version) is written sealed. */
    override suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) {
                // Unreadable is not plain: nothing for the sealing run to do here.
                return@withLock true
            }
            flushLocked()
        }
    }

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
