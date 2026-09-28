package app.parley.common.calls

/** A button of the in-call screen. */
enum class CallControl { MUTE, KEYPAD, AUDIO, HOLD, ADD_CALL, MERGE, SWAP, MANAGE, MORE }

/**
 * Which buttons the in-call grid shows and which go to its "More" sheet (see docs/CALL_SCREEN_DESIGN.md).
 *
 * The grid is always six buttons in the same places, so muscle memory works on every call: Mute, Keypad, Audio on
 * the first row; Hold, the one thing to do with several calls, More on the second. That fifth button is Merge, then
 * Swap, then Manage (a conference), then Add call, whichever the call allows first; the others go to the top of the
 * More sheet, so nothing the call can do is lost. A button the call doesn't allow stays in place, disabled, rather
 * than leaving a hole or shifting its neighbours.
 */
object CallControls {
    data class Caps(
        val canMute: Boolean,
        val canHold: Boolean,
        val canMerge: Boolean,
        /** The call can be swapped, or another call is on hold. */
        val canSwap: Boolean,
        val isConference: Boolean,
        /** Other live calls besides this one. */
        val otherCalls: Int,
        /** The call reports at least one audio route. */
        val hasAudioRoutes: Boolean,
    )

    data class Slot(val control: CallControl, val enabled: Boolean)

    data class Layout(val grid: List<Slot>, val more: List<Slot>)

    const val COLUMNS = 3

    fun layout(caps: Caps): Layout {
        val multi = buildList {
            if (caps.canMerge) add(CallControl.MERGE)
            if (caps.canSwap) add(CallControl.SWAP)
            if (caps.isConference) add(CallControl.MANAGE)
            add(CallControl.ADD_CALL)
        }
        val grid = listOf(
            Slot(CallControl.MUTE, caps.canMute),
            Slot(CallControl.KEYPAD, true),
            Slot(CallControl.AUDIO, caps.hasAudioRoutes),
            Slot(CallControl.HOLD, caps.canHold),
            slot(multi.first(), caps),
            Slot(CallControl.MORE, true),
        )
        return Layout(grid, multi.drop(1).map { slot(it, caps) })
    }

    // A third call only while the two calls can still be merged or swapped (Telecom refuses it otherwise).
    private fun slot(control: CallControl, caps: Caps) = Slot(
        control,
        if (control == CallControl.ADD_CALL) caps.otherCalls == 0 || caps.canMerge || caps.canSwap else true,
    )
}
