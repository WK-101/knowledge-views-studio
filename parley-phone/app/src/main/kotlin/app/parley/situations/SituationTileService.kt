package app.parley.situations

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.parley.R
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.situations.SituationsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: the Situation on now. Each tap goes to the next one (Off → Driving → Meeting → Night →
 * Travelling → the ones made → Off); switching one off puts back what was set before it. A Situation changes who may
 * ring and how calls are answered, so the tile never changes anything from the lock screen without unlocking.
 */
class SituationTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val c = container
        // Drawn from memory when Situations are read already; else the tile keeps what it showed until they are (read
        // off the main thread, here in a process started just for the tile).
        c.situationsIfReady()?.let(::render)
        // A window or the car may have changed things since the tile was last drawn.
        c.scope.launch(Dispatchers.IO) {
            val sit = c.situations
            suspendRunCatching { sit.reconcile() }
            render(sit)
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { next() } else next()
    }

    private fun next() {
        val c = container
        c.scope.launch(Dispatchers.IO) {
            val sit = c.situations
            val list = sit.list.value
            val at = list.indexOfFirst { it.id == sit.state.value.activeId }
            val target = list.getOrNull(at + 1)
            suspendRunCatching { if (target != null) sit.turnOn(target.id) else sit.turnOff() }
            render(sit)
        }
    }

    private fun render(sit: SituationsController) {
        val tile = qsTile ?: return
        val active = sit.active
        val name = active?.let { SituationTriggers.name(this, it) }
        tile.state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.sit_tile_label)
        tile.subtitle = name ?: getString(R.string.dc_off)
        tile.contentDescription = if (name != null) getString(R.string.sit_tile_on_cd, name) else getString(R.string.sit_tile_off_cd)
        tile.updateTile()
    }
}
