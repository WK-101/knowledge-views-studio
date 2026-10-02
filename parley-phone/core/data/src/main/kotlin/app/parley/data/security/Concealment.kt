package app.parley.data.security

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.parley.common.security.Concealed
import app.parley.common.security.DuressMachine
import app.parley.common.security.DuressPolicy
import app.parley.common.security.DuressState
import app.parley.common.security.LockPhase
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * I21: what a duress unlock hides, for the whole process (the screens, the call path, background work). The state
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

    /** Reads the stored hiding once (a few bytes); every reader below calls it, the first one off the main thread. */
    fun ensureLoaded(): DuressState {
        if (loaded) return _state.value
        synchronized(this) {
            if (!loaded) {
                val stored = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
                val s = DuressMachine.restored(hiding = stored.startsWith("h"), vaultLocked = stored == "hv")
                apply(s)
                loaded = true
            }
        }
        return _state.value
    }

    /** Whether a duress unlock's hiding is on now. */
    val hiding: Boolean get() = ensureLoaded().hiding

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

    /** Tests: forget the state in memory, as a new process starts (the stored hiding is read again). */
    @VisibleForTesting
    fun forgetForTest() {
        synchronized(this) {
            loaded = false
            apply(DuressState())
        }
    }

    val phase: LockPhase get() = ensureLoaded().phase

    private fun apply(s: DuressState) {
        // Private contacts' details refuse to open even inside the phone's own 5-minute window (option, on by default).
        VaultCrypto.detailLocked = s.hiding && s.vaultLocked
        _state.value = s
    }

    private fun persist(s: DuressState) {
        val f = file ?: return
        try {
            if (!s.hiding) {
                f.delete()
            } else {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(if (s.vaultLocked) "hv" else "h")
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        } catch (ignored: Exception) {
            // In memory it still holds for this process; the next start would show everything, so say so in the log.
            Log.w(TAG, "Couldn't store the lock state: ${ignored.javaClass.simpleName}")
        }
    }
}
