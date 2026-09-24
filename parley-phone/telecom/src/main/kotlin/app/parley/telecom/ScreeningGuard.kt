package app.parley.telecom

import android.content.Context
import android.os.SystemClock
import app.parley.common.Decision
import app.parley.common.PhoneNumbers

/**
 * Safety rules around screening that must hold regardless of user settings:
 * - after an emergency call, nothing is blocked for [EMERGENCY_WINDOW_MS] so call-backs from
 *   emergency services (often hidden or unknown numbers) always get through;
 * - a decision made by the CallScreeningService is reused by the InCallService instead of
 *   screening (and logging) the same call twice.
 */
object ScreeningGuard {
    private const val PREFS = "parley_screening_guard"
    private const val KEY_EMERGENCY = "last_emergency_wall_ms"
    private const val EMERGENCY_WINDOW_MS = 60 * 60 * 1000L
    private const val DECISION_TTL_MS = 30_000L

    private data class Recent(val key: String, val at: Long, val outcome: ScreenOutcome)
    private val recent = ArrayDeque<Recent>()

    fun noteEmergencyCall(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_EMERGENCY, System.currentTimeMillis()).apply()
    }

    fun inEmergencyWindow(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_EMERGENCY, 0L)
        return last > 0 && System.currentTimeMillis() - last in 0..EMERGENCY_WINDOW_MS
    }

    /** When the current emergency window ends (for the visible countdown, B23), or null when none is running. */
    fun emergencyWindowEndsAt(context: Context): Long? {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_EMERGENCY, 0L)
        return if (inEmergencyWindow(context)) last + EMERGENCY_WINDOW_MS else null
    }

    /** Ends the emergency window early ("Reset"). */
    fun clearEmergencyWindow(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_EMERGENCY).apply()
    }

    @Synchronized
    fun remember(number: String?, decision: Decision) = remember(number, ScreenOutcome(decision))

    @Synchronized
    fun remember(number: String?, outcome: ScreenOutcome) {
        val now = SystemClock.elapsedRealtime()
        recent.removeAll { now - it.at > DECISION_TTL_MS }
        recent.addLast(Recent(key(number), now, outcome))
    }

    @Synchronized
    fun recall(number: String?): Decision? = recallOutcome(number)?.decision

    @Synchronized
    fun recallOutcome(number: String?): ScreenOutcome? {
        val now = SystemClock.elapsedRealtime()
        return recent.lastOrNull { it.key == key(number) && now - it.at <= DECISION_TTL_MS }?.outcome
    }

    private fun key(number: String?) = if (number.isNullOrBlank()) "hidden" else PhoneNumbers.matchKey(number)
}
