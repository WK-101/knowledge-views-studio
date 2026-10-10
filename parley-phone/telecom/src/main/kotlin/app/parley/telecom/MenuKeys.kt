package app.parley.telecom

import android.telecom.Call
import app.parley.common.calls.KeyPressTracker
import app.parley.common.catching
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPress
import app.parley.common.calls.MenuStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The keypad during a call: DTMF tones (held as long as the key is), and menu memory: the digits sent in a
 * connected outgoing call, kept when it ends, and "Last time: 2 › 1 › 4" sent again on request.
 */
internal class MenuKeys(private val scope: CoroutineScope, private val live: LiveCalls, private val deps: () -> TelecomDependencies) {
    private var dtmfToken = 0L

    /** One short DTMF tone (hardware keys, accessibility): [KeyPressTracker.MIN_TONE_MS] long. */
    fun play(id: String, c: Char) {
        val token = start(id, c) ?: return
        stop(id, token, KeyPressTracker.MIN_TONE_MS)
    }

    /**
     * Starts the DTMF tone for [c] and keeps it playing until [stop] (held while the key is pressed, for phone menus
     * that want a long tone). A tone still playing from another key is stopped first (key roll-over). Returns a token
     * for [stop], or null when the call is gone.
     */
    fun start(id: String, c: Char): Long? {
        val call = live.find(id) ?: return null
        val s = live.session(id)
        if (s.dtmfToken != null) runCatching { call.stopDtmfTone() }
        runCatching { call.playDtmfTone(c) }
        val token = ++dtmfToken
        s.dtmfToken = token
        noteKey(call, s, c)
        return token
    }

    /** Stops the tone started with [token] after [afterMs], unless another key started a tone since. */
    fun stop(id: String, token: Long, afterMs: Long = 0) {
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            val s = live.sessionOrNull(id) ?: return@launch
            if (s.dtmfToken != token) return@launch
            s.dtmfToken = null
            live.find(id)?.let { catching { it.stopDtmfTone() } }
        }
    }

    /** A digit sent in a connected outgoing call, timed from the connect (kept for menu memory when the call ends). */
    private fun noteKey(call: Call, s: CallSession, c: Char) {
        val d = call.details
        if (call.parent != null || d.callDirection != Call.Details.DIRECTION_OUTGOING || d.connectTimeMillis <= 0) return
        if (s.menuPresses.size >= MENU_PRESS_LIMIT) return
        s.menuPresses += MenuPress(c, System.currentTimeMillis() - d.connectTimeMillis)
    }

    /** Hands the digits of a connected outgoing call to menu memory. Never for emergency calls or conferences. */
    fun record(ended: CallUi, s: CallSession) {
        if (s.menuPresses.isEmpty() || ended.connectTimeMillis <= 0) return
        if (ended.incoming || ended.isConference || ended.hidden) return
        val number = ended.number?.takeIf { MenuMemory.remembers(it, ended.isEmergency) } ?: return
        val presses = s.menuPresses.toList()
        runCatching { deps().onMenuKeys(number, ended.accountId, presses) }
    }

    private val _replay = MutableStateFlow<CallManager.MenuReplay?>(null)
    val replay: StateFlow<CallManager.MenuReplay?> = _replay.asStateFlow()
    private var replayJob: Job? = null
    private var replayToken = 0L

    /**
     * "Last time: 2 › 1 › 4": sends [steps] again in the call [id], each after its recorded pause
     * ([MenuMemory.replayDelays]). It stops when the call is no longer active, on [stopReplay], or when the user
     * presses a key. Never in an emergency call.
     */
    fun replay(id: String, steps: List<MenuStep>) {
        val call = live.find(id) ?: return
        if (steps.isEmpty() || !replayMayGoOn(call) || call.details.connectTimeMillis <= 0) return
        if (live.isEmergencyCall(call, call.details.handle?.schemeSpecificPart)) return
        stopReplay()
        val delays = MenuMemory.replayDelays(steps, (System.currentTimeMillis() - call.details.connectTimeMillis).coerceAtLeast(0))
        val token = ++replayToken
        _replay.value = CallManager.MenuReplay(id, steps, 0, token)
        replayJob = scope.launch {
            try {
                steps.forEachIndexed { i, step ->
                    delay(delays[i])
                    val c = live.find(id) ?: return@launch
                    if (!replayMayGoOn(c)) return@launch
                    val tone = start(id, step.tone) ?: return@launch
                    stop(id, tone, MenuMemory.REPLAY_TONE_MS)
                    _replay.value = CallManager.MenuReplay(id, steps, i + 1, token)
                }
            } finally {
                if (_replay.value?.token == token) _replay.value = null
            }
        }
    }

    /**
     * Whether a replay may send its next key into [c]: still active on its own, not merged into a conference (the
     * keys would go to everyone in it) and no other call became the active one.
     */
    private fun replayMayGoOn(c: Call): Boolean = MenuMemory.replayGoesOn(
        active = mapState(c.stateCompat()) == CallState.ACTIVE,
        inConference = c.parent != null || c.children.isNotEmpty() || c.details.hasProperty(Call.Details.PROPERTY_CONFERENCE),
        otherActive = live.calls.any { it !== c && it.parent == null && mapState(it.stateCompat()) == CallState.ACTIVE },
    )

    /** A call changed (merged, held, another answered): the replay stops at once if it may not go on. */
    fun checkReplay() {
        val id = _replay.value?.callId ?: return
        val c = live.find(id)
        if (c == null || !replayMayGoOn(c)) stopReplay()
    }

    /** Stops a replay (Stop, a key pressed by hand, the call ended). */
    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        _replay.value = null
    }

    private companion object {
        /** Digits kept per call for menu memory (it keeps far fewer; this only bounds the memory used). */
        const val MENU_PRESS_LIMIT = 64
    }
}
