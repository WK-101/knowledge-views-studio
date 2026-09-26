package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.parley.MainActivity
import app.parley.R
import app.parley.common.PhoneNumbers
import app.parley.common.circle.Promises
import app.parley.container
import app.parley.shortcuts.Shortcuts
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * R8: the one-off "follow up in 1 week / 1 month" reminder from "Anything to remember?". Only the contact's lookup
 * key and id are stored in WorkManager's database; the name is read when it fires. If the contact is gone (or
 * became private) nothing is shown. Private on the lock screen with a neutral public version, and phone-only.
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
            ?: runCatching { c.contacts.currentOf(key, inputData.getLong(KEY_ID, -1).takeIf { it > 0 }) }.getOrNull()?.let { (_, now) -> all.firstOrNull { it.lookupKey == now } }
            ?: return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(RemindersWorker.CHANNEL, ctx.getString(R.string.work_channel_reminders), NotificationManager.IMPORTANCE_DEFAULT))
        // The open promises give the reminder its context ("☐ send the photos").
        val promises = runCatching {
            c.circle.notesFor(key, contact.phones.map { PhoneNumbers.matchKey(it.number) }).flatMap { n -> Promises.open(n.text).map { it.text } }
        }.getOrDefault(emptyList())
        val tag = "followup:${contact.id}"
        val code = tag.hashCode()
        val public = NotificationCompat.Builder(ctx, RemindersWorker.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cake)
            .setContentTitle(ctx.getString(R.string.circle_notif_public))
            .build()
        val open = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CALLER).putExtra(MainActivity.EXTRA_CONTACT_ID, contact.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, RemindersWorker.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cake)
            .setContentTitle(ctx.getString(R.string.c2_followup_title, contact.displayName))
            .setContentText(promises.firstOrNull()?.let { ctx.getString(R.string.c2_promise_line, it) } ?: ctx.getString(R.string.c2_followup_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (promises.isEmpty()) ctx.getString(R.string.c2_followup_body) else promises.take(5).joinToString("\n") { ctx.getString(R.string.c2_promise_line, it) }))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
        (contact.phones.firstOrNull { it.isPrimary } ?: contact.phones.firstOrNull())?.number?.let { phone ->
            b.addAction(0, ctx.getString(R.string.work_action_call), PendingIntent.getActivity(ctx, code + 1, Shortcuts.intent(ctx, Shortcuts.Kind.CALL, phone, contact.id), PendingIntent.FLAG_IMMUTABLE))
        }
        try {
            NotificationManagerCompat.from(ctx).notify(tag, 0, b.build())
        } catch (_: SecurityException) {
        }
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
                OneTimeWorkRequestBuilder<FollowUpWorker>().setInitialDelay(days.toLong(), TimeUnit.DAYS).setInputData(workDataOf(KEY_LOOKUP to lookupKey, KEY_ID to (contactId ?: -1L))).build(),
            )
        }
    }
}
