package app.parley.common.ux

import app.parley.common.CallType

/**
 * U3: the fixed colour family of a call. Incoming, outgoing, missed and blocked calls keep the same hue everywhere
 * (Recents, number history, the contact page, insights, private contacts) whatever the wallpaper colours are.
 */
enum class CallHue {
    INCOMING, OUTGOING, MISSED, BLOCKED, NEUTRAL;

    companion object {
        fun of(type: CallType): CallHue = when (type) {
            CallType.INCOMING, CallType.ANSWERED_EXTERNALLY, CallType.VOICEMAIL -> INCOMING
            CallType.OUTGOING -> OUTGOING
            // A declined call was still a call you didn't take: it reads as missed.
            CallType.MISSED, CallType.REJECTED -> MISSED
            CallType.BLOCKED -> BLOCKED
            CallType.UNKNOWN -> NEUTRAL
        }
    }
}
