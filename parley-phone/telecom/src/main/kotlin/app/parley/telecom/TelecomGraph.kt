package app.parley.telecom

import android.content.Context
import android.content.Intent
import app.parley.common.AnswerGesture
import app.parley.common.Decision
import app.parley.common.ListDensity
import app.parley.common.ThemeMode
import app.parley.common.Verification
import app.parley.common.calltime.CallTimePlan
import app.parley.common.calls.RingFacts
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
    /** Per-contact call-screen background (file URI in app storage), or null. See PeopleContainer.callBackgroundFor. */
    val backgroundUri: String? = null,
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

    /** Caller info with the call's phone account (SIM), so national numbers are read with its country (F7). */
    suspend fun callerInfo(number: String, accountId: String?): CallerDisplay? = callerInfo(number)
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

    /** V6: turn the screen off near the ear during earpiece calls (Settings › Calls). Read from memory. */
    fun proximityEnabled(): Boolean = true

    /** V9: ring-side facts of an incoming call that has ended ("Why did my phone ring, or not?"). Off the call path. */
    fun onRingFacts(number: String?, facts: RingFacts) {}

    /** V4: an intent into the app for a post-call action on an unknown number, or null when not available. */
    fun postCallIntent(context: Context, action: PostCallAction, number: String): Intent? = null

    /** V4: saves [number] as a private temporary contact; returns what to tell the user, or null on failure. */
    suspend fun savePrivately(number: String, name: String): String? = null

    /** V4: a name to suggest when saving an unknown number ("Caller from Lyon"). */
    fun suggestedName(number: String): String = number
}

/** Post-call card actions handled by the app (V4). */
enum class PostCallAction { BLOCK, REPORT }

object TelecomGraph {
    @Volatile
    private var deps: TelecomDependencies? = null

    fun install(d: TelecomDependencies) {
        deps = d
    }

    val dependencies: TelecomDependencies
        get() = deps ?: error("TelecomGraph not installed")
}
