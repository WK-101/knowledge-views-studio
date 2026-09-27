package app.parley.telecom

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.parley.common.Decision
import app.parley.common.PhoneIdentity
import app.parley.common.calls.EmergencyPolicy

/**
 * Safety rules around screening that must hold regardless of user settings:
 * - after an emergency call, nothing is blocked for [EmergencyPolicy.WINDOW_MS] so call-backs from
 *   emergency services (often hidden or unknown numbers) always get through;
 * - a decision made by the CallScreeningService is reused by the InCallService instead of
 *   screening (and logging) the same call twice.
 */
object ScreeningGuard {
    private const val PREFS = "parley_screening_guard"
    private const val KEY_EMERGENCY = "last_emergency_wall_ms"
    private const val KEY_EMERGENCY_ELAPSED = "last_emergency_elapsed_ms"
    private const val KEY_EMERGENCY_BOOT = "last_emergency_boot"
    private const val DECISION_TTL_MS = 30_000L

    private data class Recent(val number: String?, val at: Long, val outcome: ScreenOutcome)
    private val recent = ArrayDeque<Recent>()

    /**
     * (Re)starts the window now. Written with commit(): it's called when an emergency call starts, and the process
     * may be gone before an asynchronous write lands.
     */
    fun noteEmergencyCall(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_EMERGENCY, System.currentTimeMillis())
            .putLong(KEY_EMERGENCY_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_EMERGENCY_BOOT, bootCount(context))
            .commit()
    }

    fun inEmergencyWindow(context: Context): Boolean = windowLeftMs(context) != null

    /** When the current emergency window ends (for the visible countdown, B23), or null when none is running. */
    fun emergencyWindowEndsAt(context: Context): Long? = windowLeftMs(context)?.let { System.currentTimeMillis() + it }

    /** Ends the emergency window early ("Reset"). */
    fun clearEmergencyWindow(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_EMERGENCY).remove(KEY_EMERGENCY_ELAPSED).remove(KEY_EMERGENCY_BOOT).commit()
    }

    private fun windowLeftMs(context: Context): Long? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wall = p.getLong(KEY_EMERGENCY, 0L)
        if (wall <= 0L) return null
        // Marks written by older versions have only the wall time: boot -1 compares by wall clock.
        val mark = EmergencyPolicy.WindowMark(p.getLong(KEY_EMERGENCY_ELAPSED, 0L), p.getInt(KEY_EMERGENCY_BOOT, -1), wall)
        return EmergencyPolicy.windowLeftMs(mark, SystemClock.elapsedRealtime(), bootCount(context), System.currentTimeMillis())
    }

    /** Tells a monotonic time from an earlier boot apart; -1 when unknown. */
    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    @Synchronized
    fun remember(number: String?, decision: Decision) = remember(number, ScreenOutcome(decision))

    @Synchronized
    fun remember(number: String?, outcome: ScreenOutcome) {
        val now = SystemClock.elapsedRealtime()
        recent.removeAll { now - it.at > DECISION_TTL_MS }
        recent.addLast(Recent(number?.takeIf { it.isNotBlank() }, now, outcome))
    }

    @Synchronized
    fun recall(number: String?): Decision? = recallOutcome(number)?.decision

    @Synchronized
    fun recallOutcome(number: String?): ScreenOutcome? {
        val now = SystemClock.elapsedRealtime()
        return recent.lastOrNull { sameCaller(it.number, number) && now - it.at <= DECISION_TTL_MS }?.outcome
    }

    /** Hidden callers match each other; numbers match as the same line (the screening service and Telecom may format them differently). */
    private fun sameCaller(stored: String?, number: String?) =
        if (stored == null || number.isNullOrBlank()) stored == null && number.isNullOrBlank() else PhoneIdentity.same(stored, number, null)
}
