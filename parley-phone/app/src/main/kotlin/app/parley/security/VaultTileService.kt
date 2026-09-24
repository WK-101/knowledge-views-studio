package app.parley.security

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.parley.container
import kotlinx.coroutines.launch

/** Quick Settings tile: hide/show private contacts instantly (discreet mode). */
class VaultTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render(container.settings.settings.value.hideVault)
    }

    override fun onClick() {
        super.onClick()
        // Hiding is always allowed; showing again from the lock screen needs the phone unlocked first.
        if (isLocked && container.settings.settings.value.hideVault) {
            unlockAndRun { toggle() }
            return
        }
        toggle()
    }

    private fun toggle() {
        container.scope.launch {
            val next = !container.settings.current().hideVault
            container.settings.update { it.copy(hideVault = next) }
            if (next) AppLock.lockNow()
            render(next)
        }
    }

    private fun render(hidden: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (hidden) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (hidden) "Private hidden" else "Private shown"
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = "Parley"
        tile.updateTile()
    }
}
