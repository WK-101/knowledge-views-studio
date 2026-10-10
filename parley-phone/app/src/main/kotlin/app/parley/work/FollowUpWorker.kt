package app.parley.work

import app.parley.common.NotificationIds
import app.parley.data.PhoneEnv
import app.parley.common.PhoneIdentity
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.common.circle.Promises
import app.parley.container
import app.parley.shortcuts.Shortcuts
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * The one-off "follow up in 1 week / 1 month" reminder from "Anything to remember?". Only the contact's lookup
 * key and id are stored in WorkManager's database; the name is read when it fires. If the contact is gone (or
 * became private) nothing is shown. Private on the lock screen with a neutral public version, and phone-only.
 *
 * New follow-ups go on the To call list instead (one reminder with the other calls due then, see
 * [app.parley.calls.ToCallReminders]); this worker still runs the ones set before, already in WorkManager's queue.
 */
class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val c = ctx.container
        val key = inputData.getString(KEY_LOOKUP).orEmpty()
        if (key.isEmpty()) return Result.success()
        val all = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() } ?: return Result.success()
        // The lookup key may have changed since (linked, renamed): follow it from the stored id.
        val contact = all.firstOrNull { it.lookupKey == key }
            ?: runCatching { c.contacts.currentOf(key, inputData.getLong(KEY_ID, -1).takeIf { it > 0 }) }.getOrNull()?.let { (_, now) ->
                all.firstOrNull { it.lookupKey == now }
            }
            ?: return Result.success()
        // The open promises give the reminder its context ("☐ send the photos").
        val promises = runCatching {
            c.circle.notesFor(
                key, contact.phones.flatMap { PhoneIdentity.lookupKeys(it.number, PhoneEnv.countryIso(c.appContext)) },
            ).flatMap { n -> Promises.open(n.text).map { it.text } }
        }.getOrDefault(emptyList())
        val tag = NotificationIds.followUp(contact.id)
        val code = tag.hashCode()
        val open = PrivateNotice.open(
            ctx, code, IntentRoutes.own(ctx).setAction(MainActivity.ACTION_SHOW_CALLER).putExtra(MainActivity.EXTRA_CONTACT_ID, contact.id), update = true,
        )
        val text = if (promises.isEmpty()) ctx.getString(R.string.circle_followup_body)
        else promises.take(5).joinToString("\n") { ctx.getString(R.string.circle_promise_line, it) }
        val b = PrivateNotice.builder(
            ctx, RemindersWorker.CHANNEL, R.drawable.ic_stat_cake, ctx.getString(R.string.circle_followup_title, contact.displayName),
            ctx.getString(R.string.circle_notif_public), text, open,
        )
            .setContentText(promises.firstOrNull()?.let { ctx.getString(R.string.circle_promise_line, it) } ?: ctx.getString(R.string.circle_followup_body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        contact.primaryNumber?.let { phone ->
            val call = Shortcuts.intent(ctx, Shortcuts.Kind.CALL, phone, contact.id, contact.displayName)
            b.addAction(0, ctx.getString(R.string.circle_widget_call), PendingIntent.getActivity(ctx, code + 1, call, PendingIntent.FLAG_IMMUTABLE))
        }
        PrivateNotice.post(ctx, tag, 0, b)
        return Result.success()
    }

    companion object {
        private const val KEY_LOOKUP = "lookup_key"
        private const val KEY_ID = "contact_id"

        /** One reminder per person: a newer "follow up" replaces the earlier one. */
        fun schedule(context: Context, lookupKey: String, contactId: Long?, days: Int) {
            if (lookupKey.isEmpty() || days <= 0) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                "followup:$lookupKey", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FollowUpWorker>().setInitialDelay(
                    days.toLong(), TimeUnit.DAYS,
                ).setInputData(workDataOf(KEY_LOOKUP to lookupKey, KEY_ID to (contactId ?: -1L))).build(),
            )
        }
    }
}
