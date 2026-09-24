package app.parley.telecom

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings "End call" tile (A11): a safety net when the call screen is out of reach. It ends the active
 * call (else one being dialled, else a held one) through the same [CallManager] path as the hang-up button,
 * and never rejects a ringing call. Unavailable when there is no call.
 */
class HangUpTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        watch = s.launch { CallManager.state.collect { render(it) } }
    }

    override fun onStopListening() {
        watch?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Ending a call is allowed from the lock screen: it's what the tile is for.
        CallManager.hangupForeground()
    }

    private fun render(calls: List<CallUi>) {
        val tile = qsTile ?: return
        val target = calls.firstOrNull { it.state == CallState.ACTIVE }
            ?: calls.firstOrNull { it.state == CallState.DIALING || it.state == CallState.CONNECTING || it.state == CallState.NEW }
            ?: calls.firstOrNull { it.state == CallState.HOLDING }
        tile.state = if (target != null) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = "End call"
        tile.subtitle = target?.title ?: "No call"
        tile.contentDescription = if (target != null) "End call with ${target.title}" else "End call, no call in progress"
        tile.updateTile()
    }
}
