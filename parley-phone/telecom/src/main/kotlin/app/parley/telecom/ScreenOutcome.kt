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
    /**
     * Allowed only because an allow rule limited to one SIM may apply and the SIM wasn't known (screening
     * service): the InCallService must screen again with the SIM.
     */
    val deferredToSim: Boolean = false,
    /** Where [ringtone] comes from, for "Why did my phone ring?" (V9). */
    val ringtoneSource: app.parley.common.calls.RingtoneSource? = null,
    /** The rule or label named by [ringtoneSource]. */
    val ringtoneName: String? = null,
)

/**
 * "Ring loud" (merged A8/B24): raises the ring volume to the maximum for one call and puts it back afterwards.
 * The previous volume is written to disk *before* boosting, so a crash or a killed process never leaves the
 * phone stuck at full volume: the next call, or the next app start, restores it.
 *
 * Both touch preferences and audio settings: call them off the main thread ([boostAsync], [restoreAsync]),
 * which also keeps a boost and its restore in order.
 */
object RingBoost {
    private const val PREFS = "parley_ring_boost"
    private const val KEY = "saved_ring_volume"
    private const val KEY_BOOSTED = "boosted_ring_volume"
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "parley-ring-boost").apply { isDaemon = true } }

    fun boostAsync(context: Context) {
        val app = context.applicationContext
        io.execute { runCatching { boost(app) } }
    }

    fun restoreAsync(context: Context) {
        val app = context.applicationContext
        io.execute { runCatching { restore(app) } }
    }

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
        prefs.edit().putInt(KEY, current).putInt(KEY_BOOSTED, max).commit()
        try {
            am.setStreamVolume(AudioManager.STREAM_RING, max, 0)
        } catch (_: SecurityException) {
            prefs.edit().remove(KEY).remove(KEY_BOOSTED).commit()
        }
    }

    /**
     * Puts the saved volume back. The saved value is kept until that has really happened: while the phone is on
     * vibrate or silent the ring stream reads as muted, so the restore waits for the next call or app start
     * instead of dropping it (which left the ringer stuck at full volume). If the user changed the volume since
     * the boost, their choice stays and the saved value is dropped.
     */
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return
        val saved = prefs.getInt(KEY, -1)
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (saved < 0) {
            prefs.edit().remove(KEY).remove(KEY_BOOSTED).apply()
            return
        }
        // Muted by the ringer mode: the volume can't be read or safely set now (setting it may unmute). Retry later.
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val boosted = prefs.getInt(KEY_BOOSTED, am.getStreamMaxVolume(AudioManager.STREAM_RING))
        try {
            if (am.getStreamVolume(AudioManager.STREAM_RING) == boosted) am.setStreamVolume(AudioManager.STREAM_RING, saved, 0)
        } catch (_: SecurityException) {
            return // Do Not Disturb refused the change: keep the saved value and try again later.
        }
        prefs.edit().remove(KEY).remove(KEY_BOOSTED).apply()
    }

    fun isBoosted(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)
}
