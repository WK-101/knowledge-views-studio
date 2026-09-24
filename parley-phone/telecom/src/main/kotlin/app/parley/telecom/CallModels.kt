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
) {
    val title: String get() = name ?: number?.takeIf { it.isNotBlank() } ?: if (hidden) "Private number" else "Unknown"
    val isLive: Boolean get() = state != CallState.DISCONNECTED && state != CallState.DISCONNECTING

    /** "Work · …4567": which SIM a call came in on, for the answer control on dual-SIM phones (V5). */
    val simHint: String?
        get() = accountLabel?.let { l -> listOfNotNull(l, accountNumber?.filter { it.isDigit() }?.takeLast(4)?.takeIf { it.length == 4 }?.let { "…$it" }).joinToString(" · ") }

    /** Show the post-call card: an ended call with a number that isn't in contacts (V4). */
    val postCallCard: Boolean
        get() = noContact && !hidden && !isEmergency && !number.isNullOrBlank() && number.count { it.isDigit() } >= 3
}

enum class RouteType { EARPIECE, SPEAKER, BLUETOOTH, WIRED, STREAMING }

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
