package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Event
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.MainActivity
import app.parley.common.EventDate
import app.parley.container
import app.parley.shortcuts.Shortcuts
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Once a day at the chosen hour: birthday/anniversary notifications and "reach out" nudges.
 * Entirely local — no calendar permission, no network.
 */
class RemindersWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Birthdays & reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val today = LocalDate.now()
        if (s.birthdayReminders) {
            val events = c.contacts.events()
            // Someone with a date of death gets no "turns 80 today" notification.
            val deceased = events.filter { app.parley.common.people.LifeEvents.isDeath(it.type, it.label) }.map { it.contactId }.toSet()
            events.forEach { e ->
                val d = EventDate.parse(e.date) ?: return@forEach
                if (d.daysUntil(today) != 0L) return@forEach
                if (!app.parley.common.people.LifeEvents.remindBirthday(e.type, e.contactId in deceased)) return@forEach
                val what = when (e.type) {
                    Event.TYPE_BIRTHDAY -> d.turning(today)?.let { "turns $it today" } ?: "has a birthday today"
                    Event.TYPE_ANNIVERSARY -> d.turning(today)?.let { "anniversary: $it years" } ?: "anniversary today"
                    else -> (e.label ?: "special date") + " today"
                }
                notify(e.contactId.toInt(), "${e.name} $what", e.phone, e.contactId)
            }
        }
        if (s.reachOutNudges) nudges(c)
        return Result.success()
    }

    private suspend fun nudges(c: app.parley.data.DataContainer) {
        val metas = c.meta.allMeta().first()
        // In a cold worker process the flows start empty (null): wait for the first real load.
        val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }?.associateBy { it.lookupKey } ?: return
        val calls = withTimeoutOrNull(30_000) { c.callLog.calls.filterNotNull().first() } ?: return
        val now = System.currentTimeMillis()
        for (m in metas) {
            val every = m.reachOutDays ?: continue
            val contact = contacts[m.lookupKey] ?: continue
            val keys = contact.phones.map { app.parley.common.PhoneNumbers.matchKey(it.number) }.toSet()
            val last = calls.firstOrNull { app.parley.common.PhoneNumbers.matchKey(it.number) in keys && it.durationSec > 0 }?.date ?: 0L
            val due = now - last > every * 86_400_000L
            val nudgedAt = m.lastNudgedAt
            val recentlyNudged = nudgedAt != null && now - nudgedAt < every * 86_400_000L / 2
            if (due && !recentlyNudged) {
                val days = if (last > 0) (now - last) / 86_400_000L else null
                notify(10_000 + contact.id.toInt(), "Catch up with ${contact.displayName}" + (days?.let { " — last talked $it days ago" } ?: ""), contact.phones.firstOrNull()?.number, contact.id)
                c.meta.setMeta(m.copy(lastNudgedAt = now))
            }
        }
    }

    private fun notify(id: Int, title: String, phone: String?, contactId: Long) {
        val ctx = applicationContext
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CALLER).putExtra(MainActivity.EXTRA_CONTACT_ID, contactId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(app.parley.R.drawable.ic_stat_cake)
            .setContentTitle(title)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (phone != null) {
            b.addAction(0, "Call", PendingIntent.getActivity(ctx, id + 1, Shortcuts.intent(ctx, Shortcuts.Kind.CALL, phone, contactId), PendingIntent.FLAG_IMMUTABLE))
            b.addAction(0, "Message", PendingIntent.getActivity(ctx, id + 2, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)), PendingIntent.FLAG_IMMUTABLE))
        }
        try {
            NotificationManagerCompat.from(ctx).notify("reminder", id, b.build())
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val NAME = "parley-reminders"
        const val CHANNEL = "reminders_v1"

        fun schedule(context: Context, hour: Int) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RemindersWorker>(1, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MINUTES).build(),
            )
        }
    }
}
