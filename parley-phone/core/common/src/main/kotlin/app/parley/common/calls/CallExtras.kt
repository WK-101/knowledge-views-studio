package app.parley.common.calls

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Call-path switches added in v3.1 (Settings › Calls). Kept in their own small store so the call path reads them
 * from memory, and so they don't touch the main settings document.
 */
@Serializable
data class CallExtrasConfig(
    /** V6: turn the screen off near the ear during earpiece calls. Off for broken sensors or listening in a pocket. */
    val proximitySensor: Boolean = true,
    /** V8: ask before calling from a favourite, the widget or a shortcut while the proximity sensor is covered. */
    val pocketGuard: Boolean = true,
    /** V3: re-alert for unseen missed calls every N minutes; 0 = off (the default). */
    val missedReAlertMinutes: Int = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String?): CallExtrasConfig = if (text.isNullOrBlank()) {
            CallExtrasConfig()
        } else {
            try {
                json.decodeFromString(serializer(), text).let { it.copy(missedReAlertMinutes = MissedReAlert.normalise(it.missedReAlertMinutes)) }
            } catch (_: Exception) {
                CallExtrasConfig()
            }
        }

        fun encode(c: CallExtrasConfig): String = json.encodeToString(serializer(), c)
    }
}

/** V3: re-notify an unseen missed call every few minutes, for a while, and never through Do Not Disturb. */
object MissedReAlert {
    /** The choices offered in Settings; 0 = off. */
    val CHOICES = listOf(0, 5, 10, 15, 30)

    /** At most this many re-alerts per missed call… */
    const val MAX_ALERTS = 12

    /** …and never later than this after the call was missed. */
    const val MAX_SPAN_MS = 3 * 60 * 60_000L

    fun normalise(minutes: Int): Int = if (minutes in CHOICES) minutes else 0

    /**
     * When the next re-alert is due, or null to stop: off, too many already, or too long after the call.
     * [missedAt]: when the (latest) missed call happened; [alertsSoFar]: re-alerts already shown for it.
     */
    fun nextAt(intervalMinutes: Int, missedAt: Long, alertsSoFar: Int, now: Long): Long? {
        if (normalise(intervalMinutes) == 0 || alertsSoFar >= MAX_ALERTS) return null
        val next = now + intervalMinutes * 60_000L
        return if (next - missedAt > MAX_SPAN_MS) null else next
    }

    enum class Step {
        /** Post the notification again with sound. */
        ALERT,

        /** Do Not Disturb is on: stay quiet this time, try again at the next interval. */
        SKIP,

        /** Seen, dismissed or no longer missed: stop re-alerting. */
        STOP,
    }

    /** What to do when a re-alert is due. */
    fun step(stillUnseen: Boolean, notificationShowing: Boolean, dnd: DndState): Step = when {
        !stillUnseen || !notificationShowing -> Step.STOP
        dnd != DndState.OFF && dnd != DndState.UNKNOWN -> Step.SKIP
        else -> Step.ALERT
    }
}

/** Where a call was started, for the pocket-dial guard (V8). */
enum class CallSource { KEYPAD, RECENTS, CONTACT, FAVORITE, WIDGET, SHORTCUT, OTHER }

object PocketGuard {
    /** One tap starts a call from these, so a pocket can start one too. */
    val GUARDED = setOf(CallSource.FAVORITE, CallSource.WIDGET, CallSource.SHORTCUT)

    /** Ask before calling when the guard is on, the call came from a one-tap place and the sensor reads "near". */
    fun shouldAsk(enabled: Boolean, source: CallSource, covered: Boolean?): Boolean = enabled && source in GUARDED && covered == true

    const val QUESTION = "Your phone's proximity sensor is covered. Is it in a pocket or a bag?"
}
