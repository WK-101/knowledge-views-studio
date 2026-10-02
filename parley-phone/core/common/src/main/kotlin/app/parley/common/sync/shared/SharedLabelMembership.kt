package app.parley.common.sync.shared

/**
 * This phone's place in a shared label, and how it changes (docs/SHARED_LABELS.md, "Removing a member, leaving").
 *
 *   Active(n) ──header with another epoch, or the key no longer opens it──▶ KeyChanged(n)
 *   KeyChanged(n) ──new invitation for epoch m ≥ n──▶ Active(m)
 *   Active(n) ──this phone removes a member──▶ Active(n + 1)        (this phone becomes the anchor)
 *   Active(n) / KeyChanged(n) ──leave──▶ Left
 */
object SharedLabelMembership {
    sealed interface State {
        data class Active(val epoch: Int) : State

        /** The label's key changed (someone was removed): nothing syncs until a new invitation is opened. */
        data class KeyChanged(val epoch: Int, val folderEpoch: Int) : State

        data object Left : State
    }

    sealed interface Event {
        /** A run read the folder's header: its [epoch], and whether this phone's key opens it. */
        data class HeaderRead(val epoch: Int, val keyOpens: Boolean) : Event

        data class InvitationOpened(val epoch: Int) : Event

        /** This phone changed the key to remove someone. */
        data object KeyRotated : Event

        data object Leave : Event
    }

    fun next(state: State, event: Event): State = when (event) {
        Event.Leave -> State.Left
        is Event.HeaderRead -> headerRead(state, event)
        is Event.InvitationOpened -> invitationOpened(state, event.epoch)
        Event.KeyRotated -> if (state is State.Active) State.Active(state.epoch + 1) else state
    }

    private fun headerRead(state: State, e: Event.HeaderRead): State = when (state) {
        is State.Active -> if (e.keyOpens && e.epoch == state.epoch) state else State.KeyChanged(state.epoch, e.epoch)
        // Back without an invitation only when the folder's key is ours again (a key change that was undone).
        is State.KeyChanged -> if (e.keyOpens && e.epoch == state.epoch) State.Active(state.epoch) else state.copy(folderEpoch = e.epoch)
        State.Left -> state
    }

    /** An invitation for an older key never replaces a newer one. */
    private fun invitationOpened(state: State, epoch: Int): State = when (state) {
        is State.Active -> if (epoch >= state.epoch) State.Active(epoch) else state
        is State.KeyChanged -> if (epoch >= state.epoch) State.Active(epoch) else state
        State.Left -> State.Active(epoch)
    }

    /** Whether a run may read and write the folder. */
    fun syncs(state: State): Boolean = state is State.Active

    /**
     * Removing [removed] from [members] (this phone is [me]): who the new anchor's journal carries, and whose
     * contact files the new anchor signs again (those last written by someone who isn't staying).
     */
    class Rotation(val carried: List<LabelMember>, private val staying: Set<String>) {
        fun resign(authorHex: String): Boolean = authorHex !in staying
    }

    fun rotation(members: List<LabelMember>, me: String, removed: Set<String>): Rotation {
        require(me !in removed) { "Leave instead of removing yourself" }
        val carried = members.filter { it.keyHex != me && it.keyHex !in removed }
        return Rotation(carried, carried.map { it.keyHex }.toSet() + me)
    }
}
