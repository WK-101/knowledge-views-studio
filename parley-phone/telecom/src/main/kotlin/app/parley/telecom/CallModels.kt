package app.parley.telecom

import app.parley.common.Verification

enum class CallState { NEW, RINGING, DIALING, CONNECTING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED, SELECT_ACCOUNT, OTHER }

data class CallUi(
    val id: String,
    val state: CallState,
    val number: String?,
    val hidden: Boolean,
    val name: String?,
    val label: String?,
    val photoUri: String?,
    /** The caller's call-screen background (C14), a file in Parley's storage, or null. */
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
    val note: String? = null,
    val lastCall: String? = null,
    /** I6: job/company and the "who is this" line of the caller card. */
    val subtitle: String? = null,
    val context: String? = null,
    /** Caller lookup finished and found nobody. */
    val unknown: Boolean = false,
    val location: String? = null,
    /** Phone account (SIM) of the call, or the one requested when dialling (A10). */
    val accountId: String? = null,
    /** `elapsedRealtime` when the call was put on hold (0 = not held), for "on hold · 02:10" (A2). */
    val heldSinceElapsed: Long = 0,
    /** Why the call rings silently when it isn't a blocking rule (e.g. an allowance is used up). */
    val silenceReason: String? = null,
    /** Screening verdict for the caller card: "Blocked by rule 'Telemarketing' · 7 calls", "Likely spam · FTC list". */
    val verdict: String? = null,
    val verdictWarn: Boolean = false,
    /** The caller lookup finished and the number is neither a contact nor a private contact (any direction; V4). */
    val noContact: Boolean = false,
    /** The number of the SIM the call is on, when Android knows it and two SIMs are in use (V5). */
    val accountNumber: String? = null,
    /** Localised "Private number" / "Unknown", shown when there is neither a name nor a number. */
    val fallbackTitle: String = "",
    /** P6: why an outgoing call didn't go through (set on the ended call only), and the reason to show. */
    val failure: app.parley.common.calls.FailureKind? = null,
    val failureText: String? = null,
    /** R8/R9: the last note and open promises of the caller. */
    val memory: CallerMemory? = null,
    /** R8: "Anything to remember?" is offered once the call ends. */
    val memoryPrompt: Boolean = false,
) {
    val title: String get() = name ?: number?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val isLive: Boolean get() = state != CallState.DISCONNECTED && state != CallState.DISCONNECTING

    /** "Work · …4567": which SIM a call came in on, for the answer control on dual-SIM phones (V5). */
    val simHint: String?
        get() = accountLabel?.let { l -> listOfNotNull(l, accountNumber?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }?.let { "…$it" }).joinToString(" · ") }

    /** P2: "Block & decline" is offered for a ringing call with a number (never an emergency call-back). */
    val canBlockAndDecline: Boolean
        get() = state == CallState.RINGING && !hidden && !isEmergency && !number.isNullOrBlank()

    /** R8: after a call that connected with a contact, "Anything to remember?" (opt-in). */
    val memoryCard: Boolean
        get() = memoryPrompt && !noContact && !hidden && !isEmergency && connectTimeMillis > 0 && !number.isNullOrBlank()

    /** Show the post-call card: an ended call with a number that isn't in contacts (V4). */
    val postCallCard: Boolean
        get() = noContact && !hidden && !isEmergency && !number.isNullOrBlank() && number.count { it.isDigit() } >= 3
}

/** P1/P9: the state as the pure call-waiting logic in core:common sees it. */
fun CallState.live(): app.parley.common.calls.LiveCallState = when (this) {
    CallState.RINGING -> app.parley.common.calls.LiveCallState.RINGING
    CallState.ACTIVE -> app.parley.common.calls.LiveCallState.ACTIVE
    CallState.HOLDING -> app.parley.common.calls.LiveCallState.HOLDING
    CallState.DIALING, CallState.CONNECTING, CallState.SELECT_ACCOUNT -> app.parley.common.calls.LiveCallState.DIALING
    else -> app.parley.common.calls.LiveCallState.OTHER
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

/** An outgoing call Parley asked Telecom to place, shown until the call exists (A10). */
data class PendingOutgoing(val number: String, val simLabel: String?, val atElapsed: Long)

/** P2: the call the user declined with "Block & decline", for Undo on the call-ended screen. */
data class DeclineBlock(
    val callId: String,
    val number: String,
    /** The rule written (Undo removes it), 0 when the number was already blocked, null when it couldn't be blocked. */
    val ruleId: Long?,
    val undone: Boolean = false,
)
