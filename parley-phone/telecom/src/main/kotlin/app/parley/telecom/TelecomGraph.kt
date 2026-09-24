package app.parley.telecom

import android.content.Context
import android.content.Intent
import app.parley.common.AnswerGesture
import app.parley.common.Decision
import app.parley.common.ListDensity
import app.parley.common.ThemeMode
import app.parley.common.Verification
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

    /**
     * Screening with everything the call path knows (B2, B9, B20, B24): the SIM's phone-account id (null on the
     * screening service, which never gets one) and the network caller name.
     */
    suspend fun screenCall(number: String?, hidden: Boolean, verification: Verification, accountId: String?, callerName: String?): ScreenOutcome =
        ScreenOutcome(screen(number, hidden, verification))

    /** Rules limited to one SIM exist, so an earlier decision made without the SIM must be re-checked (B9). */
    fun simRulesActive(): Boolean = false

    /** Calling this number starts the emergency window, like an emergency number (B23: a GP, a school). */
    fun startsEmergencyWindow(number: String): Boolean = false

    /** An incoming call stopped ringing: how long it rang and whether it was answered (B10 one-ring guard). */
    fun onRingFinished(number: String?, startedAt: Long, ringMillis: Long, answered: Boolean) {}
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
