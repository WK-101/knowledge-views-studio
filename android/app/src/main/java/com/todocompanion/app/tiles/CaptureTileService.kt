package com.todocompanion.app.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.todocompanion.app.MainActivity

/**
 * A Quick Settings tile that drops you straight into quick-add — capture a task from anywhere,
 * without even opening the app. Add it from the notification-shade tile editor. Fully offline:
 * it just launches our own activity with the quick-add action.
 */
class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_QUICK_ADD)
        }
        // Android 14 removed the Intent overload of startActivityAndCollapse; use a PendingIntent there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
