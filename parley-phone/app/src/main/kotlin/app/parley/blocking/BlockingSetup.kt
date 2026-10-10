package app.parley.blocking

import app.parley.common.catching
import android.content.Context
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.telecom.RingBoost
import app.parley.telecom.ScreeningGuard

/** Wires screening's side effects at app start. Called from `ParleyApp.onCreate`. */
object BlockingSetup {
    /** Main thread, cheap: nothing here touches the disk. */
    fun install(context: Context, c: DataContainer) {
        val appContext = context.applicationContext
        c.onScreened = { e -> BlockingNotifier.onScreened(appContext, c, e) }
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
        // Expected-call windows: read now so screening never opens the Keystore while a call rings.
        runCatching { c.familySafety.load() }
        // Caller location for unknown callers (the call screen) and Recents.
        runCatching { NumberInfo.warm(PhoneEnv.countryIso(appContext)) }
        runCatching { c.calling.config.value }
        runCatching { c.peoplePrefs.current() }
    }

    /**
     * Once the full app starts (the UI, or after a call): upkeep the call path doesn't need. Label references reach
     * the people graph, which a process started for a call shouldn't build.
     */
    suspend fun warmLater(context: Context, c: DataContainer) {
        val appContext = context.applicationContext
        c.lists.watchFolder { SpamListWorker.runSoon(appContext) }
        // Label references saved by older versions (group ids) are rewritten by title.
        runCatching { c.people.labelRefs.migrate() }
        catching { foldLockScreenNotes(c) }
    }

    /**
     * The older "Notes on the lock screen" switch (a Circle setting) folds into "Caller on the lock screen": on with
     * names shown, it becomes "Name and notes"; then it is off for good. Also after restoring an older backup.
     */
    suspend fun foldLockScreenNotes(c: DataContainer) {
        if (!c.circle.config.value.memoryOnLockScreen) return
        c.settings.update { it.withLockScreenNotes(notesSwitch = true) }
        c.circle.updateConfig { it.copy(memoryOnLockScreen = false) }
    }
}
