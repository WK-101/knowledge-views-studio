package app.parley.telecom

import android.content.Context
import android.content.Intent
import app.parley.common.AnswerGesture
import app.parley.common.Decision
import app.parley.common.ListDensity
import app.parley.common.ThemeMode
import app.parley.common.Verification
import app.parley.common.calltime.CallTimePlan
import kotlinx.coroutines.flow.StateFlow

data class CallerDisplay(
    val name: String,
    val photoUri: String?,
    val label: String?,
    val contactId: Long?,
    val lookupKey: String?,
    /** The user's pinned note for this person ("Ask about the invoice"). */
    val note: String? = null,
    /** e.g. "Last call 3 days ago · 4 min". */
    val lastCall: String? = null,
)

data class InCallAppearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = true,
    val density: ListDensity = ListDensity.COMFORTABLE,
    val answerGesture: AnswerGesture = AnswerGesture.SWIPE,
    val quickReplies: List<String> = emptyList(),
)

/**
 * What the call path needs from the rest of the app. Implemented by the app module so that this
 * module never depends on data or feature code.
 */
interface TelecomDependencies {
    val appearance: StateFlow<InCallAppearance>
    suspend fun callerInfo(number: String): CallerDisplay?
    fun screeningActive(): Boolean
    suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision
    suspend fun preferredAccountId(number: String): String?
    /** Intent for the main app: [dialpad] opens the keypad (used by "Add call"). */
    fun mainIntent(context: Context, dialpad: Boolean): Intent
    fun contactIntent(context: Context, contactId: Long?, number: String?): Intent

    /** Called when a call leaves Telecom (for private-history sweeps and call notes). */
    fun onCallEnded(number: String?, incoming: Boolean, connectTimeMillis: Long) {}

    /** Ringtone to play for callers who aren't contacts, or null to let the system ring. */
    fun unknownRingtone(): String? = null

    fun saveCallNote(number: String?, connectTimeMillis: Long, text: String) {}

    /** Offline "where is this number from" for unknown callers. */
    fun describeNumber(number: String): String? = null

    /** Talk-time reminders, limit and allowance for a new call (T1, T5, T6). Never called for emergency calls. */
    suspend fun callTimePlan(number: String?, accountId: String?, incoming: Boolean): CallTimePlan = CallTimePlan.NONE

    /** True when this caller's allowance is used up and the user wants such calls to ring silently (T6). */
    suspend fun silenceOverQuota(number: String, accountId: String?): Boolean = false

    /** Vibrate on connect, disconnect, swap, merge and limit warnings (A6). */
    fun callHaptics(): Boolean = true
}

object TelecomGraph {
    @Volatile
    private var deps: TelecomDependencies? = null

    fun install(d: TelecomDependencies) {
        deps = d
    }

    val dependencies: TelecomDependencies
        get() = deps ?: error("TelecomGraph not installed")
}
