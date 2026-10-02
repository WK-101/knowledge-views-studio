package app.parley.common.calls

/**
 * What TalkBack says by itself when a call changes state (P16), so someone who can't see the screen hears that the
 * call was answered, put on hold or ended without exploring for the status pill. Only changes are spoken: the state
 * the screen opens in is read with the screen, and the running timer never is.
 */
object CallAnnouncements {
    /** The call's state as far as announcements go. */
    enum class Phase { RINGING, DIALLING, ACTIVE, HOLDING, ENDED, OTHER }

    enum class Say { CONNECTED, ON_HOLD, RESUMED, ENDED }

    /** What to say when the same call goes from [before] (null: just shown) to [now]; null for nothing. */
    fun on(before: Phase?, now: Phase): Say? = when {
        before == null || before == now -> null
        now == Phase.ENDED -> Say.ENDED
        now == Phase.HOLDING -> Say.ON_HOLD
        now == Phase.ACTIVE && before == Phase.HOLDING -> Say.RESUMED
        now == Phase.ACTIVE && before != Phase.ENDED -> Say.CONNECTED
        else -> null
    }
}
