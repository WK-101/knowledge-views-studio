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
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.parley.ui.AppLocale.wrap(newBase))
    }

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
        tile.label = getString(R.string.tile_end_call)
        tile.subtitle = target?.title ?: getString(R.string.tile_no_call)
        tile.contentDescription = if (target != null) getString(R.string.tile_end_call_with, target.title) else getString(R.string.tile_end_call_none)
        tile.updateTile()
    }
}
