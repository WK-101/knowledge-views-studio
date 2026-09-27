package app.parley.common.calls

/** Telecom's disconnect cause, without the Android types. */
enum class EndCode { LOCAL, REMOTE, BUSY, ERROR, RESTRICTED, CANCELED, OTHER, MISSED, REJECTED, UNKNOWN }

/** Why an outgoing call didn't go through. */
enum class FailureKind { AIRPLANE_MODE, NO_SIM_SELECTED, BUSY, OTHER }

/** What is known when a call leaves Telecom. */
data class EndFacts(
    val outgoing: Boolean,
    val connected: Boolean,
    val code: EndCode?,
    /** The call ended while it was still asking which SIM to use. */
    val endedInSimPicker: Boolean,
    /** The user hung up or cancelled it themselves. */
    val userEnded: Boolean,
    val airplaneMode: Boolean,
    val emergency: Boolean,
    val hasNumber: Boolean,
)

/**
 * An outgoing call that never connected and that the user didn't end gets a failure banner with the reason
 * and Retry. Only a real error counts: a call the other side declined isn't a "failure" of the phone, and a LOCAL
 * or CANCELED end is a hang-up from somewhere (the power button, a headset, a car kit, a watch), never a failure.
 * BUSY is, since it's worth retrying.
 */
object CallFailure {
    fun classify(f: EndFacts): FailureKind? {
        if (!f.outgoing || f.connected || f.emergency || !f.hasNumber || f.userEnded) return null
        val kind = when (f.code ?: return null) {
            EndCode.BUSY -> FailureKind.BUSY
            EndCode.ERROR, EndCode.RESTRICTED, EndCode.OTHER, EndCode.UNKNOWN -> FailureKind.OTHER
            // LOCAL, CANCELED: ended on this phone (any surface); REMOTE, REJECTED, MISSED: the other side.
            else -> return null
        }
        if (f.endedInSimPicker) return FailureKind.NO_SIM_SELECTED
        if (f.airplaneMode) return FailureKind.AIRPLANE_MODE
        return kind
    }
}
