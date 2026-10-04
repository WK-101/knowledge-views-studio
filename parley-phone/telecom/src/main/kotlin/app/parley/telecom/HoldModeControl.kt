package app.parley.telecom

import android.os.SystemClock
import app.parley.common.calls.HoldMode
import app.parley.common.calltime.CallHaptic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hold mode (I10), "I'm on hold": the speaker comes on (so the phone can lie on the table), the screen shows a hold
 * timer, and the phone buzzes at [HoldMode.REMINDER_MINUTES]. Parley can't hear the call, so it never guesses when
 * someone is back.
 */
internal class HoldModeControl(private val scope: CoroutineScope, private val live: LiveCalls) {
    private val reminders = HashMap<String, Job>()

    fun start(id: String) {
        val call = live.find(id) ?: return
        if (mapState(call.stateCompat()) != CallState.ACTIVE || live.isEmergencyCall(call, call.details.handle?.schemeSpecificPart)) return
        val s = live.session(id)
        if (s.holdModeSince != 0L) return
        val since = SystemClock.elapsedRealtime()
        s.holdModeSince = since
        val a = live.audio
        if (a.current?.type != RouteType.SPEAKER) {
            s.routeBeforeHold = a.current
            a.routes.firstOrNull { it.type == RouteType.SPEAKER }?.let { live.requestRoute(it) }
        }
        stopReminders(id)
        reminders[id] = scope.launch {
            var at = HoldMode.nextReminderAt(since, SystemClock.elapsedRealtime())
            // Cancelled when hold mode ends or the call goes; the check covers a restart of hold mode meanwhile.
            while (at != null && live.sessionOrNull(id)?.holdModeSince == since) {
                delay((at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (live.sessionOrNull(id)?.holdModeSince == since) CallClock.remind(CallHaptic.WARN)
                at = HoldMode.nextReminderAt(since, SystemClock.elapsedRealtime())
            }
        }
        live.publish()
    }

    /** Leaves hold mode: the audio goes back where it was, unless the user moved it meanwhile. */
    fun stop(id: String) {
        val s = live.sessionOrNull(id) ?: return
        if (s.holdModeSince == 0L) return
        // Kept for the call's facts: a case file shows how long each call waited.
        s.holdModeTotalMs += (SystemClock.elapsedRealtime() - s.holdModeSince).coerceAtLeast(0)
        s.holdModeSince = 0
        stopReminders(id)
        val back = s.routeBeforeHold
        s.routeBeforeHold = null
        val a = live.audio
        if (back != null && a.current?.type == RouteType.SPEAKER) a.routes.firstOrNull { it.key == back.key }?.let { live.requestRoute(it) }
        live.publish()
    }

    fun stopReminders(id: String) {
        reminders.remove(id)?.cancel()
    }
}
