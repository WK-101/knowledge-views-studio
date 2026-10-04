package app.parley.situations

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.parley.R
import app.parley.common.suspendRunCatching
import app.parley.container
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
        // A window or the car may have changed things since the tile was last drawn.
        c.scope.launch {
            suspendRunCatching { c.situations.reconcile() }
            render()
        }
        render()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { next() } else next()
    }

    private fun next() {
        val c = container
        val list = c.situations.list.value
        val at = list.indexOfFirst { it.id == c.situations.state.value.activeId }
        val target = list.getOrNull(at + 1)
        c.scope.launch {
            suspendRunCatching { if (target != null) c.situations.turnOn(target.id) else c.situations.turnOff() }
            render()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val active = container.situations.active
        val name = active?.let { SituationTriggers.name(this, it) }
        tile.state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.sit_tile_label)
        tile.subtitle = name ?: getString(R.string.set_off)
        tile.contentDescription = if (name != null) getString(R.string.sit_tile_on_cd, name) else getString(R.string.sit_tile_off_cd)
        tile.updateTile()
    }
}
