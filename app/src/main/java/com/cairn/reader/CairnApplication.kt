package com.cairn.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cairn.reader.data.db.CairnDatabase
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.util.AppLog
import com.cairn.reader.util.orLog
import com.cairn.reader.work.CairnWork
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
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
class CairnApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** The app's hardened OkHttp client (system-CA trust only, no user-added CAs; HTTPS enforced for
     *  WebDAV), reused by Coil's image loader. Lazy so building it stays off the Hilt field-injection
     *  path. */
    @Inject
    lateinit var imageHttpClient: dagger.Lazy<OkHttpClient>

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    /** Lazy so building it (which runs the one-time at-rest encryption migration) never happens
     *  during Hilt field injection on the main thread — we warm it explicitly on a background
     *  dispatcher below. */
    @Inject
    lateinit var database: dagger.Lazy<CairnDatabase>

    /** Lazy so the one-time canonicalUrl backfill runs off the injection path, on a background scope. */
    @Inject
    lateinit var feedRepository: dagger.Lazy<FeedRepository>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Coil 3's singleton image loader, wired to reuse the app's cert-pinned OkHttp client. Coil 3
     * loads network images only when the coil-network-okhttp artifact provides a fetcher; wiring it
     * explicitly also keeps image traffic on the same hardened client as the rest of the app. The
     * diagnostics line confirms on-device that the Coil 3 pipeline initialized.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        AppLog.diag("Coil3 ImageLoader init (OkHttp network fetcher wired)")
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient.get() })) }
            // Hard-cap the read-through image cache so it can't balloon (Coil's default is a % of free
            // disk, which on a large device is huge). Same directory the storage dashboard accounts for.
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // Run the one-time plaintext→encrypted DB migration on a background thread started as early as
        // possible, so it finishes (or is well underway) before Hilt resolves the DB — the provider
        // only waits for its result and never runs the migration on the main thread. A plain Thread
        // (not a dispatcher) starts promptly, before the first ViewModel can resolve a DAO.
        Thread { runCatching { com.cairn.reader.data.db.DbCrypto.prepare(this) } }
            .apply { name = "db-encrypt-prepare"; start() }
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
            // One-time: canonicalize URLs of items captured before dedup canonicalization existed, so
            // the Duplicates view keys uniformly. No-ops once every item has a canonicalUrl.
            runCatching { feedRepository.get().backfillCanonicalUrls() }.orLog("canonicalUrl backfill")
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
            // Local upkeep (retention pruning + trash auto-purge) runs on its own daily schedule,
            // independent of feed sync, so it still happens for users with no feeds or sync off.
            CairnWork.scheduleMaintenance(this@CairnApplication)
        }
    }
}
