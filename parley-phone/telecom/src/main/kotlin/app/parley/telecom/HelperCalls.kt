package app.parley.telecom

import app.parley.common.PhoneIdentity
import android.os.SystemClock
import app.parley.common.calls.HelperCancel
import app.parley.common.calls.HelperJoin
import app.parley.common.calls.HelperStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The helper being brought into the call [fromId] ("Calling Sam to join…", then "Merge now"). */
data class HelperJoinUi(val fromId: String, val name: String, val number: String, val seen: Boolean = false)

/** Where a helper's call is, worked out from the live calls (pure rules in [HelperJoin]). */
data class HelperProgress(val join: HelperJoinUi, val stage: HelperStage, val call: CallUi?)

/**
 * "Add my helper": Add call to a trusted person through Telecom (which holds the call that goes on), then Merge
 * with the existing conference support once they answer. Lives beside [CallManager] so it survives the call screen
 * going to picture-in-picture; forgotten when the calls end.
 */
object HelperCalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _join = MutableStateFlow<HelperJoinUi?>(null)
    val join: StateFlow<HelperJoinUi?> = _join.asStateFlow()

    private var watch: Job? = null

    /**
     * Calls [helper] to join the call [fromId] on the same SIM ([accountId]). [onProblem] hears why the call couldn't
     * be placed (then nothing is left in progress).
     */
    fun start(fromId: String, helper: HelperUi, accountId: String?, onProblem: (String) -> Unit) {
        _join.value = HelperJoinUi(fromId, helper.name, helper.number)
        watchCalls()
        scope.launch {
            val problem = runCatching { TelecomGraph.dependencies.redial(helper.number, accountId) }.getOrElse { it.message }
            if (problem != null) {
                _join.value = null
                onProblem(problem)
            }
        }
    }

    /**
     * Stop bringing them in: ends the helper's call while it's still ringing, and forgets it. Tapped before Telecom has
     * reported the call, the call is ended as soon as it shows up, so it never keeps dialling behind the card.
     */
    fun cancel(progress: HelperProgress?) {
        val p = progress
        if (p != null) {
            when (HelperJoin.onCancel(p.stage, p.call != null, p.join.seen)) {
                HelperCancel.HANG_UP -> p.call?.let { CallManager.hangup(it.id) }
                HelperCancel.WHEN_REPORTED -> endWhenReported(p.join)
                HelperCancel.FORGET -> Unit
            }
        }
        _join.value = null
    }

    private var cancelled: Job? = null

    /** Ends [j]'s outgoing call once it appears (at once if it's already there), for [HelperJoin.CANCEL_WAIT_MS]. */
    private fun endWhenReported(j: HelperJoinUi) {
        val at = SystemClock.elapsedRealtime()
        cancelled?.cancel()
        cancelled = scope.launch {
            val list = withTimeoutOrNull(HelperJoin.CANCEL_WAIT_MS) { CallManager.state.first { calls -> calls.any { isHelperCall(j, it) } } }
            if (list != null && HelperJoin.endsCancelled(SystemClock.elapsedRealtime() - at)) {
                list.filter { isHelperCall(j, it) }.forEach { CallManager.hangup(it.id) }
            }
        }
    }

    private fun isHelperCall(j: HelperJoinUi, c: CallUi): Boolean = c.isLive && !c.incoming && c.id != j.fromId && same(c.number, j.number)

    fun dismiss() {
        _join.value = null
    }

    /** Where [j]'s call is in [calls] (the screen's live list). Pure: [markSeen] records that their call showed up. */
    fun progress(j: HelperJoinUi, calls: List<CallUi>): HelperProgress {
        val own = calls.firstOrNull { isHelperCall(j, it) }
        val merged = calls.any { c -> c.isLive && c.isConference && c.children.any { same(it.number, j.number) } }
        val stage = HelperJoin.stage(own?.state?.live(), merged, j.seen || own != null || merged)
        return HelperProgress(j.copy(seen = j.seen || own != null || merged), stage, own)
    }

    /** Their call has shown up: once it's gone again, they didn't answer (or hung up). */
    fun markSeen() {
        _join.value?.takeIf { !it.seen }?.let { _join.value = it.copy(seen = true) }
    }

    private fun same(a: String?, b: String): Boolean = a != null && PhoneIdentity.same(a, b, null)

    /** Forgotten once no call is left, so a later call never shows an old helper. */
    private fun watchCalls() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            CallManager.state.collect { list -> if (list.none { it.isLive }) _join.value = null }
        }
    }
}
