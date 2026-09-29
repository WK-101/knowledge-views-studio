package app.parley.telecom

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.CallExtrasConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Auto-answer on the call path ([AutoAnswer] decides): once a ringing call's caller is known and screening has let it
 * through, the call is armed with a deadline the call screen counts down to, with Cancel. At the deadline every check
 * runs again (still ringing, still alone, not cancelled or silenced) before it is answered. Main thread only.
 */
internal class AutoAnswerGate(private val scope: CoroutineScope, private val config: () -> CallExtrasConfig) {
    private val jobs = HashMap<String, Job>()

    /** What the gate asks the call path at arming time and again at the deadline. */
    interface Host {
        /** The facts for [session]'s call, or null when it no longer rings. */
        fun facts(session: CallSession): AutoAnswer.Facts?
        fun answer(session: CallSession)
        fun changed()
    }

    /** Arms [session] when auto-answer applies to it now; nothing when it already was, or the user cancelled it. */
    fun consider(session: CallSession, host: Host) {
        if (session.autoAnswerAt != 0L || session.autoAnswerCancelled || session.screening) return
        val cfg = runCatching { config() }.getOrDefault(CallExtrasConfig())
        if (!AutoAnswer.enabled(cfg)) return
        val facts = host.facts(session) ?: return
        AutoAnswer.reason(cfg, facts) ?: return
        val waitMs = AutoAnswer.normalise(cfg.autoAnswerSeconds) * 1000L
        session.autoAnswerAt = SystemClock.elapsedRealtime() + waitMs
        jobs[session.id]?.cancel()
        jobs[session.id] = scope.launch {
            delay(waitMs)
            jobs.remove(session.id)
            val now = host.facts(session)
            val still = now != null && session.autoAnswerAt != 0L && !session.autoAnswerCancelled && !session.silenced &&
                AutoAnswer.reason(runCatching { config() }.getOrDefault(cfg), now) != null
            session.autoAnswerAt = 0
            if (still) host.answer(session)
            host.changed()
        }
        host.changed()
    }

    /** The user pressed Cancel (or silenced, ignored or declined the call): it rings on as usual and isn't armed again. */
    fun cancel(session: CallSession) {
        session.autoAnswerCancelled = true
        if (session.autoAnswerAt == 0L) return
        session.autoAnswerAt = 0
        jobs.remove(session.id)?.cancel()
    }

    /** A second call arrived: no call is answered on its own while another exists. */
    fun cancelAll(sessions: Collection<CallSession>) = sessions.forEach { if (it.autoAnswerAt != 0L) cancel(it) }

    fun forget(id: String) {
        jobs.remove(id)?.cancel()
    }

    companion object {
        private val HEADSET_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }

        /**
         * A headset, earbuds or a Bluetooth device (a car kit) is connected. Read from the audio outputs, which needs no
         * permission (Bluetooth's own APIs would).
         */
        fun headsetConnected(context: Context): Boolean = runCatching {
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
        }.getOrDefault(false)
    }
}
