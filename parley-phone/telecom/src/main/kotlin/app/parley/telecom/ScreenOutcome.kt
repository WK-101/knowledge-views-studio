package app.parley.telecom

import android.content.Context
import android.media.AudioManager
import app.parley.common.Decision

/**
 * What screening decided for one call, as the call path needs it: the decision, a one-line verdict for the
 * caller card ("Reported by FTC list"), and how Parley's own ringer should ring (B2, B24).
 */
data class ScreenOutcome(
    val decision: Decision,
    val verdict: String? = null,
    /** The verdict is a warning (likely spam) rather than information. */
    val warn: Boolean = false,
    /** Ringtone URI Parley's ringer plays instead of the system's. */
    val ringtone: String? = null,
    /** Ring at full volume (favourites, repeat callers). */
    val ringLoud: Boolean = false,
)

/**
 * "Ring loud" (merged A8/B24): raises the ring volume to the maximum for one call and puts it back afterwards.
 * The previous volume is written to disk *before* boosting, so a crash or a killed process never leaves the
 * phone stuck at full volume: the next call, or the next app start, restores it.
 */
object RingBoost {
    private const val PREFS = "parley_ring_boost"
    private const val KEY = "saved_ring_volume"

    fun boost(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        // Never make a silenced phone ring, and leave Do Not Disturb alone.
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (nm != null && nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) return
        val current = am.getStreamVolume(AudioManager.STREAM_RING)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        if (current >= max) return
        prefs.edit().putInt(KEY, current).commit()
        try {
            am.setStreamVolume(AudioManager.STREAM_RING, max, 0)
        } catch (_: SecurityException) {
            prefs.edit().remove(KEY).commit()
        }
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return
        val saved = prefs.getInt(KEY, -1)
        val am = context.getSystemService(AudioManager::class.java)
        try {
            if (saved >= 0 && am != null && am.ringerMode == AudioManager.RINGER_MODE_NORMAL) am.setStreamVolume(AudioManager.STREAM_RING, saved, 0)
        } catch (_: SecurityException) {
        }
        prefs.edit().remove(KEY).apply()
    }

    fun isBoosted(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)
}
