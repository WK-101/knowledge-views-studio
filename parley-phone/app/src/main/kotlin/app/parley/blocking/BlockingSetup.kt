package app.parley.blocking

import android.content.Context
import app.parley.data.DataContainer
import app.parley.telecom.RingBoost

/** Wires screening's side effects at app start. Called from `ParleyApp.onCreate`. */
object BlockingSetup {
    fun install(context: Context, c: DataContainer) {
        val appContext = context.applicationContext
        // A ring-volume boost left behind by a crash or a killed process is undone first.
        runCatching { RingBoost.restore(appContext) }
        c.screener.onScreened = { e ->
            BlockingNotifier.onScreened(appContext, e)
        }
        SpamListWorker.schedule(appContext)
        c.lists.watchFolder { SpamListWorker.runSoon(appContext) }
    }
}
