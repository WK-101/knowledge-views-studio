package app.parley.telecom

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.DndState
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingerMode

/**
 * Reads the ringer's state when a call starts ringing (V9): Do Not Disturb, ringer mode, ring volume and "vibrate for
 * calls". Memory reads of system services only, so it's safe on the call path. Every read fails soft.
 */
internal object RingSnapshot {
    fun capture(context: Context, startedAt: Long): RingFacts {
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val dnd = when (runCatching { nm?.currentInterruptionFilter }.getOrNull()) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> DndState.OFF
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndState.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndState.ALARMS
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndState.TOTAL_SILENCE
            else -> DndState.UNKNOWN
        }
        // In priority mode, whether calls (from anyone, contacts or starred) or repeat callers may ring. Android decides
        // per caller; this only says whether any call can get through.
        val allowsCalls = if (dnd != DndState.PRIORITY) null else runCatching {
            val p = nm?.notificationPolicy ?: return@runCatching null
            (p.priorityCategories and (NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS)) != 0
        }.getOrNull()
        val ringer = when (runCatching { am?.ringerMode }.getOrNull()) {
            AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            else -> RingerMode.UNKNOWN
        }
        val volume = runCatching { am?.getStreamVolume(AudioManager.STREAM_RING) }.getOrNull()
        val max = runCatching { am?.getStreamMaxVolume(AudioManager.STREAM_RING) }.getOrNull()
        return RingFacts(
            startedAt = startedAt,
            dnd = dnd,
            dndAllowsCalls = allowsCalls,
            ringer = ringer,
            ringVolume = volume,
            ringVolumeMax = max,
            vibrate = vibrateForCalls(context, ringer),
        )
    }

    /** "Vibrate for calls": always in vibrate mode, never in silent mode, else the system setting. */
    private fun vibrateForCalls(context: Context, ringer: RingerMode): Boolean? = when (ringer) {
        RingerMode.VIBRATE -> true
        RingerMode.SILENT -> false
        else -> runCatching {
            val cr = context.contentResolver
            val on = Settings.System.getInt(cr, Settings.System.VIBRATE_WHEN_RINGING, 0) != 0
            // Android 13+ also has a ring vibration intensity; 0 means off.
            on && Settings.System.getInt(cr, "ring_vibration_intensity", -1) != 0
        }.getOrNull()
    }

    fun route(audio: AudioUi): Pair<AnswerRoute, String?>? {
        val r = audio.current ?: return null
        return when (r.type) {
            RouteType.EARPIECE -> AnswerRoute.EARPIECE to null
            RouteType.SPEAKER -> AnswerRoute.SPEAKER to null
            RouteType.BLUETOOTH -> AnswerRoute.BLUETOOTH to r.name.takeIf { it.isNotBlank() && it != "Bluetooth" }
            RouteType.WIRED -> AnswerRoute.WIRED to r.name.takeIf { it.isNotBlank() && it != "Wired headset" }
            RouteType.STREAMING -> AnswerRoute.OTHER to r.name.takeIf { it.isNotBlank() }
        }
    }
}
