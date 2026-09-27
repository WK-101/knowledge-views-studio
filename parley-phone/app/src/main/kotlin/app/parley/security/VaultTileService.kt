package app.parley.security

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.parley.container
import kotlinx.coroutines.launch

/** Quick Settings tile: hide/show private contacts instantly (discreet mode). */
class VaultTileService : TileService() {
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.parley.ui.AppLocale.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        render(container.settings.settings.value.hideVault)
    }

    override fun onClick() {
        super.onClick()
        val s = container.settings.settings.value
        // Hiding is always allowed. Showing again needs the phone unlocked first and, with the app lock on,
        // Parley's own unlock too: an unlocked phone in someone else's hands mustn't reveal private names.
        if (!s.hideVault) return toggle()
        if (isLocked) unlockAndRun { reveal(s.appLock) } else reveal(s.appLock)
    }

    // The Intent overload only runs below Android 14, where it is the only one.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun reveal(appLock: Boolean) {
        if (!appLock) return toggle()
        val intent = Intent(this, DiscreetRevealActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
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
        tile.label = getString(if (hidden) app.parley.R.string.tile_private_hidden else app.parley.R.string.tile_private_shown)
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(app.parley.R.string.app_name)
        tile.updateTile()
    }
}

/** Asks for Parley's unlock, then turns discreet mode off. Invisible apart from the system prompt. Not exported. */
class DiscreetRevealActivity : androidx.fragment.app.FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        app.parley.ui.AppLocale.override(this, newBase)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AppLock.applySecureFlag(this, true)
        if (savedInstanceState != null) return
        AppLock.authenticate(this, getString(app.parley.R.string.lock_unlock_private)) { ok ->
            if (ok) {
                val c = container
                c.scope.launch {
                    c.settings.update { it.copy(hideVault = false) }
                    android.service.quicksettings.TileService.requestListeningState(applicationContext, android.content.ComponentName(applicationContext, VaultTileService::class.java))
                }
            }
            finish()
        }
    }
}
