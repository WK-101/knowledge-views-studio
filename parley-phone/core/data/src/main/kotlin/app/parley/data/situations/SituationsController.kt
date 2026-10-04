package app.parley.data.situations

import android.content.Context
import android.util.Log
import app.parley.common.PolicyClock
import app.parley.common.situations.Behaviour
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationCause
import app.parley.common.situations.SituationKind
import app.parley.common.situations.SituationSignals
import app.parley.common.situations.SituationState
import app.parley.common.situations.Situations
import app.parley.data.R
import app.parley.data.SettingsRepository
import app.parley.data.calls.CallExtrasRepository
import app.parley.data.calls.DriveProfileRepository
import app.parley.data.calls.RoamingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Situations: the list (what each sets and when it switches on, in backups) and the one on now with what to put back
 * (this phone only). Switching writes the bundle into the stores that own each behaviour, so the call path, the call
 * screen and the settings pages read it as usual; the snapshot is written before anything changes, so a process death
 * or a reboot still knows what to restore. [reconcile] looks at the triggers: the app calls it at start, before each
 * incoming call is screened, from the Quick Settings tile, when a Bluetooth audio device comes or goes, and at the next
 * window edge ([nextChange]).
 */
class SituationsController(
    context: Context,
    private val settings: SettingsRepository,
    private val drive: () -> DriveProfileRepository,
    private val extras: () -> CallExtrasRepository,
    private val roaming: () -> RoamingRepository,
    /** What the triggers see now (connected audio devices, car mode, the drive profile's cars); null: only the time. */
    private val liveSignals: () -> SituationSignals? = { null },
) {
    private val app = context.applicationContext
    private val listPrefs = app.getSharedPreferences("parley_situations", Context.MODE_PRIVATE)
    private val statePrefs = app.getSharedPreferences("parley_situation_state", Context.MODE_PRIVATE)

    /** The built-in Situations as they come, with their suggested replies in the app's words. */
    val defaults: List<Situation> = Situations.builtIns { kind ->
        when (kind) {
            SituationKind.DRIVING -> app.getString(R.string.data_situation_reply_driving)
            SituationKind.MEETING -> app.getString(R.string.data_situation_reply_meeting)
            SituationKind.TRAVELLING -> app.getString(R.string.data_situation_reply_travelling)
            else -> null
        }
    }

    private val _list = MutableStateFlow(Situations.normalise(Situations.decodeList(listPrefs.getString(K_LIST, null)) ?: defaults, defaults))
    val list: StateFlow<List<Situation>> = _list.asStateFlow()

    private val _state = MutableStateFlow(SituationState.decode(statePrefs.getString(K_STATE, null)))
    val state: StateFlow<SituationState> = _state.asStateFlow()

    /** Told after the list or the Situation on now changed (the tile, the next window's job). */
    @Volatile
    var onChange: (() -> Unit)? = null

    private val mutex = Mutex()

    /** The Situation on now, or null. */
    val active: Situation? get() = _state.value.activeId?.let { id -> _list.value.firstOrNull { it.id == id } }

    /** The SIM for calls without one of their own while a Situation with a SIM is on (after a number's and a label's). */
    fun activeSim(): String? = Situations.activeSim(_state.value, _list.value)

    /** Whether the triggers could change anything: one is on, or one can switch itself on. Memory only. */
    fun watching(): Boolean = _state.value.activeId != null || _list.value.any { it.automatic }

    /** The next time a window starts or ends, or null. */
    fun nextChange(now: Long = System.currentTimeMillis()): Long? = Situations.nextChange(_list.value, now, java.time.ZoneId.systemDefault())

    /** Switches [id] on by hand (it stays on until switched off by hand); the one on before goes off first. */
    suspend fun turnOn(id: String): Boolean = changed {
        val s = _list.value.firstOrNull { it.id == id } ?: return@changed false
        switch(Situations.turnOn(_state.value, read(), s, SituationCause.MANUAL, System.currentTimeMillis()))
        true
    }

    /** Switches off the Situation on now and puts back what was set before it. */
    suspend fun turnOff(): Boolean = changed {
        if (_state.value.activeId == null) return@changed false
        switch(Situations.turnOff(_state.value, read(), byHand = true))
        true
    }

    /** Looks at the triggers and switches as they say. Safe to call often: nothing is written when nothing changes. */
    suspend fun reconcile(): Boolean {
        if (!watching() && _state.value.held.isEmpty()) return false
        return changed {
            val st = _state.value
            val sig = runCatching { liveSignals() }.getOrNull() ?: SituationSignals(PolicyClock.of(System.currentTimeMillis()))
            val plan = Situations.plan(st, _list.value, sig)
            val now = System.currentTimeMillis()
            when (val step = plan.step) {
                Situations.Step.Keep -> {
                    if (plan.held == st.held) return@changed false
                    saveState(st.copy(held = plan.held))
                }
                Situations.Step.Off -> switch(Situations.turnOff(st.copy(held = plan.held), read(), byHand = false))
                is Situations.Step.On -> switch(Situations.turnOn(st.copy(held = plan.held), read(), step.situation, step.cause, now))
            }
            true
        }
    }

    /**
     * Saves [s] (new or changed). The Situation on now takes its new bundle at once; a Situation can't be added beyond
     * [Situations.MAX].
     */
    suspend fun save(s: Situation): Boolean = changed { put(s) }

    /** Changes the Situation [id] with [f], applied to it as it is now (changes made quickly one after another all count). */
    suspend fun edit(id: String, f: (Situation) -> Situation): Boolean = changed {
        val cur = _list.value.firstOrNull { it.id == id } ?: return@changed false
        put(f(cur))
    }

    private suspend fun put(s: Situation): Boolean {
        val list = _list.value
        val next = if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
        if (next.size > Situations.MAX) return false
        saveList(next)
        val st = _state.value
        if (st.activeId == s.id) switch(Situations.turnOn(st, read(), s, st.cause, st.since))
        return true
    }

    /** Deletes a Situation someone made (built-ins go back to how they came); one on now goes off first. */
    suspend fun delete(id: String): Boolean = changed {
        val st = _state.value
        if (st.activeId == id) switch(Situations.turnOff(st, read(), byHand = false))
        val fresh = defaults.firstOrNull { it.id == id }
        saveList(_list.value.mapNotNull { if (it.id != id) it else fresh })
        true
    }

    /** A new Situation's id. */
    fun newId(): String = Situations.newId(System.currentTimeMillis(), _list.value.map { it.id })

    /** A backup's Situations, merged with this phone's ([Situations.merge]). */
    suspend fun restore(backup: List<Situation>) {
        changed {
            saveList(Situations.merge(_list.value, backup, defaults))
            true
        }
    }

    /** The list for a backup. */
    fun forBackup(): List<Situation> = _list.value

    /**
     * Runs [block] alone and to its end: a switch is never cut in half by a cancelled caller (a screen left, the
     * time limit before screening).
     */
    private suspend fun changed(block: suspend () -> Boolean): Boolean {
        val did = withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { block() } }
        if (did) runCatching { onChange?.invoke() }.onFailure { Log.w(TAG, "Situation change listener failed", it) }
        return did
    }

    /**
     * Keeps [sw]'s state and writes its behaviours. Switching on keeps the snapshot first (a death in between restores
     * what was there); switching off writes what comes back first (a death in between finds it already back).
     */
    private suspend fun switch(sw: Situations.Switch) {
        if (sw.state.activeId != null) {
            saveState(sw.state)
            write(sw.behaviour)
        } else {
            write(sw.behaviour)
            saveState(sw.state)
        }
    }

    /** The behaviours as their stores hold them now. */
    private suspend fun read(): Behaviour {
        val s = settings.current()
        val d = drive().config.value
        val e = extras().config.value
        val r = roaming().config.value
        return Behaviour(
            offHours = s.screening.offHours,
            driveAnnounce = d.announce, driveAnswerFavourites = d.answerFavourites, driveSilenceUnknown = d.silenceUnknown,
            autoAnswerHeadset = e.autoAnswerHeadset, autoAnswerChosen = e.autoAnswerChosen, speaker = e.speakerDefault,
            quickReplies = s.quickReplies, busyReply = s.screening.busyReply, busyReplyText = s.screening.busyReplyText,
            assistedDialling = r.assistedDialling, localSimHint = r.localSimHint,
        )
    }

    private suspend fun write(b: Behaviour) {
        if (b == read()) return
        settings.update {
            it.copy(
                quickReplies = b.quickReplies.ifEmpty { it.quickReplies },
                screening = it.screening.copy(offHours = b.offHours, busyReply = b.busyReply, busyReplyText = b.busyReplyText),
            )
        }
        drive().update { it.copy(announce = b.driveAnnounce, answerFavourites = b.driveAnswerFavourites, silenceUnknown = b.driveSilenceUnknown) }
        extras().update { it.copy(autoAnswerHeadset = b.autoAnswerHeadset, autoAnswerChosen = b.autoAnswerChosen, speakerDefault = b.speaker) }
        roaming().update { it.copy(assistedDialling = b.assistedDialling, localSimHint = b.localSimHint) }
    }

    /** Written at once (not later): the snapshot must be on disk before the behaviours change. */
    private fun saveState(s: SituationState) {
        _state.value = s
        statePrefs.edit().putString(K_STATE, s.encode()).commit()
    }

    private fun saveList(list: List<Situation>) {
        val next = Situations.normalise(list, defaults)
        _list.value = next
        listPrefs.edit().putString(K_LIST, Situations.encodeList(next)).commit()
    }

    private companion object {
        const val TAG = "Situations"
        const val K_LIST = "list"
        const val K_STATE = "state"
    }
}
