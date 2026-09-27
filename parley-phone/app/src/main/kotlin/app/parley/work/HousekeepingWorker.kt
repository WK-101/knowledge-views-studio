package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.people.TemporaryContactStore
import java.util.concurrent.TimeUnit

/**
 * Daily local housekeeping (no network):
 * temporary contacts that expired, vault entries that expired, call-log retention,
 * moving private (vault) calls out of the system log, and pruning the 30-day journal.
 */
class HousekeepingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val notices = runHousekeeping(applicationContext.container)
        notices.forEachIndexed { i, n -> notify(applicationContext, i, n) }
        // At most one backup reminder a month while a backup is overdue.
        runCatching { BackupReminder.maybeNotify(applicationContext, applicationContext.container) }
        return Result.success()
    }

    /** "X expired; the details you merged were kept". */
    private fun notify(ctx: Context, i: Int, n: TemporaryContactStore.Notice) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.work_channel_housekeeping), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val name = n.name ?: ctx.getString(R.string.work_temp_someone)
        val text = ctx.getString(if (n.keptDetails) R.string.work_temp_expired_kept else R.string.work_temp_expired_merged, name)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cake)
            .setContentTitle(ctx.getString(R.string.work_temp_expired_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(ctx).notify(NotificationIds.TAG_TEMPORARY, i, b.build())
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val NAME = "parley-housekeeping"
        private const val CHANNEL = NotificationChannels.HOUSEKEEPING

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HousekeepingWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        /** Returns notices to show about temporary contacts that were merged into someone else. */
        suspend fun runHousekeeping(c: DataContainer): List<TemporaryContactStore.Notice> {
            val now = System.currentTimeMillis()
            val settings = c.settings.current()
            // 0. Follow lookup-key changes first, so temporary entries and notes point at the right people.
            runCatching { c.contactKeys.sweep() }
            // 1. Temporary contacts: only the raw contacts Parley recorded are deleted; merged details stay.
            val notices = runCatching { c.temporaries.expire(now) }.getOrDefault(emptyList())
            // 2. Expired vault entries
            //    (F5: private temporary contacts take their call history and "last messaged" entry with them)
            //    Only numbers nobody else has: not a phone contact (or unknown, without permission) and no other
            //    private contact; those keep their history.
            for (v in c.vault.expiredEntries(now)) {
                c.vault.delete(v.id)
                v.numbers.forEach { n ->
                    val otherOwner = runCatching { c.contacts.isContact(n) != false || c.vault.lookup(n) != null }.getOrDefault(true)
                    if (otherOwner) return@forEach
                    if (v.purgeHistory) runCatching { c.history.purgeNumber(n) }
                    runCatching { c.messaging.forget(n) }
                }
            }
            // 3. Private call history
            if (settings.privateVaultHistory) {
                c.vault.sweepCallLog(now - TimeUnit.DAYS.toMillis(30))
                // Ring facts of private numbers leave no trace outside the vault either (numbers saved privately
                // after their calls rang included).
                runCatching { c.vault.allNumbers().forEach { n -> c.ringFacts.forget(n) } }
            }
            // 4. Call-log retention (the archive copies new calls first and then follows the same setting,
            //    except numbers kept forever)
            runCatching { c.history.sync(full = false) }
            runCatching { c.history.applyRetention(settings.callLogRetentionDays) }
            if (settings.callLogRetentionDays > 0) {
                val before = now - TimeUnit.DAYS.toMillis(settings.callLogRetentionDays.toLong())
                runCatching {
                    c.appContext.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls.DATE} < ?", arrayOf(before.toString()))
                }
                // The "last messaged" record follows the same retention.
                runCatching { c.messaging.pruneOlderThan(before) }
            }
            // "Forget messaged numbers after" (the stricter of it and the retention above wins).
            runCatching { c.messaging.pruneExpired(settings.callLogRetentionDays, now) }
            // 5. Journal older than 30 days
            c.meta.pruneJournal(now - TimeUnit.DAYS.toMillis(30))
            // 6. Daily time-machine snapshot (incremental)
            runCatching { c.timeMachine.snapshotIfDue() }
            return notices
        }
    }
}
