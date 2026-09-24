package app.parley.work

import android.content.Context
import android.provider.CallLog
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.container
import app.parley.data.DataContainer
import java.util.concurrent.TimeUnit

/**
 * Daily local housekeeping (no network):
 * temporary contacts that expired, vault entries that expired, call-log retention,
 * moving private (vault) calls out of the system log, and pruning the 30-day journal.
 */
class HousekeepingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runHousekeeping(applicationContext.container)
        return Result.success()
    }

    companion object {
        private const val NAME = "parley-housekeeping"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HousekeepingWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        suspend fun runHousekeeping(c: DataContainer) {
            val now = System.currentTimeMillis()
            val settings = c.settings.current()
            // 1. Temporary contacts
            for (t in c.meta.expiredContacts(now)) {
                if (t.purgeHistory) {
                    c.contacts.details(t.contactId)?.phones?.forEach { p -> runCatching { c.callLog.deleteForNumber(p.value) } }
                }
                runCatching { c.contacts.delete(listOf(t.contactId)) }
                c.meta.clearTemporary(t.lookupKey)
            }
            // 2. Expired vault entries
            c.vault.expired(now).forEach { c.vault.delete(it) }
            // 3. Private call history
            if (settings.privateVaultHistory) c.vault.sweepCallLog(now - TimeUnit.DAYS.toMillis(30))
            // 4. Call-log retention
            if (settings.callLogRetentionDays > 0) {
                val before = now - TimeUnit.DAYS.toMillis(settings.callLogRetentionDays.toLong())
                runCatching {
                    c.appContext.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls.DATE} < ?", arrayOf(before.toString()))
                }
            }
            // 5. Journal older than 30 days
            c.meta.pruneJournal(now - TimeUnit.DAYS.toMillis(30))
        }
    }
}
