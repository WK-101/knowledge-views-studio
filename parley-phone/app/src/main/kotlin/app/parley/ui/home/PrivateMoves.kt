package app.parley.ui.home

import app.parley.common.suspendRunCatching
import app.parley.data.DataContainer
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

        /**
         * The vault locked partway: the screen showing now asks for its unlock and answers with [unlocked]. Nothing
         * here holds an activity, so a rotation or leaving the screen meanwhile never leaves the move stuck.
         */
        data class NeedsUnlock(val done: Int, val total: Int, val attempt: Int) : State

        /** Finished; shown as a dialog when some couldn't be moved. */
        data class Done(val result: BulkContactActions.MovedPrivate) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    val running: Boolean get() = _state.value is State.Moving || _state.value is State.NeedsUnlock

    /** What the move goes on with after the vault's unlock. */
    private class Paused(
        val locked: BulkContactActions.BulkLocked,
        val names: Map<Long, String>,
        val onFinished: (BulkContactActions.MovedPrivate) -> Unit,
        val onError: (Exception) -> Unit,
    )

    private var paused: Paused? = null
    private var attempts = 0

    /**
     * Moves the device contacts among [ids] ([names]: for the result). When the vault needs unlocking partway, the
     * state turns to [State.NeedsUnlock] and the move waits for [unlocked]; declined, the rest are listed as not moved.
     * [onFinished] gets the outcome; [onError] an unexpected failure (nothing more moves then). Neither should hold an
     * activity: they outlive the screen that started the move.
     */
    fun start(
        ids: List<Long>,
        names: Map<Long, String>,
        onFinished: (BulkContactActions.MovedPrivate) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (running) return
        _state.value = State.Moving(0, ids.size)
        run(ids, names, BulkContactActions.MovedPrivate(0, emptyList()), onFinished, onError)
    }

    private fun run(
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
                    paused = Paused(e, names, onFinished, onError)
                    val moving = _state.value as? State.Moving
                    _state.value = State.NeedsUnlock(moving?.done ?: e.soFar.moved, moving?.total ?: (e.soFar.moved + e.remaining.size), ++attempts)
                }
                else -> {
                    _state.value = null
                    onError(e as? Exception ?: IllegalStateException(e))
                }
            }
        }
    }

    /**
     * The screen's answer to [State.NeedsUnlock] number [attempt]: unlocked, the move goes on where it stopped; not,
     * the rest are listed as not moved. An answer to an older attempt (a prompt from before a rotation) is ignored.
     */
    fun unlocked(attempt: Int, ok: Boolean) {
        val s = _state.value as? State.NeedsUnlock ?: return
        if (s.attempt != attempt) return
        val p = paused ?: return
        paused = null
        val e = p.locked
        if (ok) {
            _state.value = State.Moving(s.done, s.total)
            run(e.remaining, p.names, e.soFar, p.onFinished, p.onError)
        } else {
            finish(e.soFar.copy(failed = e.soFar.failed + e.remaining.map { it to p.names[it].orEmpty() }), p.onFinished)
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
