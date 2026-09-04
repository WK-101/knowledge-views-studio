package com.cairn.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cairn.reader.work.CairnWork
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hilt provides the [HiltWorkerFactory] so that background
 * workers (feed sync, extraction, indexing) can use constructor injection.
 */
@HiltAndroidApp
class CairnApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CairnWork.schedulePeriodicSync(this)
    }
}
