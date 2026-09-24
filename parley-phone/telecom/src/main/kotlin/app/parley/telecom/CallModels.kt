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
