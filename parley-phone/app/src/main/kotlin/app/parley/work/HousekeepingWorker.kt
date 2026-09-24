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
        val notices = runHousekeeping(applicationContext.container)
        notices.forEachIndexed { i, n -> notify(applicationContext, i, n) }
        return Result.success()
    }

    /** "X expired; the details you merged were kept" (F2). */
    private fun notify(ctx: Context, i: Int, n: app.parley.data.people.TemporaryContactStore.Notice) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, ctx.getString(app.parley.R.string.work_channel_housekeeping), android.app.NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(
            ctx, 0, android.content.Intent(ctx, app.parley.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val name = n.name ?: ctx.getString(app.parley.R.string.work_temp_someone)
        val text = ctx.getString(if (n.keptDetails) app.parley.R.string.work_temp_expired_kept else app.parley.R.string.work_temp_expired_merged, name)
        val b = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(app.parley.R.drawable.ic_stat_cake)
            .setContentTitle(ctx.getString(app.parley.R.string.work_temp_expired_title))
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify("temporary", i, b.build())
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val NAME = "parley-housekeeping"
        private const val CHANNEL = "contacts_housekeeping_v1"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HousekeepingWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        /** Returns notices to show about temporary contacts that were merged into someone else. */
        suspend fun runHousekeeping(c: DataContainer): List<app.parley.data.people.TemporaryContactStore.Notice> {
            val now = System.currentTimeMillis()
            val settings = c.settings.current()
            // 0. Follow lookup-key changes first, so temporary entries and notes point at the right people.
            runCatching { c.contactKeys.sweep() }
            // 1. Temporary contacts: only the raw contacts Parley recorded are deleted; merged details stay (F2).
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
                // Ring facts (V9) of private numbers leave no trace outside the vault either (numbers saved privately
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
                // F13: the "last messaged" record follows the same retention.
                runCatching { c.messaging.pruneOlderThan(before) }
            }
            // M10: "Forget messaged numbers after" (the stricter of it and the retention above wins).
            runCatching { c.messaging.pruneExpired(settings.callLogRetentionDays, now) }
            // 5. Journal older than 30 days
            c.meta.pruneJournal(now - TimeUnit.DAYS.toMillis(30))
            // 6. Daily time-machine snapshot (incremental)
            runCatching { c.timeMachine.snapshotIfDue() }
            return notices
        }
    }
}
