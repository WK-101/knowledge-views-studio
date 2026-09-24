package app.parley.messaging

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.parley.R

/**
 * Quick Settings tile (M8): "Message a number". Opens the number sheet with an empty field, a Paste chip (the
 * clipboard is read only when you tap it), the country and the messengers. The sheet isn't allowed over the lock
 * screen, so a locked phone asks to unlock first and nothing (no recent numbers, no history) shows before that.
 */
class MessageNumberTileService : TileService() {
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.parley.ui.AppLocale.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.shortcut_message_number_short)
        tile.contentDescription = getString(R.string.shortcut_message_number_long)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = MessageNumber.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** "Message a number" (M8): the empty number sheet, from the tile, the launcher shortcut and Parley's menus. */
object MessageNumber {
    const val ACTION = "app.parley.action.MESSAGE_NUMBER"

    fun intent(context: Context): Intent = Intent(context, NumberActionActivity::class.java)
        .setAction(ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
