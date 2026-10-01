package app.parley.telecom

import app.parley.common.PhoneIdentity
import app.parley.common.calls.HelperJoin
import app.parley.common.calls.HelperStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** I5: the helper being brought into the call [fromId] ("Calling Sam to join…", then "Merge now"). */
data class HelperJoinUi(val fromId: String, val name: String, val number: String, val seen: Boolean = false)

/** Where a helper's call is, worked out from the live calls (pure rules in [HelperJoin]). */
data class HelperProgress(val join: HelperJoinUi, val stage: HelperStage, val call: CallUi?)

/**
 * "Add my helper" (I5): Add call to a trusted person through Telecom (which holds the call that goes on), then Merge
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

    /** Stop bringing them in: ends the helper's call while it's still ringing, and forgets it. */
    fun cancel(progress: HelperProgress?) {
        progress?.call?.takeIf { progress.stage == HelperStage.CALLING }?.let { CallManager.hangup(it.id) }
        _join.value = null
    }

    fun dismiss() {
        _join.value = null
    }

    /** Where [j]'s call is in [calls] (the screen's live list). Pure: [markSeen] records that their call showed up. */
    fun progress(j: HelperJoinUi, calls: List<CallUi>): HelperProgress {
        val own = calls.firstOrNull { it.isLive && !it.incoming && it.id != j.fromId && same(it.number, j.number) }
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
