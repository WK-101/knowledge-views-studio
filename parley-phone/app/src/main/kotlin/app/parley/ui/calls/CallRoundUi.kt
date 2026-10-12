package app.parley.ui.calls

import app.parley.common.calls.CallRound
import app.parley.common.calls.CallRounds
import app.parley.common.calls.RoundMark
import app.parley.common.calls.RoundPerson
import app.parley.common.catching
import app.parley.common.security.PrivacyView
import app.parley.data.calls.CallRoundStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The call round, for its screen and the ways into it: intents, not the store. Marks and the end of a round are written
 * in the app's [scope], so leaving the screen never loses one. Private contacts in the round show only while private
 * contacts may ([CallRounds.shown]); what is stored keeps them.
 */
class CallRoundUi(
    private val store: () -> CallRoundStore,
    private val privacy: StateFlow<PrivacyView>,
    private val scope: CoroutineScope,
) {
    /** People chosen in Contacts ("Start a call round"), waiting on the round's screen for the one thing to say. */
    val chosen = MutableStateFlow<List<RoundPerson>>(emptyList())

    /** The round as it may show now (none until [load] ran). */
    val shown: Flow<CallRound> get() = combine(store().state, privacy) { r, p -> CallRounds.shown(r, p.privateShown) }

    suspend fun load() {
        catching { store().load() }
    }

    /** Starts a round telling [people] [message]; false when there is nothing to say, nobody to call, or it can't be kept. */
    suspend fun start(message: String, people: List<RoundPerson>): Boolean {
        val visible = if (privacy.value.privateShown) people else people.filterNot { it.private }
        val round = CallRounds.start(message, visible, System.currentTimeMillis()) ?: return false
        val saved = catching { store().update { round } }.getOrNull() != null
        if (saved) chosen.value = emptyList()
        return saved
    }

    fun mark(key: String, mark: RoundMark) {
        scope.launch { catching { store().update { CallRounds.mark(it, key, mark) } } }
    }

    fun remove(key: String) {
        scope.launch { catching { store().update { CallRounds.remove(it, key) } } }
    }

    /** Ends the round: nothing of it is kept. */
    fun clear() {
        scope.launch { catching { store().clear() } }
    }
}
