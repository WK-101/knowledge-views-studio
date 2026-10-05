package app.parley.situations

import android.os.Bundle
import app.parley.IntentRoutes
import app.parley.security.LockedActivity

/**
 * A long press on one of Parley's Quick Settings tiles. Android sends it to the app for every tile it holds, so it
 * lands here whichever tile it was: the Situation tile's opens Rescue call (behind the app lock, through Parley's
 * own entry), every other tile's opens App info, as it did before ([IntentRoutes.tileLongPress]). It shows nothing
 * and is gone at once.
 */
class TileLongPressActivity : LockedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(IntentRoutes.tileLongPress(this, intent)) }
        finish()
    }
}
