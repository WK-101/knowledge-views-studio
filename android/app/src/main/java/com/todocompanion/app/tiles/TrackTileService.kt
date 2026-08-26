package com.todocompanion.app.tiles

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.widget.TimeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Round 13 — a Quick Settings tile for time tracking. When a timer is running the tile is Active and
 * shows the activity; tap to stop. When nothing is running, tap to jump into the Time screen. Offline.
 */
class TrackTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? App ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val running = app.repository.runningTimeEntry()
            if (running != null) {
                app.repository.stopTimeTracking()
                TimeWidget.refresh(applicationContext)
                withContext(Dispatchers.Main) { refreshTile() }
            } else {
                withContext(Dispatchers.Main) { launchTime() }
            }
        }
    }

    private fun launchTime() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_time")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val app = applicationContext as? App ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val running = app.repository.runningTimeEntry()
            val name = running?.let { r -> app.repository.getTimeActivitiesOnce().firstOrNull { it.id == r.activityId }?.name }
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                if (running != null) {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = name ?: "Tracking"
                } else {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Track time"
                }
                runCatching { tile.icon = Icon.createWithResource(this@TrackTileService, android.R.drawable.ic_menu_recent_history) }
                tile.updateTile()
            }
        }
    }
}
