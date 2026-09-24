package app.parley.telecom

import android.content.Context
import android.os.PowerManager

/**
 * Turns the screen off when the phone is held to the ear during an earpiece call. Can be switched off in
 * Settings › Calls (V6), for broken sensors or for listening with the phone in a pocket.
 */
class ProximityController(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val lock: PowerManager.WakeLock? =
        if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "parley:proximity").apply { setReferenceCounted(false) }
        } else {
            null
        }

    fun update(calls: List<CallUi>, audio: AudioUi, uiVisible: Boolean, enabled: Boolean = true) {
        if (!enabled) {
            release()
            return
        }
        val inCall = calls.any { it.state == CallState.ACTIVE || it.state == CallState.DIALING || it.state == CallState.CONNECTING }
        val earpiece = audio.current == null || audio.current.type == RouteType.EARPIECE
        if (inCall && earpiece && uiVisible) acquire() else release()
    }

    private fun acquire() {
        lock?.let { if (!it.isHeld) it.acquire(4 * 60 * 60 * 1000L) }
    }

    fun release() {
        lock?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }
}
