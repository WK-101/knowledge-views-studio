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
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.EventDate
import app.parley.common.circle.CircleConfig
import app.parley.common.circle.CircleDigest
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.DateReminders
import app.parley.common.circle.LastContact
import app.parley.common.circle.ReminderDelivery
import app.parley.common.circle.YearlyEvents
import app.parley.common.people.LifeEvents
import app.parley.container
import app.parley.data.ContactEvent
import app.parley.data.DataContainer
import app.parley.shortcuts.Shortcuts
import app.parley.ui.circle.CircleText
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Once a day at the chosen hour: date reminders (R5) and kind keep-in-touch reminders (R4). Entirely local: no
 * calendar permission, no network.
 *
 * - Dates fire once on the lead day (if chosen) and once on the day, never daily; "Mark as wished" closes the
 *   occasion. Each event has its own tag, `birthday:<contactId>:<eventKey>` (G5).
 * - Keep in touch: a Sunday digest with up to three people (default), or one notification per person as they come
 *   due, capped per week. "Not now" doubles the next gap. Answered calls and any logged interaction count (G6).
 * - Every notification is private on the lock screen, with a neutral public version, and stays on the phone (G4).
 */
class RemindersWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.work_channel_reminders), NotificationManager.IMPORTANCE_DEFAULT))
        val cfg = c.circle.config.value
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        if (s.birthdayReminders) runCatching { dates(c, cfg, today, now) }
        if (s.reachOutNudges) keepInTouch(c, cfg, today, now)
        // R7: the Circle widget's dates and people move on daily.
        runCatching { app.parley.shortcuts.CircleWidget.refresh(applicationContext) }
        return Result.success()
    }

    // R5
    private fun dates(c: DataContainer, cfg: CircleConfig, today: LocalDate, now: Long) {
        val ctx = applicationContext
        val events = c.contacts.events()
        // Someone with a date of death gets no "turns 80 today" notification.
        val deceased = events.filter { LifeEvents.isDeath(it.type, it.label) }.map { it.contactId }.toSet()
        val fired = DateReminders.prune(c.circle.stateSet(S_FIRED), now).toMutableSet()
        for (e in events) {
            val d = EventDate.parse(e.date) ?: continue
            if (!LifeEvents.remindBirthday(e.type, e.contactId in deceased)) continue
            val fire = DateReminders.fire(d, today, cfg.dateLeadDays) ?: continue
            val key = DateReminders.eventKey(e.type, d)
            val occasion = DateReminders.occurrence(e.contactId, key, d, today)
            if (c.circle.isWished(occasion)) continue
            val firedKey = "$occasion:${fire.name}"
            if (DateReminders.has(fired, firedKey)) continue
            val title = when (fire) {
                DateReminders.Fire.ON_DAY -> when (e.type) {
                    Event.TYPE_BIRTHDAY -> d.turning(today)?.let { ctx.getString(R.string.work_turns_today, e.name, it) } ?: ctx.getString(R.string.work_birthday_today, e.name)
                    Event.TYPE_ANNIVERSARY -> d.turning(today)?.let { ctx.getString(R.string.work_anniversary_years, e.name, it) } ?: ctx.getString(R.string.work_anniversary_today, e.name)
                    else -> ctx.getString(R.string.work_event_today, e.name, e.label ?: ctx.getString(R.string.work_special_date))
                }
                DateReminders.Fire.LEAD -> {
                    val days = d.daysUntil(today).toInt()
                    when (e.type) {
                        Event.TYPE_BIRTHDAY -> ctx.resources.getQuantityString(R.plurals.circle_birthday_in_days, days, days, e.name)
                        Event.TYPE_ANNIVERSARY -> ctx.resources.getQuantityString(R.plurals.circle_anniversary_in_days, days, days, e.name)
                        else -> ctx.resources.getQuantityString(R.plurals.circle_date_in_days, days, days, e.name, e.label ?: ctx.getString(R.string.work_special_date))
                    }
                }
            }
            notifyDate(e, DateReminders.tag(e.contactId, key), title, occasion)
            fired += "$firedKey|$now"
        }
        c.circle.setStateSet(S_FIRED, fired)
    }

    // R4
    private suspend fun keepInTouch(c: DataContainer, cfg: CircleConfig, today: LocalDate, now: Long) {
        val members = c.circle.relearnDue(now)
        if (members.isEmpty()) return
        // In a cold worker process the flows start empty (null): wait for the first real load.
        val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }?.associateBy { it.lookupKey } ?: return
        val idx = c.history.awaitIndex()
        data class Known(val m: app.parley.data.circle.CircleRepository.Member, val contact: ContactSummary, val last: LastContact?, val planned: CirclePlanner.Member)
        // Only people who are still system contacts: private (vault) contacts are never named here.
        val known = members.mapNotNull { m ->
            val contact = contacts[m.lookupKey] ?: return@mapNotNull null
            val last = c.circle.lastContact(m.lookupKey, idx)
            Known(m, contact, last, CirclePlanner.Member(m.lookupKey, m.days, last?.time, m.rhythm.snoozedUntil))
        }
        if (known.isEmpty()) return
        val byKey = known.associateBy { it.m.lookupKey }
        when (cfg.delivery) {
            ReminderDelivery.WEEKLY_DIGEST -> {
                val last = c.circle.stateString(S_LAST_DIGEST)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (!CircleDigest.isDigestDay(today, last)) return
                val keys = byKey.keys
                val upcoming = runCatching { c.contacts.events() }.getOrDefault(emptyList<ContactEvent>())
                    .filter { it.lookupKey in keys && !LifeEvents.isDeath(it.type, it.label) }
                    .mapNotNull { e -> EventDate.parse(e.date)?.let { CircleDigest.UpcomingDate(e.lookupKey, it.daysUntil(today).toInt()) } }
                // X6: the serendipity pick can be anyone you were once in touch with, as long as they're a contact.
                val quiet = c.circle.lastContactsAll(idx).filterKeys { it in contacts }.map { (k, t) -> CircleDigest.Quiet(k, t) }
                // R10: life events remembered yearly, for anyone (not only the Circle).
                val flags = c.circle.yearlyFlags()
                val yearly = if (flags.isEmpty()) emptyList() else runCatching { c.contacts.events() }.getOrDefault(emptyList<ContactEvent>()).mapNotNull { e ->
                    val d = EventDate.parse(e.date) ?: return@mapNotNull null
                    if (LifeEvents.isDeath(e.type, e.label) || e.lookupKey !in contacts) return@mapNotNull null
                    if (YearlyEvents.key(e.type, e.label, d) !in flags[e.lookupKey].orEmpty()) return@mapNotNull null
                    YearlyEvents.upcoming(e.lookupKey, e.label?.takeIf { it.isNotBlank() } ?: applicationContext.getString(R.string.work_special_date), d, today, CircleDigest.DATE_WINDOW_DAYS)
                }
                val picks = CircleDigest.pick(known.map { it.planned }, upcoming, now, c.circle.stateString(S_LAST_QUIET), quiet, yearly)
                c.circle.setStateString(S_LAST_DIGEST, today.toString())
                c.circle.setStateString(S_LAST_QUIET, picks.firstOrNull { it.reason == CircleDigest.Reason.QUIET }?.lookupKey)
                if (picks.isNotEmpty()) notifyDigest(picks.mapNotNull { p -> (byKey[p.lookupKey]?.contact ?: contacts[p.lookupKey])?.let { it to p } })
            }
            ReminderDelivery.AS_DUE -> {
                val week = CircleDigest.weekOf(today).toString()
                val sent = if (c.circle.stateString(S_WEEK) == week) c.circle.stateString(S_SENT)?.toIntOrNull() ?: 0 else 0
                val lastNudged = known.mapNotNull { k -> k.m.meta.lastNudgedAt?.let { k.m.lookupKey to it } }.toMap()
                val due = CircleDigest.asDue(known.map { it.planned }, lastNudged, now, cfg.weeklyCap, sent)
                for (key in due) {
                    val k = byKey[key] ?: continue
                    notifyNudge(k.contact, k.last, now)
                    c.meta.setMeta(k.m.meta.copy(lastNudgedAt = now))
                }
                c.circle.setStateString(S_WEEK, week)
                c.circle.setStateString(S_SENT, (sent + due.size).toString())
            }
        }
    }

    private fun builder(title: String): NotificationCompat.Builder {
        val ctx = applicationContext
        // G4: nothing personal on the lock screen, and nothing mirrored to watches.
        val public = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cake)
            .setContentTitle(ctx.getString(R.string.circle_notif_public))
            .build()
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cake)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
    }

    private fun openContact(contactId: Long, code: Int): PendingIntent = PendingIntent.getActivity(
        applicationContext, code,
        Intent(applicationContext, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CALLER).putExtra(MainActivity.EXTRA_CONTACT_ID, contactId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun addCallAndMessage(b: NotificationCompat.Builder, phone: String?, contactId: Long, code: Int) {
        val ctx = applicationContext
        phone ?: return
        b.addAction(0, ctx.getString(R.string.work_action_call), PendingIntent.getActivity(ctx, code + 1, Shortcuts.intent(ctx, Shortcuts.Kind.CALL, phone, contactId), PendingIntent.FLAG_IMMUTABLE))
        b.addAction(0, ctx.getString(R.string.work_action_message), PendingIntent.getActivity(ctx, code + 2, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)), PendingIntent.FLAG_IMMUTABLE))
    }

    private fun post(tag: String, b: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(applicationContext).notify(tag, 0, b.build())
        } catch (_: SecurityException) {
        }
    }

    private fun notifyDate(e: ContactEvent, tag: String, title: String, occasion: String) {
        val code = tag.hashCode()
        val b = builder(title).setContentIntent(openContact(e.contactId, code))
        addCallAndMessage(b, e.phone, e.contactId, code)
        // R5: "Mark as wished" logs it and closes this occasion.
        b.addAction(0, applicationContext.getString(R.string.circle_mark_wished), CircleActionReceiver.wished(applicationContext, code + 3, tag, e.lookupKey, e.contactId, occasion))
        post(tag, b)
    }

    private fun notifyNudge(contact: ContactSummary, last: LastContact?, now: Long) {
        val ctx = applicationContext
        val tag = DateReminders.nudgeTag(contact.id)
        val code = tag.hashCode()
        val b = builder(ctx.getString(R.string.circle_might_enjoy, contact.displayName))
            .setContentText(CircleText.last(ctx.resources, last, now))
            .setContentIntent(openContact(contact.id, code))
        addCallAndMessage(b, (contact.phones.firstOrNull { it.isPrimary } ?: contact.phones.firstOrNull())?.number, contact.id, code)
        b.addAction(0, ctx.getString(R.string.circle_not_now), CircleActionReceiver.notNow(ctx, code + 3, tag, contact.lookupKey))
        post(tag, b)
    }

    private fun notifyDigest(people: List<Pair<ContactSummary, CircleDigest.Pick>>) {
        val ctx = applicationContext
        val lines = people.map { (ct, pick) ->
            when (pick.reason) {
                CircleDigest.Reason.DUE -> ctx.getString(R.string.circle_might_enjoy, ct.displayName)
                CircleDigest.Reason.DATE -> ctx.getString(R.string.circle_digest_date, ct.displayName)
                // X6: over a year since you were in touch.
                CircleDigest.Reason.QUIET -> ctx.getString(R.string.c2_digest_long_quiet, ct.displayName)
                // R10: "1 year since Ana's new job".
                CircleDigest.Reason.YEARLY -> pick.years?.let { y -> ctx.resources.getQuantityString(R.plurals.c2_digest_yearly, y, y, ct.displayName, pick.label.orEmpty()) }
                    ?: ctx.getString(R.string.c2_digest_yearly_no_year, ct.displayName, pick.label.orEmpty())
            }
        }
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val open = PendingIntent.getActivity(
            ctx, DateReminders.DIGEST_TAG.hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CIRCLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = builder(ctx.getString(R.string.circle_digest_title))
            .setContentText(lines.joinToString(ctx.getString(R.string.main_separator)))
            .setStyle(style)
            .setContentIntent(open)
        post(DateReminders.DIGEST_TAG, b)
    }

    companion object {
        private const val NAME = "parley-reminders"
        const val CHANNEL = "reminders_v1"
        private const val S_FIRED = "fired"
        private const val S_LAST_DIGEST = "lastDigest"
        private const val S_LAST_QUIET = "lastQuiet"
        private const val S_WEEK = "week"
        private const val S_SENT = "sentThisWeek"

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
