package app.parley.ui.situations

import app.parley.common.situations.Situation
import app.parley.common.situations.SituationState
import app.parley.data.situations.SituationsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Situations screens, the home screen's line and the "Set by …" banners may read and do, as intents: they
 * call these instead of reaching into the data container. Switching runs in the app's [scope], so leaving a screen
 * never cuts a switch in half.
 */
class SituationControls(
    private val controller: () -> SituationsController,
    /** The controller if it was built already (building it reads two preference files: not on the main thread). */
    private val ifBuilt: () -> SituationsController?,
    private val scope: CoroutineScope,
) {
    val list: StateFlow<List<Situation>> get() = controller().list
    val state: StateFlow<SituationState> get() = controller().state

    /** Whether the Situations were read already (the home line shows nothing until then). */
    val ready: Boolean get() = ifBuilt() != null

    /** Reads the Situations off the main thread, when they weren't yet. */
    suspend fun prepare() {
        if (!ready) withContext(Dispatchers.IO) { controller() }
    }

    fun turnOn(id: String, until: Long?) {
        scope.launch { controller().turnOn(id, until) }
    }

    fun turnOff() {
        scope.launch { controller().turnOff() }
    }

    fun newId(): String = controller().newId()

    /** Saves [s] (a new one, or a whole change); false when it couldn't be (too many, say). */
    suspend fun save(s: Situation): Boolean = controller().save(s)

    fun edit(id: String, f: (Situation) -> Situation) {
        scope.launch { controller().edit(id, f) }
    }

    fun delete(id: String) {
        scope.launch { controller().delete(id) }
    }
}
