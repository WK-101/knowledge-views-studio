package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallState
import app.parley.common.storage.PersistentStores
import app.parley.data.backup.BackupExtras
import app.parley.data.security.RecordCrypto
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
 * [onChanged] runs after every write (the app reschedules the reminder there).
 */
class ToCallStore(context: Context, private val isPrivate: suspend (String) -> Boolean) {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val crypto by lazy { RecordCrypto.get(appContext) }
    private val mutex = Mutex()

    @Volatile private var loaded = false
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

    /** Reads the stored list once. */
    suspend fun load(): ToCallState = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked(): ToCallState {
        if (!loaded) {
            _state.value = ToCall.decode(runCatching { crypto.openText(prefs.getString(KEY, null)) }.getOrNull())
            _folded.value = prefs.getBoolean(K_FOLDED, false)
            loaded = true
        }
        return _state.value
    }

    /** Applies [f] and writes the result; returns it. */
    suspend fun update(f: (ToCallState) -> ToCallState): ToCallState = withContext(Dispatchers.IO) {
        val next = mutex.withLock {
            val now = loadLocked()
            val next = f(now)
            if (next == now) return@withLock null
            _state.value = next
            val encoded = ToCall.encode(next)
            prefs.edit().putString(KEY, crypto.sealText(encoded) ?: encoded).apply()
            next
        }
        if (next != null) runCatching { onChanged?.invoke() }
        next ?: _state.value
    }

    /** Inside the encrypted backup's settings section; private contacts' numbers stay out. */
    val backupExtras: BackupExtras = object : BackupExtras {
        override val section = "to call"
        override val sections = setOf(PersistentStores.Sections.TO_CALL)

        override suspend fun export(): Map<String, String> {
            val s = load()
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
