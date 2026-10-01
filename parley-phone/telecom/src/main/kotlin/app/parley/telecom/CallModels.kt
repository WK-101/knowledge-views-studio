package app.parley.telecom

import app.parley.common.Verification
import app.parley.common.calls.DropKind
import app.parley.common.calls.FailureKind
import app.parley.common.calls.LiveCallState

enum class CallState { NEW, RINGING, DIALING, CONNECTING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED, SELECT_ACCOUNT, OTHER }

data class CallUi(
    val id: String,
    val state: CallState,
    val number: String?,
    val hidden: Boolean,
    val name: String?,
    val label: String?,
    val photoUri: String?,
    /** The caller's call-screen background, a file in Parley's storage, or null. */
    val backgroundUri: String? = null,
    val contactId: Long?,
    val incoming: Boolean,
    val connectTimeMillis: Long,
    val isConference: Boolean,
    val children: List<CallUi>,
    val canHold: Boolean,
    val canMerge: Boolean,
    val canSwap: Boolean,
    val canMute: Boolean,
    val canSeparate: Boolean,
    val canDisconnectChild: Boolean,
    val canRespondViaText: Boolean,
    val accountLabel: String?,
    val verification: Verification,
    val disconnectReason: String?,
    val postDialWait: String?,
    val silenced: Boolean,
    val isEmergency: Boolean,
    /** The contact's lookup key, with [contactId]: who is calling for Do Not Disturb's contact filters. */
    val lookupKey: String? = null,
    val note: String? = null,
    val lastCall: String? = null,
    /** Job/company and the "who is this" line of the caller card. */
    val subtitle: String? = null,
    val context: String? = null,
    /** Caller lookup finished and found nobody. */
    val unknown: Boolean = false,
    val location: String? = null,
    /** Phone account (SIM) of the call, or the one requested when dialling. */
    val accountId: String? = null,
    /** `elapsedRealtime` when the call was put on hold (0 = not held), for "on hold · 02:10". */
    val heldSinceElapsed: Long = 0,
    /** Why the call rings silently when it isn't a blocking rule (e.g. an allowance is used up). */
    val silenceReason: String? = null,
    /** Screening verdict for the caller card: "Blocked by rule 'Telemarketing' · 7 calls", "Likely spam · FTC list". */
    val verdict: String? = null,
    val verdictWarn: Boolean = false,
    /** The caller lookup finished and the number is neither a contact nor a private contact (any direction; V4). */
    val noContact: Boolean = false,
    /** The number of the SIM the call is on, when Android knows it and two SIMs are in use. */
    val accountNumber: String? = null,
    /** Localised "Private number" / "Unknown", shown when there is neither a name nor a number. */
    val fallbackTitle: String = "",
    /** Why an outgoing call didn't go through (set on the ended call only), and the reason to show. */
    val failure: FailureKind? = null,
    val failureText: String? = null,
    /** The last note and open promises of the caller. */
    val memory: CallerMemory? = null,
    /** "Anything to remember?" is offered once the call ends. */
    val memoryPrompt: Boolean = false,
    /** The user silenced the ringer with a hardware key (volume, power) while this call rang. */
    val systemSilenced: Boolean = false,
    /** "Block & decline" is writing the rule; the call can't be answered from Parley meanwhile. */
    val blockingDecline: Boolean = false,
    /** The network carries this call in HD voice (Call.Details.PROPERTY_HIGH_DEF_AUDIO), when it says so. */
    val hdAudio: Boolean = false,
    /** The call goes over Wi-Fi calling (Call.Details.PROPERTY_WIFI). */
    val wifi: Boolean = false,
    /** The caller's pronouns ("she/her"), shown beside the name. */
    val pronouns: String? = null,
    /** `elapsedRealtime` when this ringing call is answered automatically (0: it isn't); the screen shows Cancel. */
    val autoAnswerAt: Long = 0,
    /** L10: the subject the caller sent with the call (cleaned, plain text), when the network passes it on. */
    val subject: String? = null,
    /** The caller marked the call urgent (Call Composer). */
    val urgent: Boolean = false,
    /** P1: why a ringing call rings although screening would otherwise have kept it quiet, in words. */
    val rangThrough: String? = null,
    /** P5: the connected call dropped (set on the ended call only), and why, in words ("Lost signal · Wi-Fi calling"). */
    val drop: DropKind? = null,
    val dropText: String? = null,
    /** I10: `elapsedRealtime` when "I'm on hold" started, or 0 when not in hold mode. */
    val holdModeSince: Long = 0,
    /** I1: what Parley remembers about this number (not a contact), or null. */
    val numberMemory: NumberMemoryLine? = null,
) {
    val title: String get() = name ?: number?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val isLive: Boolean get() = state != CallState.DISCONNECTED && state != CallState.DISCONNECTING

    /** "Work · …4567": which SIM a call came in on, for the answer control on dual-SIM phones. */
    val simHint: String?
        get() = accountLabel?.let { l -> listOfNotNull(l, accountNumber?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }?.let { "…$it" }).joinToString(" · ") }

    /** "Block & decline" is offered for a ringing call with a number (never an emergency call-back). */
    val canBlockAndDecline: Boolean
        get() = state == CallState.RINGING && !hidden && !isEmergency && !number.isNullOrBlank() && !blockingDecline

    /** After a call that connected with a contact, "Anything to remember?" (opt-in). */
    val memoryCard: Boolean
        get() = memoryPrompt && !noContact && !hidden && !isEmergency && connectTimeMillis > 0 && !number.isNullOrBlank()

    /** "Call again" after a drop: a number to call, never an emergency call. */
    val canCallAgain: Boolean
        get() = drop != null && !hidden && !isEmergency && !number.isNullOrBlank()

    /** I3 "Check it's really them": a live, connected or ringing call that isn't an emergency call. */
    val canVerify: Boolean
        get() = isLive && !isEmergency && !isConference && state != CallState.SELECT_ACCOUNT

    /** I10 "I'm on hold" can start: a connected, active call. */
    val canHoldMode: Boolean
        get() = state == CallState.ACTIVE && !isEmergency && holdModeSince == 0L

    /** Show the post-call card: an ended call with a number that isn't in contacts. */
    val postCallCard: Boolean
        get() = noContact && !hidden && !isEmergency && !number.isNullOrBlank() && number.count { it.isDigit() } >= 3
}

/** The state as the pure call-waiting logic in core:common sees it. */
fun CallState.live(): LiveCallState = when (this) {
    CallState.RINGING -> LiveCallState.RINGING
    CallState.ACTIVE -> LiveCallState.ACTIVE
    CallState.HOLDING -> LiveCallState.HOLDING
    CallState.DIALING, CallState.CONNECTING, CallState.SELECT_ACCOUNT -> LiveCallState.DIALING
    else -> LiveCallState.OTHER
}

enum class RouteType { EARPIECE, SPEAKER, BLUETOOTH, WIRED, STREAMING }

/** [name] is the device's own name, or blank for a generic route (the UI names it in the user's language). */
data class AudioRoute(val key: String, val type: RouteType, val name: String)

data class AudioUi(
    val routes: List<AudioRoute> = emptyList(),
    val current: AudioRoute? = null,
    val muted: Boolean = false,
) {
    val hasExternal: Boolean get() = routes.any { it.type == RouteType.BLUETOOTH || it.type == RouteType.WIRED }
}

/** An outgoing call Parley asked Telecom to place, shown until the call exists. */
data class PendingOutgoing(val number: String, val simLabel: String?, val atElapsed: Long)

/** The call the user declined with "Block & decline", for Undo on the call-ended screen. */
data class DeclineBlock(
    val callId: String,
    val number: String,
    /** The rule written (Undo removes it), 0 when the number was already blocked, null when it couldn't be blocked. */
    val ruleId: Long?,
    val undone: Boolean = false,
    /** The rule is still being written (it took longer than the call may keep ringing): [ruleId] follows. */
    val pending: Boolean = false,
    /** The call was answered elsewhere (a headset) while the rule was written: blocked, but not declined. */
    val answered: Boolean = false,
)
