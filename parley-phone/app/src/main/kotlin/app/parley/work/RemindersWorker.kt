package app.parley.work

import app.parley.common.catching
import app.parley.common.AltCalendar
import app.parley.common.AltCalendars
import app.parley.data.people.IcuCalendars
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Event
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.EventDate
import app.parley.common.NotificationChannels
import app.parley.common.PhoneIdentity
import app.parley.common.calls.CatchUp
import app.parley.common.circle.CircleConfig
import app.parley.common.circle.CircleDigest
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.DateReminders
import app.parley.common.circle.Drift
import app.parley.common.circle.KeepInTouch
import app.parley.common.circle.LastContact
import app.parley.common.circle.ReminderDelivery
import app.parley.common.circle.YearlyEvents
import app.parley.common.people.LifeEvents
import app.parley.container
import app.parley.data.ContactEvent
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.security.Privacy
import app.parley.common.history.CallLogIndex
import app.parley.data.circle.CircleRepository
import app.parley.shortcuts.CircleWidget
import app.parley.shortcuts.Shortcuts
import app.parley.ui.circle.CircleText
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Once a day at the chosen hour: date reminders and kind keep-in-touch reminders. Entirely local: no
 * calendar permission, no network.
 *
 * - Dates fire once on the lead day (if chosen) and once on the day, never daily; "Mark as wished" closes the
 *   occasion. Each event has its own tag, `birthday:<contactId>:<eventKey>`.
 * - Keep in touch: a Sunday digest with up to three people (default), or one notification per person as they come
 *   due, capped per week. "Not now" doubles the next gap. Answered calls and any logged interaction count.
 * - Every notification is private on the lock screen, with a neutral public version, and stays on the phone.
 */
class RemindersWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        val cfg = c.circle.config.value
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        if (s.birthdayReminders) runCatching { dates(c, cfg, today, now) }
        if (s.reachOutNudges) keepInTouch(c, cfg, today, now)
        // The Circle widget's dates and people move on daily.
        runCatching { CircleWidget.refresh(applicationContext) }
        return Result.success()
    }

    private fun dates(c: DataContainer, cfg: CircleConfig, today: LocalDate, now: Long) {
        val ctx = applicationContext
        val events = c.contacts.events()
        // Someone with a date of death gets no "turns 80 today" notification.
        val deceased = events.filter { LifeEvents.isDeath(it.type, it.label) }.map { it.contactId }.toSet()
        val fired = DateReminders.prune(c.circle.stateSet(S_FIRED), now).toMutableSet()
        for (e in events) {
            val stored = EventDate.parse(e.date) ?: continue
            // A date kept by another calendar (lunar, Hebrew, Hijri) fires on its next Gregorian day, as one occasion
            // per year of that calendar, and its age is counted in that calendar.
            val due = AltCalendars.due(stored, AltCalendar.byKey(e.calendar), today, IcuCalendars) ?: continue
            val d = due.date
            if (!LifeEvents.remindBirthday(e.type, e.contactId in deceased)) continue
            val fire = DateReminders.fire(d, today, cfg.dateLeadDays) ?: continue
            val key = DateReminders.eventKey(e.type, stored, e.label)
            val occasion = DateReminders.occurrence(e.contactId, key, due.round)
            if (c.circle.isWished(occasion)) continue
            val firedKey = "$occasion:${fire.name}"
            if (DateReminders.has(fired, firedKey)) continue
            val title = when (fire) {
                DateReminders.Fire.ON_DAY -> when (e.type) {
                    Event.TYPE_BIRTHDAY -> due.turning?.let { ctx.getString(R.string.work_turns_today, e.name, it) }
                        ?: ctx.getString(R.string.work_birthday_today, e.name)
                    Event.TYPE_ANNIVERSARY -> due.turning?.let { ctx.getString(R.string.work_anniversary_years, e.name, it) }
                        ?: ctx.getString(R.string.work_anniversary_today, e.name)
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

    private suspend fun keepInTouch(c: DataContainer, cfg: CircleConfig, today: LocalDate, now: Long) {
        val members = c.circle.relearnDue(now)
        // The weekly digest also carries yearly life events and the serendipity pick, for anyone (not only
        // the Circle), so it still goes out with an empty Circle.
        val digest = cfg.delivery == ReminderDelivery.WEEKLY_DIGEST
        if (members.isEmpty() && !digest) return
        // In a cold worker process the flows start empty (null): wait for the first real load.
        val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }?.associateBy { it.lookupKey } ?: return
        val idx = c.history.awaitIndex()
        data class Known(val m: CircleRepository.Member, val contact: ContactSummary, val last: LastContact?, val planned: CirclePlanner.Member)
        // A next catch-up set after a call (on their To call item) keeps the rhythm quiet until that day.
        val toCall = catching { c.toCall.load() }.getOrNull()
        val iso = PhoneEnv.countryIso(applicationContext)
        // Only people who are still system contacts: private (vault) contacts are never named here.
        val known = members.mapNotNull { m ->
            val contact = contacts[m.lookupKey] ?: return@mapNotNull null
            val last = c.circle.lastContact(m.lookupKey, idx)
            val catchUp = toCall?.let { s -> CatchUp.of(s, contact.phones.map { PhoneIdentity.key(it.number, iso) }) }
            Known(m, contact, last, CirclePlanner.Member(m.lookupKey, m.days, last?.time, CatchUp.quietUntil(m.rhythm.snoozedUntil, catchUp)))
        }
        if (known.isEmpty() && !digest) return
        val byKey = known.associateBy { it.m.lookupKey }
        when (cfg.delivery) {
            ReminderDelivery.WEEKLY_DIGEST -> {
                val last = c.circle.stateString(S_LAST_DIGEST)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (!CircleDigest.isDigestDay(today, last)) return
                val keys = byKey.keys
                val upcoming = runCatching { c.contacts.events() }.getOrDefault(emptyList<ContactEvent>())
                    .filter { it.lookupKey in keys && !LifeEvents.isDeath(it.type, it.label) }
                    .mapNotNull { e ->
                        EventDate.parse(e.date)?.let { AltCalendars.effective(it, AltCalendar.byKey(e.calendar), today, IcuCalendars) }
                            ?.let { CircleDigest.UpcomingDate(e.lookupKey, it.daysUntil(today).toInt()) }
                    }
                // The serendipity pick can be anyone you were once in touch with, as long as they're a contact.
                val quiet = c.circle.lastContactsAll(idx).filterKeys { it in contacts }.map { (k, t) -> CircleDigest.Quiet(k, t) }
                // Life events remembered yearly, for anyone (not only the Circle).
                val flags = c.circle.yearlyFlags()
                val yearly = if (flags.isEmpty()) emptyList() else runCatching { c.contacts.events() }.getOrDefault(emptyList<ContactEvent>()).mapNotNull { e ->
                    val d = EventDate.parse(e.date) ?: return@mapNotNull null
                    if (LifeEvents.isDeath(e.type, e.label) || e.lookupKey !in contacts) return@mapNotNull null
                    if (YearlyEvents.key(e.type, e.label, d) !in flags[e.lookupKey].orEmpty()) return@mapNotNull null
                    YearlyEvents.upcoming(e.lookupKey, e.label?.takeIf { it.isNotBlank() } ?: applicationContext.getString(R.string.work_special_date), d, today, CircleDigest.DATE_WINDOW_DAYS)
                }
                val lastDrift = c.circle.stateString(S_LAST_DRIFT)
                val drift = drifting(c, idx, now, contacts.keys, lastDrift)
                val picks = CircleDigest.pick(known.map { it.planned }, upcoming, now, c.circle.stateString(S_LAST_QUIET), quiet, yearly, drift = drift)
                c.circle.setStateString(S_LAST_DIGEST, today.toString())
                c.circle.setStateString(S_LAST_QUIET, picks.firstOrNull { it.reason == CircleDigest.Reason.QUIET }?.lookupKey)
                c.circle.setStateString(S_LAST_DRIFT, picks.firstOrNull { it.reason == CircleDigest.Reason.DRIFT }?.lookupKey)
                // Seasons that ended since the last digest: one line each, then they're cleared (the usual rhythm
                // already counted from their end). Private contacts are never named here; theirs are cleared quietly.
                val ended = catching { c.circle.endedSeasons(now) }.getOrDefault(emptyList())
                val seasonLines = ended.mapNotNull { m -> contacts[m.lookupKey]?.let { it to CircleText.rhythm(applicationContext.resources, m.everyDays, m.rhythm) } }
                if (picks.isNotEmpty() || seasonLines.isNotEmpty()) {
                    val turns = yourTurns(c, cfg, picks, idx, now)
                    notifyDigest(picks.mapNotNull { p -> (byKey[p.lookupKey]?.contact ?: contacts[p.lookupKey])?.let { it to p } }, turns, seasonLines, byKey.keys)
                }
                catching { c.circle.clearEndedSeasons(ended.map { it.lookupKey }, now) }
            }
            ReminderDelivery.AS_DUE -> {
                val week = CircleDigest.weekOf(today).toString()
                val sent = if (c.circle.stateString(S_WEEK) == week) c.circle.stateString(S_SENT)?.toIntOrNull() ?: 0 else 0
                val lastNudged = known.mapNotNull { k -> k.m.meta.lastNudgedAt?.let { k.m.lookupKey to it } }.toMap()
                val due = CircleDigest.asDue(known.map { it.planned }, lastNudged, now, cfg.weeklyCap, sent)
                for (key in due) {
                    val k = byKey[key] ?: continue
                    notifyNudge(k.contact, k.last, now)
                    // Only this column: the row may have changed since it was read (a pinned-note edit, a re-key).
                    // Someone in the Circle through a label may have no row yet: it is made for the date.
                    if (k.m.own) c.meta.setLastNudgedAt(key, now) else c.circle.nudged(key, now)
                }
                // No digest to say it: ended seasons are simply cleared (the usual rhythm counts from their end).
                catching { c.circle.clearEndedSeasons(c.circle.endedSeasons(now).map { it.lookupKey }, now) }
                c.circle.setStateString(S_WEEK, week)
                c.circle.setStateString(S_SENT, (sent + due.size).toString())
            }
        }
    }

    private fun builder(title: String): NotificationCompat.Builder =
        PrivateNotice.builder(applicationContext, CHANNEL, R.drawable.ic_stat_cake, title, applicationContext.getString(R.string.label_reminder))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

    private fun openContact(contactId: Long, code: Int): PendingIntent = PrivateNotice.open(
        applicationContext, code,
        IntentRoutes.own(applicationContext).setAction(MainActivity.ACTION_SHOW_CALLER).putExtra(MainActivity.EXTRA_CONTACT_ID, contactId), update = true,
    )

    private fun addCallAndMessage(b: NotificationCompat.Builder, phone: String?, contactId: Long, code: Int) {
        val ctx = applicationContext
        phone ?: return
        b.addAction(0, ctx.getString(R.string.action_call), PendingIntent.getActivity(ctx, code + 1, Shortcuts.intent(ctx, Shortcuts.Kind.CALL, phone, contactId), PendingIntent.FLAG_IMMUTABLE))
        b.addAction(0, ctx.getString(R.string.action_message), PendingIntent.getActivity(ctx, code + 2, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)), PendingIntent.FLAG_IMMUTABLE))
    }

    private fun post(tag: String, b: NotificationCompat.Builder) = PrivateNotice.post(applicationContext, tag, 0, b)

    private fun notifyDate(e: ContactEvent, tag: String, title: String, occasion: String) {
        val code = tag.hashCode()
        val b = builder(title).setContentIntent(openContact(e.contactId, code))
        addCallAndMessage(b, e.phone, e.contactId, code)
        // "Mark as wished" logs it and closes this occasion.
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
        addCallAndMessage(b, contact.primaryNumber, contact.id, code)
        b.addAction(0, ctx.getString(R.string.label_not_now), CircleActionReceiver.notNow(ctx, code + 3, tag, contact.lookupKey))
        post(tag, b)
    }

    /**
     * The due picks it's your turn with (they started the last few conversations), by lookup key with how many. None
     * when "who reaches out first" is off or a duress unlock hides relationship insights.
     */
    private suspend fun yourTurns(c: DataContainer, cfg: CircleConfig, picks: List<CircleDigest.Pick>, idx: CallLogIndex?, now: Long): Map<String, Int> {
        val due = picks.filter { it.reason == CircleDigest.Reason.DUE }.map { it.lookupKey }.toSet()
        if (due.isEmpty() || !cfg.firstMoverShown || !c.privacy.now().insightsShown) return emptyMap()
        val touches = catching { c.circle.touches(now - TURN_WINDOW_DAYS * CallLogIndex.DAY, idx) }.getOrDefault(emptyList())
            .filter { it.key in due }.groupBy { it.key }
        return due.mapNotNull { k -> KeepInTouch.yourTurn(touches[k].orEmpty(), due = true, firstMoverShown = true)?.let { k to it } }.toMap()
    }

    /**
     * This week's "quieter than usual" pick: a contact ([contacts]) you used to talk to much more often, not in a
     * season, not marked "They're fine" and not last week's. None while a duress unlock hides relationship insights.
     */
    private suspend fun drifting(c: DataContainer, idx: CallLogIndex?, now: Long, contacts: Set<String>, lastDrift: String?): Drift.Candidate? {
        if (!c.privacy.now().insightsShown) return null
        val since = now - (Drift.RECENT_DAYS + Drift.BEFORE_DAYS) * CallLogIndex.DAY
        val touches = catching { c.circle.touches(since, idx) }.getOrDefault(emptyList()).filter { it.kind.inTouch && it.key in contacts }
        if (touches.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val candidates = touches.groupBy { it.key }.mapNotNull { (k, t) -> Drift.of(k, t.map { it.time }, now, zone) }
        if (candidates.isEmpty()) return null
        val quietOnes = catching { c.circle.notDrifting(now) }.getOrDefault(emptySet())
        return Drift.pick(candidates, quietOnes, lastDrift)
    }

    private fun notifyDigest(
        people: List<Pair<ContactSummary, CircleDigest.Pick>>,
        turns: Map<String, Int>,
        seasons: List<Pair<ContactSummary, String>>,
        circle: Set<String>,
    ) {
        val ctx = applicationContext
        // Read again as the lines are written, so a duress unlock in between still hides "your turn" and "quieter".
        val privacy = Privacy.duressOnly()
        val shown = people.filter { (_, pick) -> pick.reason != CircleDigest.Reason.DRIFT || privacy.insightsShown }
        val lines = shown.map { (ct, pick) ->
            when (pick.reason) {
                // "Ana has called you the last 3 times. Your turn to call?"
                CircleDigest.Reason.DUE -> CircleText.yourTurn(ctx.resources, ct.displayName, turns[ct.lookupKey], privacy)
                    ?: ctx.getString(R.string.circle_might_enjoy, ct.displayName)
                CircleDigest.Reason.DATE -> ctx.getString(R.string.circle_digest_date, ct.displayName)
                // Over a year since you were in touch.
                CircleDigest.Reason.QUIET -> ctx.getString(R.string.circle_digest_long_quiet, ct.displayName)
                // "1 year since Ana's new job".
                CircleDigest.Reason.YEARLY -> pick.years?.let { y -> ctx.resources.getQuantityString(R.plurals.circle_digest_yearly, y, y, ct.displayName, pick.label.orEmpty()) }
                    ?: ctx.getString(R.string.circle_digest_yearly_no_year, ct.displayName, pick.label.orEmpty())
                // "You and Marco used to talk about every week; since April it's been about every month."
                CircleDigest.Reason.DRIFT -> pick.drift?.let { CircleText.drift(ctx.resources, ct.displayName, it, privacy) }.orEmpty()
            }
        } + seasons.map { (ct, usual) -> ctx.getString(R.string.circle_digest_season_ended, ct.displayName, usual) }
        if (lines.isEmpty()) return
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val open = PrivateNotice.open(ctx, DateReminders.DIGEST_TAG.hashCode(), IntentRoutes.own(ctx).setAction(MainActivity.ACTION_SHOW_CIRCLE), update = true)
        val b = builder(ctx.getString(R.string.circle_digest_title))
            .setContentText(lines.joinToString(ctx.getString(R.string.main_separator)))
            .setStyle(style)
            .setContentIntent(open)
        // Quieter than usual: Call, Add to your Circle (for someone outside it) and They're fine, for that one person.
        shown.firstOrNull { it.second.reason == CircleDigest.Reason.DRIFT }?.let { (ct, pick) ->
            val code = DateReminders.DIGEST_TAG.hashCode()
            ct.primaryNumber?.let { phone ->
                b.addAction(0, ctx.getString(R.string.action_call), PendingIntent.getActivity(ctx, code + 1, Shortcuts.intent(ctx, Shortcuts.Kind.CALL, phone, ct.id), PendingIntent.FLAG_IMMUTABLE))
            }
            val drift = pick.drift
            if (drift != null && ct.lookupKey !in circle) {
                b.addAction(
                    0, ctx.getString(R.string.circle_add_to_circle),
                    CircleActionReceiver.addToCircle(ctx, code + 2, DateReminders.DIGEST_TAG, ct.lookupKey, ct.id, Drift.suggestedDays(drift)),
                )
            }
            b.addAction(0, ctx.getString(R.string.circle_drift_fine), CircleActionReceiver.fine(ctx, code + 3, DateReminders.DIGEST_TAG, ct.lookupKey, ct.id))
        }
        post(DateReminders.DIGEST_TAG, b)
    }

    companion object {
        private const val NAME = "parley-reminders"
        const val CHANNEL = NotificationChannels.REMINDERS
        private const val S_FIRED = "fired"
        private const val S_LAST_DIGEST = "lastDigest"
        private const val S_LAST_QUIET = "lastQuiet"
        private const val S_LAST_DRIFT = "lastDrift"
        private const val S_WEEK = "week"
        private const val S_SENT = "sentThisWeek"

        /** "Your turn" looks back this far for the last few conversations. */
        private const val TURN_WINDOW_DAYS = 400L

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
