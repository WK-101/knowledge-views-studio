package com.cairn.reader.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.widget.CairnWidgetProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Refreshes every subscribed feed (conditional GET; unchanged feeds cost nothing). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching { feedRepository.syncAll() }
            .fold(
                onSuccess = { CairnWidgetProvider.refresh(context); Result.success() },
                onFailure = { Result.retry() },
            )
}

/** Saves a shared/pasted URL and extracts a clean offline copy. */
@HiltWorker
class SaveUrlWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        return feedRepository.saveUrl(url)
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val KEY_URL = "url"
    }
}

/** Entry points for scheduling background work. */
object CairnWork {
    private const val UNIQUE_PERIODIC = "cairn-periodic-sync"
    private const val UNIQUE_SYNC_NOW = "cairn-sync-now"

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(connected)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_SYNC_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun saveUrl(context: Context, url: String) {
        val request = OneTimeWorkRequestBuilder<SaveUrlWorker>()
            .setConstraints(connected)
            .setInputData(workDataOf(SaveUrlWorker.KEY_URL to url))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
