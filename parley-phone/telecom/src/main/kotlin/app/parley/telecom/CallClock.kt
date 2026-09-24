package app.parley.telecom

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import app.parley.common.calltime.CallHaptic
import app.parley.common.calltime.CallTimeBook
import app.parley.common.calltime.CallTimePlan
import app.parley.common.calltime.Countdown
import app.parley.common.calltime.CountdownEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Timing of one live call, for the in-call screen, the notification and the "Return to call" chip. */
data class CallTiming(
    val countdown: Countdown,
    /** "Limit for Ana", or null for reminders only. */
    val source: String?,
    /** The allowance was used up during this call. */
    val quotaUsed: Boolean,
) {
    fun remainingMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long? = countdown.remainingMs(nowElapsed)
    val canExtend: Boolean get() = countdown.canExtend
}

/**
 * Call time (T1–T5) and call haptics (A6). Runs only while Telecom has calls: nothing is scheduled between calls.
 *
 * - Every call gets its plan (reminders, limit, allowance) when it is added; the countdown starts from the call's
 *   connect time, on the `elapsedRealtime` clock, when it becomes active.
 * - A limit ends *its own* call with [android.telecom.Call.disconnect], never another ringing or held call and
 *   never through `TelecomManager.endCall`. Emergency calls are never timed.
 * - A partial wake lock, bounded by the latest end time, is held only while a limited call is live, so the
 *   limit fires on time with the screen off.
 */
object CallClock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val book = CallTimeBook()
    private val plans = HashMap<String, CallTimePlan>()
    private val requested = HashSet<String>()
    private val connected = HashSet<String>()
    private val quotaUsed = HashSet<String>()
    /** Calls a limit has ended: never timed again while they finish disconnecting. */
    private val endedByLimit = HashSet<String>()
    private var feedback: CallFeedback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var power: PowerManager? = null
    private var ticker: Job? = null
    private var appContext: Context? = null

    private val _timings = MutableStateFlow<Map<String, CallTiming>>(emptyMap())
    val timings: StateFlow<Map<String, CallTiming>> = _timings.asStateFlow()

    internal fun attach(context: Context) {
        appContext = context.applicationContext
        feedback = CallFeedback(context.applicationContext)
        power = context.applicationContext.getSystemService(PowerManager::class.java)
    }

    /**
     * Calls that are never timed: emergency calls, any call while the emergency window runs (the operator's
     * call-back, either direction), and numbers the user listed as starting that window (B23).
     */
    private fun exempt(number: String?, emergency: Boolean): Boolean {
        if (emergency) return true
        val ctx = appContext
        if (ctx != null && runCatching { ScreeningGuard.inEmergencyWindow(ctx) }.getOrDefault(false)) return true
        return !number.isNullOrBlank() && runCatching { TelecomGraph.dependencies.startsEmergencyWindow(number) }.getOrDefault(false)
    }

    internal fun detach() {
        ticker?.cancel()
        ticker = null
        book.retain(emptySet())
        plans.clear()
        requested.clear()
        connected.clear()
        quotaUsed.clear()
        endedByLimit.clear()
        releaseWakeLock()
        _timings.value = emptyMap()
        feedback = null
    }

    /**
     * Loads the plan for a call once it is connected (once per call): by then Telecom has settled on the SIM,
     * and the countdown still starts from the connect time.
     */
    private fun requestPlan(id: String, number: String?, accountId: String?, incoming: Boolean, emergency: Boolean) {
        if (!requested.add(id)) return
        if (exempt(number, emergency)) {
            plans[id] = CallTimePlan.NONE
            return
        }
        scope.launch {
            val plan = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
                runCatching { TelecomGraph.dependencies.callTimePlan(number, accountId, incoming) }.getOrNull()
            } ?: CallTimePlan.NONE
            if (id !in requested) return@launch // the call is already gone
            plans[id] = plan
            onCallsChanged(CallManager.state.value)
            CallManager.notifyObservers()
        }
    }

    /** Called by [CallManager] on every change. Cheap: bookkeeping only. */
    internal fun onCallsChanged(top: List<CallUi>) {
        val all = top + top.flatMap { it.children }
        val now = SystemClock.elapsedRealtime()
        val liveIds = HashSet<String>()
        for (c in all) {
            if (!c.isLive) {
                if (connected.remove(c.id)) feedback?.haptic(CallHaptic.DISCONNECT)
                continue
            }
            liveIds += c.id
            if (c.state == CallState.ACTIVE && connected.add(c.id)) feedback?.haptic(CallHaptic.CONNECT)
            val running = c.state == CallState.ACTIVE || c.state == CallState.HOLDING
            if (running) requestPlan(c.id, c.number, c.accountId, c.incoming, c.isEmergency)
            val plan = plans[c.id]
            if (running && !c.isEmergency && c.connectTimeMillis > 0 && book[c.id] == null && plan != null && !plan.isEmpty && c.id !in endedByLimit) {
                book.track(c.id, Countdown.start(now, System.currentTimeMillis() - c.connectTimeMillis, plan))
            }
        }
        // Calls that left Telecom without a DISCONNECTED update still get their haptic.
        connected.filter { it !in liveIds }.forEach { connected.remove(it); feedback?.haptic(CallHaptic.DISCONNECT) }
        val gone = requested.filter { it !in liveIds && all.none { c -> c.id == it } }
        gone.forEach { requested.remove(it); plans.remove(it); quotaUsed.remove(it); endedByLimit.remove(it) }
        book.retain(liveIds)
        reschedule()
    }

    // ---- Wrap-up controls (T2) ----

    fun extend(id: String, minutes: Int) = change(id) { it.extend(minutes * 60_000L) }

    fun keepGoing(id: String) = change(id) { it.keepGoing() }

    /** "End in 1 min": works on calls without a limit too, never on emergency calls. */
    fun endIn(id: String, minutes: Int) {
        val call = find(id) ?: return
        if (exempt(call.number, call.isEmergency) || !call.isLive || id in endedByLimit) return
        val now = SystemClock.elapsedRealtime()
        if (book[id] == null) {
            val connectedFor = if (call.connectTimeMillis > 0) System.currentTimeMillis() - call.connectTimeMillis else 0L
            book.track(id, Countdown.start(now, connectedFor, plans[id] ?: CallTimePlan.NONE))
        }
        change(id) { it.endIn(now, minutes * 60_000L) }
    }

    private fun change(id: String, f: (Countdown) -> Countdown) {
        book.update(id, f) ?: return
        reschedule()
        CallManager.notifyObservers()
    }

    private fun find(id: String): CallUi? = CallManager.state.value.let { top -> (top + top.flatMap { it.children }).firstOrNull { it.id == id } }

    // ---- Scheduling ----

    private fun reschedule() {
        ticker?.cancel()
        ticker = null
        publish()
        if (book.isEmpty()) {
            releaseWakeLock()
            return
        }
        updateWakeLock()
        ticker = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val events = book.step(now)
                events.forEach { (id, e) -> handle(id, e) }
                if (events.isNotEmpty()) {
                    publish()
                    updateWakeLock()
                    CallManager.notifyObservers()
                }
                if (book.isEmpty()) break
                val next = book.nextDueAt() ?: (now + MAX_TICK_MS)
                // Re-checked at least every few seconds: the elapsed clock is the truth, not the delay.
                delay((next - SystemClock.elapsedRealtime()).coerceIn(MIN_TICK_MS, MAX_TICK_MS))
            }
            releaseWakeLock()
        }
    }

    private fun handle(id: String, event: CountdownEvent) {
        val plan = plans[id] ?: CallTimePlan.NONE
        val fb = feedback
        when (event) {
            CountdownEvent.REMINDER -> {
                if (plan.reminderBeep) fb?.beep(twice = false)
                if (plan.reminderVibrate) fb?.vibrate(CallHaptic.REMINDER)
            }
            CountdownEvent.WARN -> {
                fb?.beep(twice = true)
                fb?.haptic(CallHaptic.WARN)
            }
            CountdownEvent.QUOTA_USED -> {
                quotaUsed += id
                fb?.beep(twice = true)
                fb?.vibrate(CallHaptic.WARN)
            }
            CountdownEvent.END -> {
                // The emergency window may have started during this call (an emergency call made meanwhile).
                val call = find(id)
                if (call != null && exempt(call.number, call.isEmergency)) return
                endedByLimit += id
                CallManager.endForLimit(id)
            }
        }
    }

    private fun publish() {
        _timings.value = book.all.mapValues { (id, cd) -> CallTiming(cd, plans[id]?.source, id in quotaUsed) }
    }

    private fun updateWakeLock() {
        val end = book.latestEnd()
        if (end == null) {
            releaseWakeLock()
            return
        }
        val pm = power ?: return
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "parley:call-limit").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        // Re-acquiring with a new timeout replaces the old one (not reference counted).
        lock.acquire(Countdown.wakeLockTimeoutMs(SystemClock.elapsedRealtime(), end))
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Swap and merge are user actions; their haptic confirms the action went through. */
    internal fun haptic(h: CallHaptic) {
        feedback?.haptic(h)
    }

    private const val PLAN_TIMEOUT_MS = 3000L
    private const val MIN_TICK_MS = 50L
    private const val MAX_TICK_MS = 10_000L
}
