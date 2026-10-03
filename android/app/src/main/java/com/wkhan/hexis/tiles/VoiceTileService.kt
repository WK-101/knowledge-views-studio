package com.wkhan.hexis.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.wkhan.hexis.widget.QuickVoiceActivity

/**
 * A Quick Settings tile that drops you straight into voice capture from anywhere — even over the lock
 * screen shade — without opening the app. It launches the lightweight [QuickVoiceActivity] popup, which
 * itself requires the optional voice addon (and shows an install prompt if it isn't connected). Add it
 * from the notification-shade tile editor. Fully offline; the core holds no microphone permission.
 */
class VoiceTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickVoiceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
