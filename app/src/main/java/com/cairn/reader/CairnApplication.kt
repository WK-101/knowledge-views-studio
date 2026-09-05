package com.cairn.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.work.CairnWork
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Reading one value from DataStore at startup is quick; it lets the background
        // sync respect the user's Wi-Fi-only preference from the first schedule.
        val prefs = runCatching { runBlocking { preferencesRepository.preferences.first() } }.getOrNull()
        CairnWork.schedulePeriodicSync(this, prefs?.syncWifiOnly ?: false)
        CairnWork.scheduleBackup(this, prefs?.backupFrequencyHours ?: 0)
    }
}
