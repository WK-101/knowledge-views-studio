package app.parley.data.security

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.parley.common.security.Concealed
import app.parley.common.security.DuressMachine
import app.parley.common.security.DuressPolicy
import app.parley.common.security.DuressState
import app.parley.common.security.LockPhase
import app.parley.common.storage.DurableFiles
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What a duress unlock hides, for the whole process (the screens, the call path, background work). The state
 * machine and the list are [DuressMachine] and [DuressPolicy]; this keeps the state and stores the one fact that must
 * outlive the process, "hiding until the real PIN", in a file of its own under no_backup (never in backups or settings
 * exports). A call that wakes Parley while someone still holds the phone finds it there.
 *
 * Stores read [hides] where they open what is listed in [Concealed]. Hiding never deletes or rewrites anything: a store
 * shows the item as absent and keeps what is stored when a write passes that absence back.
 */
object Concealment {
    private const val TAG = "Concealment"

    @Volatile private var file: File? = null

    @Volatile private var loaded = false

    private val _state = MutableStateFlow(DuressState())

    /** The current state; [DuressState.hiding] is false until [init] ran (no duress PIN was ever used then either). */
    val state: StateFlow<DuressState> = _state.asStateFlow()

    /** Called once by the container (no disk access here). */
    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.noBackupFilesDir, "app_lock_state")
    }

    /**
     * Reads the stored hiding once (a few bytes); every reader below calls it, the first one off the main thread. A
     * stored state that is there but can't be read counts as hiding, details locked (fail closed). A hiding that
     * couldn't be stored yet is written again here, at most every [RETRY_MS], so a hiding a full disk kept in memory reaches storage as soon as it can.
     */
    fun ensureLoaded(): DuressState {
        if (loaded) {
            retryPersist()
            return _state.value
        }
        synchronized(this) {
            if (!loaded) {
                val f = file
                val stored = if (f?.exists() == true) runCatching { f.readText() }.getOrElse { "hv" } else ""
                val s = DuressMachine.restored(hiding = stored.startsWith("h"), vaultLocked = stored == "hv")
                apply(s)
                loaded = true
            }
        }
        return _state.value
    }

    /** A hiding that couldn't be stored (kept in memory meanwhile), and when storing it was last tried. */
    @Volatile private var unsaved: DuressState? = null

    @Volatile private var triedAt = 0L

    /** Tests: the clock [retryPersist] waits on. */
    @VisibleForTesting
    var nanos: () -> Long = System::nanoTime

    private fun retryPersist() {
        val s = unsaved ?: return
        val now = nanos()
        if (now - triedAt < RETRY_MS * 1_000_000) return
        synchronized(this) {
            if (unsaved === s) persist(s)
        }
    }

    /** Whether a duress unlock's hiding is on now. */
    val hiding: Boolean get() = ensureLoaded().hiding

    /**
     * What was written while hiding (a note added, a safe word set): shown as written for as long as the hiding
     * lasts, so a note someone made you add doesn't vanish in front of them. Kept in memory; once the hiding ends
     * everything shows anyway. Tokens are the stores' own ("note:<key>", "callnote:<id>", …).
     */
    private val written = ConcurrentHashMap.newKeySet<String>()

    /** Values that would replace a hidden stored one (shown instead of it while hiding, never stored over it). */
    private val overlay = ConcurrentHashMap<String, String>()

    private val _revisions = MutableStateFlow(0)

    /** Goes up whenever [markWritten] or [setOverlay] changes what a store shows, so its flows emit again. */
    val revisions: StateFlow<Int> = _revisions.asStateFlow()

    fun markWritten(token: String) {
        if (hiding && written.add(token)) _revisions.value++
    }

    fun writtenWhileHiding(token: String): Boolean = token in written

    /** [token]'s value shown while hiding instead of the stored one; null removes it. */
    fun setOverlay(token: String, value: String?) {
        if (!hiding) return
        if (value == null) overlay.remove(token) else overlay[token] = value
        _revisions.value++
    }

    fun overlay(token: String): String? = overlay[token]

    fun hasOverlay(token: String): Boolean = overlay.containsKey(token)

    /** Whether [c] is hidden now ([discreet]: discreet mode as the stored settings say). */
    fun hides(c: Concealed, discreet: Boolean = false): Boolean = DuressPolicy.hidden(c, ensureLoaded(), discreet)

    /** A transition of [DuressMachine]: kept in memory, and the hiding written before anyone reads it again. */
    @Synchronized
    fun move(next: DuressState) {
        ensureLoaded()
        val before = _state.value
        if (next.hiding != before.hiding || next.vaultLocked != before.vaultLocked) persist(next)
        apply(next)
    }

    fun lock() = move(DuressMachine.locked(ensureLoaded()))

    /**
     * Tests: forget the state in memory, as a new process starts (the stored hiding is read again). [newProcess] also
     * forgets where it is stored: each Robolectric test has its own app directories, picked up by the next [init].
     */
    @VisibleForTesting
    fun forgetForTest(newProcess: Boolean = false) {
        synchronized(this) {
            loaded = false
            unsaved = null
            if (newProcess) file = null
            apply(DuressState())
        }
    }

    val phase: LockPhase get() = ensureLoaded().phase

    private fun apply(s: DuressState) {
        // Private contacts' details refuse to open even inside the phone's own 5-minute window (option, on by default).
        VaultCrypto.detailLocked = s.hiding && s.vaultLocked
        if (!s.hiding) {
            written.clear()
            overlay.clear()
        }
        _state.value = s
    }

    private fun persist(s: DuressState) {
        val f = file ?: return
        triedAt = nanos()
        try {
            if (!s.hiding) {
                f.delete()
            } else {
                DurableFiles.writeOrThrow(f, (if (s.vaultLocked) "hv" else "h").toByteArray())
            }
            unsaved = null
        } catch (ignored: Exception) {
            // It holds in memory for this process, and is written again as soon as it can be (see retryPersist).
            unsaved = s
            Log.w(TAG, "Couldn't store the lock state: ${ignored.javaClass.simpleName}")
        }
    }

    private const val RETRY_MS = 2_000L
}
