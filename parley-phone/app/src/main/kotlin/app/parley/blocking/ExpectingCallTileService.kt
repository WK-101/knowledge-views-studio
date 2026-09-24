package app.parley.blocking

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.parley.container
import kotlinx.coroutines.launch

/**
 * Quick Settings tile (B21): "Expecting a call". Each tap cycles off → 30 min → 1 h → 2 h → off, letting
 * unknown callers ring through your screening rules until it runs out on its own.
 */
class ExpectingCallTileService : TileService() {
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.parley.ui.AppLocale.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        // Letting unknown callers through is a screening change: never from the lock screen without unlocking.
        if (isLocked) unlockAndRun { cycle() } else cycle()
    }

    private fun cycle() {
        val c = container
        val remaining = BlockingActions.snoozeRemaining(c)
        val next = when {
            remaining <= 0 -> 30
            remaining <= 30 * 60_000L -> 60
            remaining <= 60 * 60_000L -> 120
            else -> 0
        }
        c.scope.launch {
            BlockingActions.snooze(c, next)
            render()
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val remaining = BlockingActions.snoozeRemaining(container)
        tile.state = if (remaining > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Expecting a call"
        tile.subtitle = if (remaining > 0) "Unknown callers ring · ${formatLeft(remaining)}" else "Off"
        tile.contentDescription = if (remaining > 0) "Expecting a call, ${formatLeft(remaining)} left" else "Expecting a call, off"
        tile.updateTile()
    }

    companion object {
        fun formatLeft(ms: Long): String {
            val min = ((ms + 59_999) / 60_000).toInt()
            return if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')} min" else "$min min"
        }

        fun refresh(context: Context) {
            try {
                TileService.requestListeningState(context, ComponentName(context, ExpectingCallTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
