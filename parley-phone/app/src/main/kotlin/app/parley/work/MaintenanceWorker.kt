package app.parley.work

import app.parley.common.catching
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import app.parley.common.history.RetentionDefaults
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.calls.ReputationLearner
import app.parley.blocking.ListsUpdaterClient
import app.parley.blocking.SpamListWorker
import app.parley.common.people.TemporaryDue
import app.parley.data.people.TemporaryContactStore
import app.parley.ui.common.ImageExport
import app.parley.ui.contact.ContactCamera
import app.parley.ui.history.ExportFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * The daily upkeep, in one run while the battery isn't low (entirely local, no network):
 * - housekeeping: temporary contacts and vault entries that expired, lookup keys that moved, private (vault) calls
 *   out of the system log, call-log retention, the 30-day journal and the time-machine snapshot;
 * - call history: the full archive catch-up, retention, old export files and the plan-meter check;
 * - screening: expired temporary allow rules, old screening traces, the subscribed spam lists, and personal
 *   reputation (what your own calls say about numbers, for the call path to look up).
 * - the sync watchdog: contacts that vanished since the last snapshot without being deleted in Parley.
 * - number memory: the keyed-hash index of what Parley knows about numbers that aren't contacts.
 *
 * One wake instead of three, each job with its own notifications as before. It doesn't wait for the phone to be idle:
 * expired temporary contacts and retention are promises that shouldn't slip by days. Reminders stay separate
 * (they fire at the hour the user chose), and so does the check shortly after each call ([HistoryWorker.checkSoon]).
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val c = ctx.container
        // A worker process starts lean: expiry notices name people and the key sweep indexes backgrounds, so wait for
        // the address book (bounded) as a running app would have it.
        withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }
        val notices = step("housekeeping") { runHousekeeping(c) }.orEmpty()
        notices.forEachIndexed { i, n -> notify(ctx, i, n) }
        // After the day's snapshot (taken in housekeeping): contacts that vanished without the user deleting them.
        step("sync watchdog") { SyncWatchdogNotice.check(ctx, c) }
        // Number memory (I1): after the journal was pruned and today's snapshot taken; only changed stores are read.
        step("number memory") { c.numberMemory.rebuild() }
        // At most one backup reminder a month while a backup is overdue.
        step("backup reminder") { BackupReminder.maybeNotify(ctx, c) }
        // Call history: the full catch-up ran above (before retention); old exports and plan warnings.
        step("export cleanup") { ExportFiles.cleanup(ctx, olderThanMillis = TimeUnit.HOURS.toMillis(1)) }
        // Contact photos taken with the camera app that no editor needs any more (Parley's cache isn't sealed).
        step("camera photos") { ContactCamera.sweep(ctx) }
        step("shared pictures") { ImageExport.sweep(ctx) }
        step("plan warnings") { HistoryWorker.warnPlans(ctx) }
        // Screening upkeep and lists from the optional "Parley Lists" app (read through its provider).
        step("screening upkeep") { SpamListWorker.run(c) }
        step("spam lists") { ListsUpdaterClient.refresh(ctx, c.lists) }
        // I2: learn again what your own calls say about numbers and ranges (after the archive caught up above).
        step("personal reputation") { ReputationLearner.learn(c) }
        return Result.success()
    }

    /** "X expired; the details you merged were kept". */
    private fun notify(ctx: Context, i: Int, n: TemporaryContactStore.Notice) {
        val name = n.name ?: ctx.getString(R.string.work_temp_someone)
        val text = ctx.getString(if (n.keptDetails) R.string.work_temp_expired_kept else R.string.work_temp_expired_merged, name)
        val title = ctx.getString(R.string.work_temp_expired_title)
        val open = PrivateNotice.open(ctx, NotificationRequests.TEMPORARY_EXPIRED, Intent(ctx, MainActivity::class.java))
        // It names someone: only the title on the lock screen.
        PrivateNotice.post(ctx, NotificationIds.TAG_TEMPORARY, i, PrivateNotice.builder(ctx, CHANNEL, R.drawable.ic_stat_cake, title, title, text, open))
    }

    companion object {
        private const val NAME = "parley-maintenance"
        private const val TAG = "ParleyMaintenance"
        private const val CHANNEL = NotificationChannels.HOUSEKEEPING

        /** The daily jobs this worker replaced; cancelled so they don't run twice. */
        private val REPLACED = listOf("parley-housekeeping", "parley-history", "parley-screening-daily")

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            REPLACED.forEach { wm.cancelUniqueWork(it) }
            wm.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build(),
            )
        }

        /**
         * One upkeep job on its own: a failure is logged and the jobs after it still run (a vault row that can't be
         * read must not stop the archive's retention, day after day).
         */
        private inline fun <T> step(name: String, block: () -> T): T? =
            runCatching(block).onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Maintenance step failed: $name", e)
            }.getOrNull()

        /**
         * Deletes the temporary contacts whose time is up at [now]: all of them, or only [onlyDevice] (lookup keys) and
         * [onlyPrivate] (vault ids) when the user confirmed those. Returns notices about ones merged into someone else.
         */
        suspend fun expireTemporaries(
            c: DataContainer,
            now: Long,
            onlyDevice: Set<String>? = null,
            onlyPrivate: Set<Long>? = null,
        ): List<TemporaryContactStore.Notice> {
            // 1. Temporary contacts: only the raw contacts Parley recorded are deleted; merged details stay.
            val notices = if (onlyDevice?.isEmpty() == true) emptyList() else step("temporary contacts") { c.temporaries.expire(now, onlyDevice) }.orEmpty()
            // 2. Expired vault entries
            //    (F5: private temporary contacts take their call history and "last messaged" entry with them)
            //    Only numbers nobody else has: not a phone contact (or unknown, without permission) and no other
            //    private contact; those keep their history.
            val expired = step("expired vault entries") { c.vault.expiredEntries(now) }.orEmpty().filter { onlyPrivate == null || it.id in onlyPrivate }
            for (v in expired) {
                // A failed delete keeps the entry, and with it the history of its numbers.
                if (step("vault delete") { c.vault.delete(v.id); true } != true) continue
                // What Parley kept about them (Circle, moments, call-screen picture) goes with them.
                step("private extras") { c.contactKeys.forget(app.parley.common.people.ContactRef.privateKey(v.id)); true }
                v.numbers.forEach { n ->
                    val otherOwner = runCatching { c.contacts.isContact(n) != false || c.vault.lookup(n) != null }.getOrDefault(true)
                    if (otherOwner) return@forEach
                    if (v.purgeHistory) runCatching { c.history.purgeNumber(n) }
                    runCatching { c.messaging.forget(n) }
                }
            }
            return notices
        }

        /** Returns notices to show about temporary contacts that were merged into someone else. */
        suspend fun runHousekeeping(c: DataContainer): List<TemporaryContactStore.Notice> {
            val now = System.currentTimeMillis()
            val settings = c.settings.current()
            // 0. Follow lookup-key changes first, so temporary entries and notes point at the right people.
            step("lookup keys") { c.contactKeys.sweep() }
            // Values stored plain while the records key couldn't be used are sealed once it can.
            step("record sealing") { if (!c.recordSealing.done) c.recordSealing.runIfNeeded() }
            // 1-2. Temporary contacts whose time is up: with "Ask before deleting temporary contacts" (the default)
            //      nothing is deleted here; one notification asks (DueTemporaries). Off, they go at once, as before.
            val notices = if (TemporaryDue.deletesWithoutAsking(settings.askBeforeDeletingTemporary)) {
                expireTemporaries(c, now)
            } else {
                step("due temporary contacts") { DueTemporaries.check(c, now) }
                emptyList()
            }
            // Deleted private contacts are kept sealed for 30 days ("Recently deleted"), then go for good.
            step("private trash") { c.privateTrash.purge(now); true }
            // 3. Private call history
            if (settings.privateVaultHistory) {
                step("private call log") { c.vault.sweepCallLog(now - TimeUnit.DAYS.toMillis(30)) }
                // Ring facts of private numbers leave no trace outside the vault either (numbers saved privately
                // after their calls rang included).
                runCatching { c.vault.allNumbers().forEach { n -> c.ringFacts.forget(n); c.callQuality.forget(n) } }
            }
            // 4. Call-log retention (the archive catches up on the whole log first and then follows the same
            //    setting, except numbers kept forever)
            step("archive catch-up") { c.history.sync(full = true) }
            step("archive retention") { c.history.applyRetention(settings.callLogRetentionDays) }
            // Private calls are never pruned by "Keep call history": they go only when deleted, with their contact, or
            // when a temporary contact expires (an older private history is often the reason a contact is private).
            // The phone's own call log is trimmed only by a limit the user chose: a default (five years on a new
            // install) applies to Parley's archive alone, since a restored or transferred call log isn't Parley's.
            val systemLogDays = RetentionDefaults.systemLogDays(settings.callLogRetentionDays, settings.callLogRetentionChosen)
            if (systemLogDays > 0) {
                val before = now - TimeUnit.DAYS.toMillis(systemLogDays.toLong())
                catching {
                    c.appContext.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls.DATE} < ?", arrayOf(before.toString()))
                }
            }
            if (settings.callLogRetentionDays > 0) {
                // The "last messaged" record is Parley's own and follows the retention.
                catching { c.messaging.pruneOlderThan(now - TimeUnit.DAYS.toMillis(settings.callLogRetentionDays.toLong())) }
            }
            // "Forget messaged numbers after" (the stricter of it and the retention above wins).
            runCatching { c.messaging.pruneExpired(settings.callLogRetentionDays, now) }
            // 5. Journal older than 30 days
            step("journal") { c.journal.prune(now - TimeUnit.DAYS.toMillis(30)) }
            // Pruning leaves free pages behind; give them back when they add up.
            step("database tidy") { c.undoStorage.tidy() }
            // 6. Daily time-machine snapshot (incremental)
            step("time machine") { c.timeMachine.snapshotIfDue() }
            return notices
        }
    }
}
