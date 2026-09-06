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
import com.cairn.reader.notifications.Notifier
import com.cairn.reader.widget.CairnWidgetProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Refreshes every subscribed feed (conditional GET; unchanged feeds cost nothing),
 *  then raises notifications for new items from notify-enabled feeds. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
    private val notifier: Notifier,
    private val preferencesRepository: com.cairn.reader.data.prefs.PreferencesRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching { feedRepository.syncAll() }
            .fold(
                onSuccess = { newItems ->
                    runCatching { notifier.notifyNewArticles(newItems) }
                    val prefs = runCatching { preferencesRepository.preferences.first() }.getOrNull()
                    // Broken-link watchdog reaches publishers' servers, so it only runs when the user
                    // has explicitly opted in — keeping the default posture fully offline.
                    if (prefs?.linkCheckEnabled == true) runCatching { feedRepository.checkLinks(15) }
                    // Context automation: if Commute Mode is on, pull the next batch fully offline.
                    // This sync already ran under the user's Wi-Fi/charging constraints, so the
                    // device context is right; image caching still honours the offline-image policy.
                    if (prefs?.autoOfflinePack == true) runCatching { feedRepository.prepareOfflinePack(20) }
                    CairnWidgetProvider.refresh(context)
                    Result.success()
                },
                onFailure = { Result.retry() },
            )
}

/** Writes a JSON/zip backup into the user's chosen SAF folder (and/or WebDAV), keeping the last few.
 *  The archive is plaintext by design — it stays on storage the user controls; the one secret it
 *  could carry (the WebDAV password) is deliberately excluded. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: com.cairn.reader.data.backup.BackupManager,
    private val preferencesRepository: com.cairn.reader.data.prefs.PreferencesRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = preferencesRepository.preferences.first()
        val hasFolder = prefs.backupFolderUri != null
        val hasWebDav = !prefs.webdavUrl.isNullOrBlank()
        if (!hasFolder && !hasWebDav) return Result.success()

        var anyFailed = false
        // 1) Local SAF folder (if configured).
        if (hasFolder) {
            val ok = runCatching { backupToSaf(prefs.backupFolderUri!!, prefs.backupIncludeOffline) }.getOrDefault(false)
            if (!ok) anyFailed = true
        }
        // 2) Self-hosted WebDAV / Nextcloud mirror (if configured).
        if (hasWebDav) {
            if (backupManager.backupToWebDav().isFailure) anyFailed = true
        }
        return if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun backupToSaf(uriStr: String, includeOffline: Boolean): Boolean {
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(uriStr))
        if (tree == null || !tree.canWrite()) return true // nothing writable — don't spin on retries
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        if (includeOffline) {
            // Full archive: data + every offline copy, in one .zip.
            val file = tree.createFile("application/zip", "cairn-backup-$stamp.zip") ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { backupManager.exportArchive(it) }
        } else {
            val json = backupManager.export()
            val file = tree.createFile("application/json", "cairn-backup-$stamp.json") ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { it.write(json.toByteArray()) }
        }
        // Keep only the most recent few backups (either extension).
        tree.listFiles()
            .filter { it.name?.startsWith("cairn-backup-") == true }
            .sortedBy { it.name }
            .dropLast(KEEP)
            .forEach { runCatching { it.delete() } }
        return true
    }

    companion object { private const val KEEP = 5 }
}

/** Once a day, composes the focus-ranked brief and posts a quiet "your brief is ready" nudge. */
@HiltWorker
class BriefWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val insightsRepository: com.cairn.reader.data.repo.InsightsRepository,
    private val notifier: Notifier,
    private val preferencesRepository: com.cairn.reader.data.prefs.PreferencesRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!preferencesRepository.preferences.first().dailyBriefNotify) return Result.success()
        val picks = runCatching { insightsRepository.topPicks(8) }.getOrDefault(emptyList())
        runCatching { notifier.notifyBrief(picks.size, picks.firstOrNull()?.title) }
        return Result.success()
    }
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

/** Saves shared text (e.g. a forwarded newsletter) as a Read Later item. No network needed. */
@HiltWorker
class SaveTextWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val subject = inputData.getString(KEY_SUBJECT)
        return feedRepository.saveText(subject, text)
            .fold(onSuccess = { Result.success() }, onFailure = { Result.failure() })
    }

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_SUBJECT = "subject"
    }
}

/** Entry points for scheduling background work. */
object CairnWork {
    private const val UNIQUE_PERIODIC = "cairn-periodic-sync"
    private const val UNIQUE_SYNC_NOW = "cairn-sync-now"
    private const val UNIQUE_BACKUP = "cairn-periodic-backup"
    private const val UNIQUE_BRIEF = "cairn-daily-brief"

    /** (Re)schedule the once-daily brief notification, or cancel it when [enabled] is false. */
    fun scheduleDailyBrief(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(UNIQUE_BRIEF); return }
        val request = PeriodicWorkRequestBuilder<BriefWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(4, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_BRIEF, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** (Re)schedule automatic backup every [hours], or cancel it when hours <= 0. */
    fun scheduleBackup(context: Context, hours: Int) {
        val wm = WorkManager.getInstance(context)
        if (hours <= 0) {
            wm.cancelUniqueWork(UNIQUE_BACKUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(hours.toLong(), TimeUnit.HOURS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_BACKUP, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Run a backup immediately (e.g. right after the user picks a folder). */
    fun backupNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun constraints(wifiOnly: Boolean, chargingOnly: Boolean = false) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(chargingOnly)
        .build()

    /** (Re)schedule the background sync. UPDATE lets a changed Wi-Fi-only / charging / interval
     *  preference replace the constraint on the existing work without losing its schedule.
     *  [intervalMinutes] of 0 keeps the 6-hour default; WorkManager clamps to a 15-minute minimum. */
    fun schedulePeriodicSync(
        context: Context,
        wifiOnly: Boolean = false,
        chargingOnly: Boolean = false,
        intervalMinutes: Int = 0,
    ) {
        val request = if (intervalMinutes > 0) {
            PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
        } else {
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
        }
            .setConstraints(constraints(wifiOnly, chargingOnly))
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun syncNow(context: Context) {
        // A manual sync is intentional, so it runs on any connection regardless of the Wi-Fi-only policy.
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(false))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_SYNC_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun saveUrl(context: Context, url: String) {
        val request = OneTimeWorkRequestBuilder<SaveUrlWorker>()
            .setConstraints(constraints(false))
            .setInputData(workDataOf(SaveUrlWorker.KEY_URL to url))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    /** Save shared/forwarded text (a newsletter, an email, a highlighted passage) straight into
     *  Read Later. Purely local — no network constraint, so it runs even when offline. */
    fun saveText(context: Context, subject: String?, text: String) {
        val request = OneTimeWorkRequestBuilder<SaveTextWorker>()
            .setInputData(
                workDataOf(
                    SaveTextWorker.KEY_TEXT to text,
                    SaveTextWorker.KEY_SUBJECT to subject,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
