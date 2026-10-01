package app.parley.ui.home

import androidx.fragment.app.FragmentActivity
import app.parley.common.suspendRunCatching
import app.parley.data.DataContainer
import app.parley.security.AppLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Contacts tab's bulk "Move to private", run in the app's view-model scope. It used to run in the confirmation
 * dialog's own coroutine scope, which the dialog cancelled as it closed (the very first thing its Move button did),
 * so the move stopped before the first contact, or right after the vault's unlock, and nothing happened. Here it
 * outlives the dialog and the selection bar, and shows its progress and outcome through [state].
 */
class PrivateMoves(c: DataContainer, private val scope: CoroutineScope) {
    private val bulk = BulkContactActions(c)

    sealed interface State {
        data class Moving(val done: Int, val total: Int) : State

        /** Finished; shown as a dialog when some couldn't be moved. */
        data class Done(val result: BulkContactActions.MovedPrivate) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    val running: Boolean get() = _state.value is State.Moving

    /**
     * Moves the device contacts among [ids] ([names]: for the result). Asks for the vault's unlock when it's needed
     * (with [activity]), then goes on where it stopped; declined, the rest are listed as not moved. [onFinished] gets
     * the outcome; [onError] an unexpected failure (nothing more moves then).
     */
    fun start(
        activity: FragmentActivity?,
        ids: List<Long>,
        names: Map<Long, String>,
        onFinished: (BulkContactActions.MovedPrivate) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (running) return
        _state.value = State.Moving(0, ids.size)
        run(activity, ids, names, BulkContactActions.MovedPrivate(0, emptyList()), onFinished, onError)
    }

    private fun run(
        activity: FragmentActivity?,
        ids: List<Long>,
        names: Map<Long, String>,
        already: BulkContactActions.MovedPrivate,
        onFinished: (BulkContactActions.MovedPrivate) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        scope.launch {
            val outcome = try {
                suspendRunCatching { bulk.makePrivate(ids, names, already) { done, total -> _state.value = State.Moving(done, total) } }
            } catch (e: CancellationException) {
                _state.value = null
                throw e
            }
            outcome.onSuccess { finish(it, onFinished) }
            when (val e = outcome.exceptionOrNull()) {
                null -> Unit
                is BulkContactActions.BulkLocked -> {
                    val notMoved = { finish(e.soFar.copy(failed = e.soFar.failed + e.remaining.map { it to names[it].orEmpty() }), onFinished) }
                    if (activity == null) {
                        notMoved()
                    } else {
                        AppLock.authenticateForVault(activity) { ok ->
                            if (ok) run(activity, e.remaining, names, e.soFar, onFinished, onError) else notMoved()
                        }
                    }
                }
                else -> {
                    _state.value = null
                    onError(e as? Exception ?: IllegalStateException(e))
                }
            }
        }
    }

    private fun finish(r: BulkContactActions.MovedPrivate, onFinished: (BulkContactActions.MovedPrivate) -> Unit) {
        // All moved: a message says so and nothing waits; otherwise the outcome stays until it's read.
        _state.value = if (r.failed.isEmpty()) null else State.Done(r)
        onFinished(r)
    }

    /** The outcome dialog was closed. */
    fun dismiss() {
        if (_state.value is State.Done) _state.value = null
    }
}
