package app.parley.telecom

import android.content.Context
import android.os.PowerManager
import app.parley.common.calls.ScreenAtEar

/**
 * Turns the screen off when the phone is held to the ear during an earpiece call. Settings › Calls › During calls
 * switches it off (broken sensors, listening with the phone in a pocket) or keeps it for answered calls only, so the
 * screen stays on while an outgoing call is dialled ([ScreenAtEar]).
 */
/** Settings › Calls › "Turn the screen off at your ear", read from memory (real calls and rescue calls alike). */
internal fun screenAtEarMode(): ScreenAtEar.Mode = runCatching {
    val d = TelecomGraph.dependencies
    ScreenAtEar.mode(d.proximityEnabled(), d.proximityOnceAnswered())
}.getOrDefault(ScreenAtEar.Mode.DURING_CALLS)

class ProximityController(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val lock: PowerManager.WakeLock? =
        if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "parley:proximity").apply { setReferenceCounted(false) }
        } else {
            null
        }

    fun update(calls: List<CallUi>, audio: AudioUi, uiVisible: Boolean, mode: ScreenAtEar.Mode = ScreenAtEar.Mode.DURING_CALLS) {
        val earpiece = audio.current == null || audio.current.type == RouteType.EARPIECE
        val holds = ScreenAtEar.holds(
            mode,
            connected = calls.any { it.state == CallState.ACTIVE },
            dialling = calls.any { it.state == CallState.DIALING || it.state == CallState.CONNECTING },
            earpiece = earpiece,
            screenInFront = uiVisible,
        )
        if (holds) acquire() else release()
    }

    private fun acquire() {
        lock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) }
    }

    fun release() {
        lock?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }
}
