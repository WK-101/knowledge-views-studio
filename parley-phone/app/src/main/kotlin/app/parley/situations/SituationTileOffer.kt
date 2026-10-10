package app.parley.situations

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import app.parley.R
import app.parley.common.ux.Tips
import app.parley.data.UxPrefs

/**
 * The first time a Situation is turned on from its row, Android is asked once to add the Situation tile to Quick
 * Settings ([Tips.offersSituationTile]): Android shows its own "Add tile?" question, and nothing happens if the tile
 * is there already. Asked once whatever the answer, so a "No" is never asked again.
 */
object SituationTileOffer {
    fun onTurnedOn(context: Context, ux: UxPrefs) {
        if (!Tips.offersSituationTile(turningOn = true, ux.state.value.seenTips, Build.VERSION.SDK_INT)) return
        ux.dismissTip(Tips.SITUATION_TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request(context)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun request(context: Context) {
        val bar = context.getSystemService(StatusBarManager::class.java) ?: return
        // An answer changes nothing here: the tile reads the Situation on now whenever it shows.
        runCatching {
            bar.requestAddTileService(
                ComponentName(context, SituationTileService::class.java),
                context.getString(R.string.sit_tile_label),
                Icon.createWithResource(context, R.drawable.ic_tile_situation),
                context.mainExecutor,
            ) { }
        }
    }
}
