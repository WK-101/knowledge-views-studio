package app.parley.telecom

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import app.parley.common.calls.DndState
import app.parley.common.calls.RingRamp
import app.parley.common.calls.RingStyle
import app.parley.common.calls.RingerMode
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * "Increasing" and "Vibrate first, then ring" ([RingStyle]): the ring volume starts at [RingRamp.START_VOLUME] and
 * steps up to the user's own ring volume ([RingRamp.steps]). Like "Ring loud" ([RingBoost]), the user's volume is
 * written to disk before the first change, so a crash or a killed process never leaves the ringer quiet: the next call
 * or the next app start puts it back ([restore]). The ramp ends, and the volume goes back, however the ringing ends:
 * answered, declined, silenced (the Silence button, a volume key, flip, a rule) or the call gone.
 *
 * It never fights the user: a volume moved during the ramp (the volume panel, another app) stops it there, and theirs
 * stays ([RingRamp.restore]). The volume keys themselves silence the ringing, which ends the ramp too.
 *
 * Runs on [RingBoost]'s thread, which keeps a ramp, a boost and their restores in order.
 */
object RingVolumeRamp {
    private const val PREFS = "parley_ring_ramp"
    private const val KEY_SAVED = "saved_ring_volume"
    private const val KEY_SET = "set_ring_volume"
    private const val KEY_PREVIOUS = "previous_ring_volume"

    /** The steps waiting to run (on [RingBoost.io] only). */
    private val pending = ArrayList<ScheduledFuture<*>>()

    /**
     * Starts the ramp for a call that just started ringing, when [RingRamp.applies]: [delayMs] holds the first step
     * back ("Vibrate first": the vibration alone, at the lowest volume). Off the main thread.
     */
    fun startAsync(context: Context, style: RingStyle, delayMs: Long = 0) {
        val app = context.applicationContext
        RingBoost.io.execute {
            cancelSteps()
            runCatching {
                val target = begin(app, style) ?: return@runCatching
                RingRamp.steps(target, RingRamp.DURATION_MS, delayMs).forEach { step ->
                    pending += RingBoost.io.schedule({ runCatching { advance(app, step.volume) } }, step.atMs, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /** The ringing ended: the remaining steps go and the user's volume comes back. */
    fun stopAsync(context: Context) {
        val app = context.applicationContext
        RingBoost.io.execute {
            cancelSteps()
            runCatching { restore(app) }
        }
    }

    internal fun cancelSteps() {
        pending.forEach { it.cancel(false) }
        pending.clear()
    }

    /**
     * Saves the user's ring volume and drops it to [RingRamp.START_VOLUME]; the volume to grow to, or null when this
     * call doesn't ramp. A ramp left over by a crash is put back first, so the saved volume is always the user's.
     */
    internal fun begin(context: Context, style: RingStyle): Int? {
        restore(context)
        if (RingBoost.isBoosted(context)) return null
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val nm = context.getSystemService(NotificationManager::class.java)
        val ringer = if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) RingerMode.NORMAL else RingerMode.UNKNOWN
        val dnd = if (nm == null || nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) DndState.OFF else DndState.PRIORITY
        val volume = am.getStreamVolume(AudioManager.STREAM_RING)
        if (!RingRamp.applies(style, ringer, dnd, otherCall = false, ringLoud = false, systemRamps = systemRamps(context), volume = volume)) return null
        val prefs = prefs(context)
        // On disk before the first change: a crash from here on still finds the user's volume.
        prefs.edit().putInt(KEY_SAVED, volume).putInt(KEY_SET, RingRamp.START_VOLUME).remove(KEY_PREVIOUS).commit()
        return try {
            am.setStreamVolume(AudioManager.STREAM_RING, RingRamp.START_VOLUME, 0)
            volume
        } catch (_: SecurityException) {
            prefs.edit().remove(KEY_SAVED).remove(KEY_SET).remove(KEY_PREVIOUS).commit()
            null
        }
    }

    /**
     * One step up to [volume]; false when the ramp is over: stopped, finished, or the user moved the volume (theirs
     * stays and the saved one is dropped). While the ring can't be heard (vibrate, silent, Do Not Disturb muting it) the
     * step waits: a muted stream reads 0, which is not the user's choice. Each step is noted on disk (commit) before
     * the volume changes, with the one before it, so a process killed in between still finds the ramp's own volume and
     * puts the user's back ([RingRamp.restore]).
     */
    internal fun advance(context: Context, volume: Int): Boolean {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_SAVED)) return false
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val saved = prefs.getInt(KEY_SAVED, -1)
        val lastSet = prefs.getInt(KEY_SET, RingRamp.START_VOLUME)
        val previous = if (prefs.contains(KEY_PREVIOUS)) prefs.getInt(KEY_PREVIOUS, -1) else null
        if (!audible(context, am)) return false
        if (RingRamp.userTookOver(lastSet, am.getStreamVolume(AudioManager.STREAM_RING), previous)) {
            prefs.edit().remove(KEY_SAVED).remove(KEY_SET).remove(KEY_PREVIOUS).commit()
            cancelSteps()
            return false
        }
        val next = volume.coerceAtMost(saved)
        prefs.edit().putInt(KEY_SET, next).putInt(KEY_PREVIOUS, lastSet).commit()
        try {
            am.setStreamVolume(AudioManager.STREAM_RING, next, 0)
        } catch (_: SecurityException) {
            return false
        }
        if (next >= saved && am.getStreamVolume(AudioManager.STREAM_RING) == saved) {
            // Back at the user's own volume: nothing is left to restore.
            prefs.edit().remove(KEY_SAVED).remove(KEY_SET).remove(KEY_PREVIOUS).commit()
            return false
        }
        return next < saved
    }

    /**
     * Puts the user's volume back ([RingRamp.restore]): kept for later while the ring can't be heard (vibrate or silent,
     * or Do Not Disturb muting the ring stream: the volume reads as muted then and a change is ignored), dropped when
     * the user changed the volume since. The saved value goes only once the volume reads back as the user's.
     */
    fun restore(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_SAVED)) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val saved = prefs.getInt(KEY_SAVED, -1)
        val lastSet = if (prefs.contains(KEY_SET)) prefs.getInt(KEY_SET, -1) else null
        val previous = if (prefs.contains(KEY_PREVIOUS)) prefs.getInt(KEY_PREVIOUS, -1) else null
        val audible = audible(context, am)
        val current = if (audible) am.getStreamVolume(AudioManager.STREAM_RING) else 0
        when (val r = RingRamp.restore(saved, lastSet, current, audible, previous)) {
            RingRamp.Restore.Later -> return
            is RingRamp.Restore.To -> try {
                am.setStreamVolume(AudioManager.STREAM_RING, r.volume, 0)
                // Android can ignore the change without saying so (Do Not Disturb came on just now): try again later.
                if (am.getStreamVolume(AudioManager.STREAM_RING) != r.volume) return
            } catch (_: SecurityException) {
                return // Do Not Disturb refused the change: keep the saved value and try again later.
            }
            RingRamp.Restore.Nothing, RingRamp.Restore.KeepUsers -> Unit
        }
        prefs.edit().remove(KEY_SAVED).remove(KEY_SET).remove(KEY_PREVIOUS).commit()
    }

    /** The ring can be heard and its volume set: normal ringer mode, and Do Not Disturb not muting the ring stream. */
    private fun audible(context: Context, am: AudioManager): Boolean {
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (runCatching { am.isStreamMute(AudioManager.STREAM_RING) }.getOrDefault(false)) return false
        val filter = runCatching { context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter }.getOrNull()
        return filter == null || filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** A ramp is under way (or left over). */
    fun isRamping(context: Context): Boolean = prefs(context).contains(KEY_SAVED)

    /**
     * Android's own "Vibrate first, then ring gradually" (`apply_ramping_ringer`: a system setting from Android 13, a
     * global one before): it ramps Telecom's ringing already, and two ramps would start inaudibly low.
     */
    internal fun systemRamps(context: Context): Boolean {
        val cr = context.contentResolver
        val system = runCatching { Settings.System.getInt(cr, APPLY_RAMPING_RINGER, 0) != 0 }.getOrDefault(false)
        val global = runCatching { Settings.Global.getInt(cr, APPLY_RAMPING_RINGER, 0) != 0 }.getOrDefault(false)
        return system || global
    }

    private const val APPLY_RAMPING_RINGER = "apply_ramping_ringer"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
