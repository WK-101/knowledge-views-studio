package app.parley.blocking

import android.content.Context
import app.parley.data.DataContainer
import app.parley.telecom.RingBoost
import app.parley.telecom.ScreeningGuard

/** Wires screening's side effects at app start. Called from `ParleyApp.onCreate`. */
object BlockingSetup {
    /** Main thread, cheap: nothing here touches the disk. */
    fun install(context: Context, c: DataContainer) {
        val appContext = context.applicationContext
        c.onScreened = { e -> BlockingNotifier.onScreened(appContext, e) }
    }

    /**
     * Off the main thread: builds the screening parts and reads what the call path asks synchronously (settings,
     * rules, list state, emergency window, call-time config) so an incoming call never waits on the disk.
     */
    suspend fun warm(context: Context, c: DataContainer) {
        val appContext = context.applicationContext
        // A ring-volume boost left behind by a crash or a killed process is undone first.
        runCatching { RingBoost.restore(appContext) }
        runCatching { ScreeningGuard.inEmergencyWindow(appContext) }
        runCatching { c.screener.warm() }
        runCatching { c.calling.config.value }
        runCatching { c.peoplePrefs.current() }
        SpamListWorker.schedule(appContext)
        c.lists.watchFolder { SpamListWorker.runSoon(appContext) }
        // Label references saved by older versions (group ids) are rewritten by title.
        runCatching { c.people.labelRefs.migrate() }
    }
}
