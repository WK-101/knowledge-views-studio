package app.parley.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.filter

/**
 * Holds the full data graph back in a process that started for something small: an incoming call, a worker, a
 * widget or a broadcast. Such a process needs settings, rules and a contact lookup, not the whole address book, the
 * call log, the decrypted archive and the indexes built over them, which would compete with call screening for the
 * CPU and the disk.
 *
 * Flows shared with [sharing] start when the gate opens (the UI started, or a call settled) or when anything
 * subscribes to them, whichever comes first, and then stay hot: several call sites read their `.value` directly.
 * Background upkeep that only makes sense for a running app (key sweeps, the widget observer, the archive's live
 * mirror) waits for [await].
 */
class StartGate {
    private val open = MutableStateFlow(false)

    /** True once the full graph was asked for. */
    val opened: StateFlow<Boolean> = open.asStateFlow()

    val isOpen: Boolean get() = open.value

    /** Idempotent. */
    fun open() {
        open.value = true
    }

    suspend fun await() {
        open.first { it }
    }

    /** Starts on the first subscriber or when the gate opens; never stops. */
    val sharing: SharingStarted = SharingStarted { subscribers ->
        combine(subscribers, open) { n, o -> n > 0 || o }
            .filter { it }
            .take(1)
            .map { SharingCommand.START }
    }
}
