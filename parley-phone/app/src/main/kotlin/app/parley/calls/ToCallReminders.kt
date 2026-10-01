package app.parley.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationPrivacy
import app.parley.common.PhoneIdentity
import app.parley.common.calls.SettlingCall
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallItem
import app.parley.common.calls.ToCallSource
import app.parley.common.calls.ToCallState
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.data.history.CallHistory
import app.parley.shortcuts.Shortcuts
import app.parley.ui.Bidi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The "To call" list's writes and its one reminder (L1, P2, P3, I9). Every change goes through [update], which
 * reschedules the reminder when the list changed: one inexact WorkManager job for the next item due (no exact-alarm
 * permission). The worker never replaces itself: it posts, marks the items shown, then schedules the next run. When it
 * runs, every item due then shows in one quiet notification (names only after unlocking; a private contact's name
 * never in discreet mode), with no badge and no repeat.
 */
object ToCallReminders {
    private const val WORK = "to_call_reminder"
    const val ACTION_NOT_NOW = "app.parley.TO_CALL_NOT_NOW"
    const val EXTRA_KEYS = "keys"

    /** Where the reminder finds the app's stores (tests use their own). */
    @VisibleForTesting
    internal var containerOf: (Context) -> DataContainer = { it.container }

    /**
     * True while [ToCallWorker] runs: a change made meanwhile doesn't reschedule (REPLACE would cancel the running
     * worker before it posts); the worker schedules the next run itself, from the list as it is when it ends.
     */
    @Volatile private var workerRunning = false

    /**
     * Puts [number] on the list for [at]. False when it has no number to call, or the list can't be read right now
     * (nothing was set then).
     */
    suspend fun remind(
        context: Context, number: String, accountId: String?, at: Long, source: ToCallSource = ToCallSource.REMINDER,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val iso = PhoneEnv.countryIso(context, accountId)
        val key = PhoneIdentity.key(number, iso)
        if (!canRemind(key, number)) return false
        // Their time zone, when the number tells it (offline), for "after 6 pm their time".
        val zone = runCatching { NumberInfo.timeZone(number, iso)?.id }.getOrNull()
        update(context) { ToCall.remind(it, key, number, at, now, source, zone = zone, accountId = accountId) }
        // I7: a number nobody saved may call back before then (asked once; off until accepted).
        runCatching { ExpectedCallHints.toCallAdded(containerOf(context), number, at, now) }
        return containerOf(context).toCall.available
    }

    /** Whether [number] (its identity [key]) can go on the list: something to call. */
    fun canRemind(key: String, number: String): Boolean = key.isNotEmpty() && number.any { it.isDigit() }

    /** Applies [f] to the list, writes it and, when it changed, reschedules the reminder. */
    suspend fun update(context: Context, f: (ToCallState) -> ToCallState): ToCallState {
        val w = containerOf(context).toCall.write(f)
        if (w.changed && !workerRunning) schedule(context, w.state)
        return w.state
    }

    /**
     * The one reminder job, for the next item due; none when nothing waits. From outside the worker it replaces the
     * job waiting; [fromWorker] appends to the running one instead (it starts once that one has finished), so the
     * worker never cancels itself.
     */
    fun schedule(context: Context, state: ToCallState, now: Long = System.currentTimeMillis(), fromWorker: Boolean = false) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val next = ToCall.nextAlarm(state, now)
        if (next == null) {
            // The running worker ends by itself; cancelling it here would cut it short.
            if (!fromWorker) wm.cancelUniqueWork(WORK)
            return
        }
        wm.enqueueUniqueWork(
            WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ToCallWorker>().setInitialDelay((next - now).coerceAtLeast(0), TimeUnit.MILLISECONDS).build(),
        )
    }

    /**
     * The worker's run: settles items already called back, shows what is due in one notification, marks it shown and
     * only then schedules the next run, once. False when the list can't be read right now (the worker retries later).
     */
    suspend fun notifyDue(context: Context): Boolean {
        val c = containerOf(context)
        workerRunning = true
        try {
            val now = System.currentTimeMillis()
            c.toCall.load()
            if (!c.toCall.available) return false
            settle(context, c, now)
            val due = ToCall.toNotify(c.toCall.state.value, now)
            if (due.isNotEmpty()) {
                post(context, c, due)
                c.toCall.write { ToCall.markNotified(it, due.map { i -> i.key }.toSet()) }
            }
        } finally {
            workerRunning = false
        }
        // From the list as it is now, so a change made while the worker ran is included.
        schedule(context, c.toCall.state.value, fromWorker = true)
        return true
    }

    /** Items whose person was called (or talked to) since they were set go. */
    private suspend fun settle(context: Context, c: DataContainer, now: Long) {
        val items = c.toCall.state.value.items
        if (items.isEmpty()) return
        val loaded = c.history.calls.value
        // Calls with private contacts live in Parley's own history ("Private call history").
        val private = runCatching { c.vault.privateCalls.value.map(CallHistory::privateEntry) }.getOrDefault(emptyList())
        val calls = items.flatMap { item ->
            val iso = PhoneEnv.countryIso(context, item.accountId)
            val system = loaded ?: runCatching { c.callLog.pastCalls(item.number, now, limit = 20) }.getOrDefault(emptyList())
            (system + private).filter { ToCall.settles(it) && PhoneIdentity.same(it.number, item.number, iso) }.map { SettlingCall(item.key, it.date) }
        }
        // Inside the worker: the one schedule at its end covers this change.
        c.toCall.write { ToCall.settle(it, calls, now) }
    }

    private suspend fun post(context: Context, c: DataContainer, due: List<ToCallItem>) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NotificationChannels.TO_CALL, context.getString(R.string.to_call_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setShowBadge(false) },
        )
        val hideVault = c.settings.current().hideVault
        val names = due.map { nameOf(c, it.number, hideVault) }
        val public = NotificationCompat.Builder(context, NotificationChannels.TO_CALL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.to_call_notif_public))
            .build()
        val keys = due.map { it.key }.toTypedArray()
        val open = PendingIntent.getActivity(
            context, 70,
            Intent(context, MainActivity::class.java).setAction(IntentRoutes.ACTION_SHOW_TO_CALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notNow = PendingIntent.getBroadcast(
            context, 71,
            Intent(context, ToCallActionReceiver::class.java).setAction(ACTION_NOT_NOW).putExtra(EXTRA_KEYS, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val one = due.singleOrNull()
        val b = NotificationCompat.Builder(context, NotificationChannels.TO_CALL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setNumber(0)
            .setContentIntent(open)
        if (one != null) {
            b.setContentTitle(context.getString(R.string.to_call_notif_one, names[0]))
                .setContentText(
                    context.getString(if (one.source == ToCallSource.FOLLOW_UP) R.string.to_call_notif_follow_up else R.string.to_call_notif_reminder),
                )
                .addAction(
                    0, context.getString(R.string.to_call_call),
                    PendingIntent.getActivity(
                        context, 72, Shortcuts.intent(context, Shortcuts.Kind.CALL, one.number, null),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
        } else {
            val title = context.resources.getQuantityString(R.plurals.to_call_notif_many, due.size, due.size)
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
            names.take(6).forEach { inbox.addLine(it) }
            b.setContentTitle(title).setContentText(names.joinToString(", ")).setStyle(inbox)
        }
        b.addAction(0, context.getString(R.string.to_call_not_now), notNow)
        try {
            NotificationManagerCompat.from(context).notify(NotificationIds.TAG_TO_CALL, NotificationIds.TO_CALL_ID, b.build())
        } catch (_: SecurityException) {
        }
    }

    /** A contact's name, a private contact's (never in discreet mode), or the number. */
    private suspend fun nameOf(c: DataContainer, number: String, hideVault: Boolean): String {
        val contact = runCatching { c.contacts.lookup(number)?.name }.getOrNull()
        val vault = if (contact == null) runCatching { c.vault.lookup(number)?.second?.name }.getOrNull() else null
        val name = NotificationPrivacy.missedCallName(contact, vault, hideVault, number) ?: number
        return if (name == number) Bidi.ltr(number) else name
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NotificationIds.TAG_TO_CALL, NotificationIds.TO_CALL_ID)
    }
}

/** Shows the To call reminder when it's due. */
class ToCallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A list that can't be read right now (a Keystore hiccup) is tried again later, never taken for an empty one.
        val done = try {
            ToCallReminders.notifyDue(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        return if (done) Result.success() else Result.retry()
    }
}

/** "Not now" on the To call reminder: the same calls come back in an hour, once. Not exported. */
class ToCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ToCallReminders.ACTION_NOT_NOW) return
        val keys = intent.getStringArrayExtra(ToCallReminders.EXTRA_KEYS)?.toSet().orEmpty()
        val pending = goAsync()
        context.container.scope.launch {
            try {
                ToCallReminders.cancelNotification(context)
                runCatching { ToCallReminders.update(context) { ToCall.notNow(it, keys, System.currentTimeMillis()) } }
            } finally {
                pending.finish()
            }
        }
    }
}
