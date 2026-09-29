package app.parley.telecom

import android.content.Context
import android.content.Intent
import app.parley.common.AnswerGesture
import app.parley.common.ListDensity
import app.parley.common.ThemeMode
import app.parley.common.Verification
import app.parley.common.calls.CallExtrasConfig
import app.parley.common.calltime.CallTimePlan
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.RingFacts
import app.parley.common.ux.CallScreenBackground
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
    /** "Engineer · Acme" (or "Work profile" for a work contact). */
    val subtitle: String? = null,
    /** The "who is this" line of a private contact. */
    val context: String? = null,
    /** The last note and open promises for this contact (never for private contacts). */
    val memory: CallerMemory? = null,
    /** Offer "Anything to remember?" after the call (a contact, and the setting is on). */
    val memoryPrompt: Boolean = false,
    /** "she/her", shown beside the name. */
    val pronouns: String? = null,
    /** The caller's haptic caller ID ([app.parley.common.calls.CallerHaptics] spec): their own, else a label's. */
    val vibration: String? = null,
    /** The person, or one of their labels, is chosen for auto-answer. */
    val autoAnswerChosen: Boolean = false,
    /**
     * The contact's own ringtone (Android plays it), so Parley's ringer can play the same tone when it takes over the
     * ringing for [vibration]; null: the phone's default.
     */
    val ownRingtone: String? = null,
)

/**
 * What to remember about a caller. The call screen shows it only while the phone is unlocked, unless
 * [onLockScreen] (the user allowed it).
 */
data class CallerMemory(
    /** The newest note, in one line. */
    val lastNote: String?,
    /** Open promises ("[ ] …" lines), newest first. */
    val promises: List<String>,
    val onLockScreen: Boolean = false,
) {
    val isEmpty: Boolean get() = lastNote.isNullOrBlank() && promises.isEmpty()
}

data class InCallAppearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = true,
    val density: ListDensity = ListDensity.COMFORTABLE,
    val answerGesture: AnswerGesture = AnswerGesture.SWIPE,
    val quickReplies: List<String> = emptyList(),
    /** "Hide screen content" also covers the call screen (screenshots, recents thumbnail, casting). */
    val secureScreen: Boolean = false,
    /** False until the stored settings were read: the call screen stays secure until then. */
    val loaded: Boolean = false,
    /** Simple mode: large answer and decline buttons (tap, never a slider). */
    val simpleMode: Boolean = false,
    /** "Decline this call?" before declining. */
    val confirmDecline: Boolean = false,
    /** Say the caller's name aloud (on-device text-to-speech, contacts only, only while the ringer is on). */
    val speakCallerName: Boolean = false,
    /** Settings › Calls › "Call screen background": the caller's colour or plain. */
    val callBackground: CallScreenBackground = CallScreenBackground.CALLER_COLOUR,
)

/** Who is calling: the caller card and the post-call card's name suggestion. */
interface CallerInfoSource {
    /** Caller info with the call's phone account (SIM), so national numbers are read with its country. */
    suspend fun callerInfo(number: String, accountId: String?): CallerDisplay?

    /** Offline "where is this number from" for unknown callers. */
    fun describeNumber(number: String): String? = null

    /**
     * The time-zone id where [number] is (offline, by country and area code), or null when it can't be told. Read by
     * the call screen off the main thread, after it is up, to show the caller's local time; never on the ring path.
     */
    fun callerZone(number: String, accountId: String?): String? = null

    /** A name to suggest when saving an unknown number ("Caller from Lyon"). */
    fun suggestedName(number: String): String = number

    /** Ringtone to play for callers who aren't contacts, or null to let the system ring. */
    fun unknownRingtone(): String? = null
}

/** Blocking and screening as the call path uses them, and the emergency checks that override them. */
interface ScreeningHooks {
    /** Whether any screening could act on a call now (read from memory: the call path runs on the main thread). */
    fun screeningActive(): Boolean

    /**
     * Screening with everything the call path knows: the SIM's phone-account id (null on the screening service,
     * which never gets one) and the network caller name.
     */
    suspend fun screenCall(number: String?, hidden: Boolean, verification: Verification, accountId: String?, callerName: String?): ScreenOutcome

    /** Rules limited to one SIM exist, so an earlier decision made without the SIM must be re-checked. */
    fun simRulesActive(): Boolean = false

    /** Calling this number starts the emergency window, like an emergency number (a GP, a school). */
    fun startsEmergencyWindow(number: String): Boolean = false

    /** The platform's emergency-number check (see [EmergencyPolicy]); the fallback list until the app answers. */
    fun isEmergencyNumber(number: String): Boolean = EmergencyPolicy.isFallbackEmergencyNumber(number)

    /**
     * Writes a block rule for [number] before "Block & decline" declines the call. Returns the new rule's id (for
     * Undo), 0 when the number was already blocked, or null when the rule couldn't be written.
     */
    suspend fun blockForDecline(number: String): Long? = null

    /** Undo on the call-ended screen: removes the rule [blockForDecline] wrote. */
    suspend fun undoBlockForDecline(ruleId: Long) {}
}

/** Call time, SIM choice and placing calls again. */
interface CallPolicyHooks {
    suspend fun preferredAccountId(number: String): String?

    /** Talk-time reminders, limit and allowance for a new call. Never called for emergency calls. */
    suspend fun callTimePlan(number: String?, accountId: String?, incoming: Boolean): CallTimePlan = CallTimePlan.NONE

    /** Settings › Calls › Answer automatically, read from memory on the call path (off by default). */
    fun autoAnswer(): CallExtrasConfig = CallExtrasConfig()

    /** True when this caller's allowance is used up and the user wants such calls to ring silently. */
    suspend fun silenceOverQuota(number: String, accountId: String?): Boolean = false

    /** Retry on the failure banner. Returns what to tell the user when the call couldn't be placed, else null. */
    suspend fun redial(number: String, accountId: String?): String? = null
}

/** What the call path hands back once a call has rung or ended: history, the ledger, notes. Off the call path. */
interface CallRecordHooks {
    /** Called when a call leaves Telecom (for private-history sweeps and call notes). */
    fun onCallEnded(number: String?, incoming: Boolean, connectTimeMillis: Long) {}

    /**
     * A connected, non-emergency call ended after [durationSec] seconds of talk: recorded in the call-usage ledger that
     * allowances count (it survives a cleared call log and includes private contacts' calls).
     */
    fun onCallUsage(number: String?, accountId: String?, incoming: Boolean, connectTimeMillis: Long, durationSec: Long) {}

    /** An incoming call stopped ringing: how long it rang and whether it was answered (one-ring guard). */
    fun onRingFinished(number: String?, startedAt: Long, ringMillis: Long, answered: Boolean) {}

    /** Ring-side facts of an incoming call that has ended ("Why did my phone ring, or not?"). */
    fun onRingFacts(number: String?, facts: RingFacts) {}

    fun saveCallNote(number: String?, connectTimeMillis: Long, text: String) {}

    /**
     * "Anything to remember?" after a call with a contact: saves [note] as a call note (it shows on the contact's
     * timeline) and, with [followUpDays], sets a one-off reminder to follow up.
     */
    fun rememberAfterCall(number: String, connectTimeMillis: Long, note: String?, followUpDays: Int?) {}

    /** Saves [number] as a private temporary contact; returns what to tell the user, or null on failure. */
    suspend fun savePrivately(number: String, name: String): String? = null
}

/** The call screen's look and feel, and the ways out of it into the app. */
interface UiHooks {
    val appearance: StateFlow<InCallAppearance>

    /** Intent for the main app: [dialpad] opens the keypad (used by "Add call"). */
    fun mainIntent(context: Context, dialpad: Boolean): Intent
    fun contactIntent(context: Context, contactId: Long?, number: String?): Intent

    /** An intent into the app for a post-call action on an unknown number, or null when not available. */
    fun postCallIntent(context: Context, action: PostCallAction, number: String): Intent? = null

    /** Vibrate on connect, disconnect, swap, merge and limit warnings. */
    fun callHaptics(): Boolean = true

    /** The buzz when a call connects (only with [callHaptics] on). */
    fun connectHaptic(): Boolean = true

    /** Turn the screen off near the ear during earpiece calls (Settings › Calls). Read from memory. */
    fun proximityEnabled(): Boolean = true
}

/**
 * What the call path needs from the rest of the app, as cohesive parts (each collaborator of the call path asks only
 * for its part). Implemented by the app module so that this module never depends on data or feature code.
 */
interface TelecomDependencies : CallerInfoSource, ScreeningHooks, CallPolicyHooks, CallRecordHooks, UiHooks

/** Post-call card actions handled by the app. */
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
