package app.parley.telecom.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one scope for writes started from the call screen (agenda ticks, menu memory, case-file references): they
 * outlive the card or dialog that started them, since the call may end meanwhile. A failed write doesn't cancel the
 * others. Created on first use, so code that never touches it needs no main dispatcher.
 */
internal object CallScreenWrites {
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}
