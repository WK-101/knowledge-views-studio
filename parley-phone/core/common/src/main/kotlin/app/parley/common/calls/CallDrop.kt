package app.parley.common.calls

/** Why a connected call dropped, in words the call-ended screen can use. */
enum class DropKind {
    /** The mobile signal went (telephony LOST_SIGNAL, CDMA_DROP). */
    LOST_SIGNAL,

    /** Wi-Fi calling lost its Wi-Fi (WIFI_LOST). */
    WIFI_LOST,

    /** The phone lost service (OUT_OF_SERVICE, the radio turned off). */
    NO_SERVICE,

    /** Any other network error. */
    NETWORK,
}

/** What is known about a call when it leaves Telecom, for [CallDrop]. */
data class DropFacts(
    val connected: Boolean,
    /** The user ended it from Parley (or its notification, a shortcut): never a drop. */
    val userEnded: Boolean,
    val emergency: Boolean,
    val code: EndCode?,
    /** `DisconnectCause.getReason()`: telephony puts its own cause's name in it ("LOST_SIGNAL", "ERROR_UNSPECIFIED"). */
    val reason: String?,
    /** A time limit ended it. */
    val endedByLimit: Boolean = false,
)

/**
 * A call that was connected and ended because of the network, not because anyone hung up. Telecom reports such
 * ends as ERROR; telephony's own cause (only readable as the name in the reason text) says which kind. A LOCAL or
 * REMOTE end is someone hanging up, so it's never a drop, whatever the reason says. Emergency calls are left to the
 * system: Parley never offers to call an emergency number again by itself.
 */
object CallDrop {
    fun classify(f: DropFacts): DropKind? {
        if (!f.connected || f.userEnded) return null
        if (f.emergency || f.endedByLimit) return null
        val kind = telephonyKind(f.reason)
        return when (f.code) {
            EndCode.ERROR -> kind ?: DropKind.NETWORK
            // Some networks report a drop as OTHER or UNKNOWN: only with telephony's own drop cause.
            EndCode.OTHER, EndCode.UNKNOWN -> kind
            else -> null
        }
    }

    /** The drop telephony named in the reason text, if any. */
    private fun telephonyKind(reason: String?): DropKind? {
        val tokens = reason.orEmpty().uppercase().split(',', ' ', ':', ';').map { it.trim() }.toSet()
        return when {
            "WIFI_LOST" in tokens -> DropKind.WIFI_LOST
            "LOST_SIGNAL" in tokens || "CDMA_DROP" in tokens -> DropKind.LOST_SIGNAL
            "OUT_OF_SERVICE" in tokens || "POWER_OFF" in tokens -> DropKind.NO_SERVICE
            else -> null
        }
    }
}
