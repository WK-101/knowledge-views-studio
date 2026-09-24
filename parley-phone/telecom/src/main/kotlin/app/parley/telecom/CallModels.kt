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
) {
    val title: String get() = name ?: number?.takeIf { it.isNotBlank() } ?: if (hidden) "Private number" else "Unknown"
    val isLive: Boolean get() = state != CallState.DISCONNECTED && state != CallState.DISCONNECTING
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
