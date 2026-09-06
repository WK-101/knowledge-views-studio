package com.cairn.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cairn.reader.data.db.CairnDatabase
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.util.AppLog
import com.cairn.reader.util.orLog
import com.cairn.reader.work.CairnWork
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. Hilt provides the [HiltWorkerFactory] so that background
 * workers (feed sync, extraction, indexing) can use constructor injection.
 */
@HiltAndroidApp
class CairnApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    /** Lazy so building it (which runs the one-time at-rest encryption migration) never happens
     *  during Hilt field injection on the main thread — we warm it explicitly on a background
     *  dispatcher below. */
    @Inject
    lateinit var database: dagger.Lazy<CairnDatabase>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // Record any uncaught exception (with a breadcrumb) to Logcat and the local diagnostics log
        // before the platform's default handler runs, so a crash leaves a trace instead of vanishing.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.e("Uncaught on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        // Open the encrypted database — and run the one-time plaintext→encrypted migration on first
        // launch after upgrade — on a background (IO) thread, so the first screen that touches the DB
        // doesn't pay that cost on the main thread during cold start. Room refuses main-thread queries
        // anyway; this also moves the heavier build/migration step off it.
        appScope.launch(Dispatchers.IO) {
            runCatching { database.get().openHelper.writableDatabase }.orLog("database warm-up")
        }
        // Read the sync/backup preferences off the main thread, then schedule work. Scheduling is
        // idempotent (KEEP/REPLACE policies), so doing it a beat after launch is fine and keeps cold
        // start off the DataStore read.
        appScope.launch {
            val prefs = runCatching { preferencesRepository.preferences.first() }.orLog("startup prefs read")
            CairnWork.schedulePeriodicSync(
                this@CairnApplication,
                wifiOnly = prefs?.syncWifiOnly ?: false,
                chargingOnly = prefs?.syncChargingOnly ?: false,
                intervalMinutes = prefs?.syncIntervalMinutes ?: 0,
            )
            CairnWork.scheduleBackup(this@CairnApplication, prefs?.backupFrequencyHours ?: 0)
        }
    }
}
