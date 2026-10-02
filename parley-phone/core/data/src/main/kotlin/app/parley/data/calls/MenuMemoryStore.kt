package app.parley.data.calls

import android.content.Context
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuState
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
 * I6 menu memory ([MenuMemory]): the keys sent in the last call to each number, the saved menu shortcuts and the
 * numbers it must not remember, in one small document in its own preferences file. Sealed with the small-records key
 * (it holds numbers, a private contact's among them), so the call path can write it while the phone is locked.
 *
 * Like the To call list: a stored document that can't be opened right now is never taken for an empty one (changes are
 * refused until it can be read), and it is never stored as plain text (a change waits in memory until it can be sealed).
 * Private contacts' numbers stay out of the backup ([isPrivate]).
 */
class MenuMemoryStore internal constructor(
    context: Context,
    private val isPrivate: suspend (String) -> Boolean,
    /** Seals the encoded state; null when sealing isn't possible right now (tests replace it). */
    sealOverride: ((String) -> String?)?,
) : RecordSealing.Resealable {
    constructor(context: Context, isPrivate: suspend (String) -> Boolean) : this(context, isPrivate, null)

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val crypto by lazy { RecordCrypto.get(appContext) }
    private val seal: (String) -> String? = sealOverride ?: { text -> crypto.sealText(text)?.takeIf { crypto.isSealed(it) } }
    private val mutex = Mutex()

    @Volatile private var loaded = false

    @Volatile private var unsaved = false
    private val _state = MutableStateFlow(MenuState())

    /** The state as stored; empty until [load] ran. */
    val state: StateFlow<MenuState> = _state.asStateFlow()

    /** Whether the stored state could be read. */
    val available: Boolean get() = loaded

    suspend fun load(): MenuState = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            flushLocked()
        }
        _state.value
    }

    private fun loadLocked(): Boolean {
        if (loaded) return true
        val stored = prefs.getString(KEY, null)
        val text = try {
            crypto.openTextOrThrow(stored)
        } catch (_: RecordCrypto.UnreadableException) {
            return false
        }
        _state.value = MenuMemory.decode(text)
        loaded = true
        if (!stored.isNullOrEmpty() && !crypto.isSealed(stored)) unsaved = true
        return true
    }

    private fun flushLocked(): Boolean {
        if (!loaded || !unsaved) return true
        val sealed = seal(MenuMemory.encode(_state.value))
        if (sealed == null) {
            RecordSealing.markPending(appContext)
            return false
        }
        prefs.edit().putString(KEY, sealed).apply()
        unsaved = false
        return true
    }

    /**
     * Applies [f] and writes the result; returns the state, or null when the stored state can't be read right now
     * (nothing is applied then, so nothing stored is overwritten).
     */
    suspend fun update(f: (MenuState) -> MenuState): MenuState? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loadLocked()) return@withLock null
            val now = _state.value
            val next = f(now)
            if (next != now) {
                _state.value = next
                unsaved = true
            }
            flushLocked()
            next
        }
    }

    override suspend fun resealPlain(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { if (!loadLocked()) true else flushLocked() }
    }

    /** Inside the encrypted backup's settings section; private contacts' numbers stay out. */
    val backupExtras: BackupExtras = object : BackupExtras {
        override val section = "menu memory"
        override val sections = setOf(PersistentStores.Sections.MENUS)

        override suspend fun export(): Map<String, String> {
            val s = load()
            check(available) { "Menu memory can't be read right now" }
            val numbers = s.paths.values.map { it.number } + s.shortcuts.map { it.number }
            val leaveOut = numbers.filter { it.isNotEmpty() && runCatching { isPrivate(it) }.getOrDefault(true) }.toSet()
            return mapOf(X_STATE to MenuMemory.encode(MenuMemory.without(s) { it in leaveOut }))
        }

        override suspend fun import(values: Map<String, String>) {
            val restored = values[X_STATE]?.let { MenuMemory.decode(it) } ?: return
            update { MenuMemory.merge(it, restored) }
        }
    }

    private companion object {
        const val FILE = "menu_memory"
        const val KEY = "state_v1"
        const val X_STATE = "${BackupExtras.PREFIX}menus.state"
    }
}
