package app.parley.security

import app.parley.common.AppSettings
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.parley.R
import app.parley.container
import app.parley.ui.startOrSay
import kotlinx.coroutines.launch

/** Quick Settings tile: hide/show private contacts instantly (discreet mode). */
class VaultTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render(container.settings.settings.value.shownHidden())
    }

    override fun onClick() {
        super.onClick()
        val s = container.settings.settings.value
        // Hiding is always allowed. Showing again needs the phone unlocked first and, with the app lock on,
        // Parley's own unlock too: an unlocked phone in someone else's hands mustn't reveal private names.
        if (!s.shownHidden()) return toggle()
        if (isLocked) unlockAndRun { reveal(s.appLock) } else reveal(s.appLock)
    }

    // The Intent overload only runs below Android 14, where it is the only one.
    @SuppressLint("StartActivityAndCollapseDeprecated")
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
            val next = !container.settings.current().shownHidden()
            container.settings.update { it.copy(hideVault = next) }
            if (next) {
                // Hidden means locked too: their details close until the next unlock, as with "Lock private contacts".
                container.vault.lockAll()
                AppLock.lockNow()
            }
            render(next)
        }
    }

    /** Discreet mode as the switch is set: after a duress unlock the tile looks as it did before (I21). */
    private fun AppSettings.shownHidden() = duress?.hideVault ?: hideVault

    private fun render(hidden: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (hidden) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (hidden) R.string.tile_private_hidden else R.string.tile_private_shown)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(R.string.app_name)
        tile.updateTile()
    }
}

/** Asks for Parley's unlock, then turns discreet mode off. Invisible apart from the system prompt. Not exported. */
class DiscreetRevealActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLock.applySecureFlag(this, true)
        // Configuration changes are handled in place (manifest), so the prompt and its callback stay with this
        // instance. Anything that still recreates it (process death, a change not listed there) leaves a prompt that
        // belongs to the old instance: close rather than stay as an invisible window over everything.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val c = container
        lifecycleScope.launch {
            // M6: with a Parley PIN set (a duress PIN or not), the phone's screen lock doesn't stand in for it: while
            // Parley is locked, it opens on its own lock screen instead, and discreet mode stays on until the user
            // turns it off from there.
            if (!c.appPin.load().deviceUnlocks && AppLock.locked.value) {
                packageManager.getLaunchIntentForPackage(packageName)?.let { startOrSay(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                finish()
                return@launch
            }
            AppLock.authenticate(this@DiscreetRevealActivity, getString(R.string.lock_unlock_private)) { ok ->
                // The prompt confirmed the screen lock; that opens nothing while Parley still wants its PIN.
                if (ok && !AppLock.locked.value) {
                    c.scope.launch {
                        // While a duress unlock hides things this changes only what is shown, never the stored switch.
                        c.settings.update { it.copy(hideVault = false) }
                        TileService.requestListeningState(applicationContext, ComponentName(applicationContext, VaultTileService::class.java))
                    }
                }
                finish()
            }
        }
    }
}
