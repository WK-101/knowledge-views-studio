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
                // Resolve by lookup key: the stored id may now belong to another contact.
                val id = c.contacts.resolve(t.lookupKey, t.contactId)
                if (id == null) { c.meta.clearTemporary(t.lookupKey); continue }
                if (t.purgeHistory) {
                    // Straight from the call log and the archive (Recents may never have loaded in this process),
                    // and with no undo copy: the point is that nothing stays.
                    c.contacts.details(id)?.phones?.forEach { p -> runCatching { c.history.purgeNumber(p.value) } }
                }
                if (runCatching { c.contacts.delete(listOf(id)) }.isSuccess) c.meta.clearTemporary(t.lookupKey)
            }
            // 2. Expired vault entries
            c.vault.expired(now).forEach { c.vault.delete(it) }
            // 3. Private call history
            if (settings.privateVaultHistory) c.vault.sweepCallLog(now - TimeUnit.DAYS.toMillis(30))
            // 4. Call-log retention (the archive copies new calls first and then follows the same setting,
            //    except numbers kept forever)
            runCatching { c.history.sync(full = false) }
            runCatching { c.history.applyRetention(settings.callLogRetentionDays) }
            if (settings.callLogRetentionDays > 0) {
                val before = now - TimeUnit.DAYS.toMillis(settings.callLogRetentionDays.toLong())
                runCatching {
                    c.appContext.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls.DATE} < ?", arrayOf(before.toString()))
                }
            }
            // 5. Journal older than 30 days
            c.meta.pruneJournal(now - TimeUnit.DAYS.toMillis(30))
            // 6. Daily time-machine snapshot (incremental)
            runCatching { c.timeMachine.snapshotIfDue() }
        }
    }
}
