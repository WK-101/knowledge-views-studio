package app.parley.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.common.catching
import app.parley.common.ContactSummary
import app.parley.common.EventDate
import app.parley.common.PhoneIdentity
import app.parley.common.calls.CatchUp
import app.parley.common.circle.CircleDigest
import app.parley.common.circle.CirclePlanner
import app.parley.common.people.LifeEvents
import app.parley.common.people.PeopleWidgetPlan
import app.parley.common.widgets.WidgetShape
import app.parley.data.DataContainer
import app.parley.data.EventItem
import app.parley.ui.circle.CircleText
import app.parley.ui.people.eventLabel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * The People widget in Circle mode: upcoming dates in the next 14 days and up to three people from the Circle digest,
 * each with a Call button (through the shortcut trampoline, so the pocket guard applies). Only system contacts are
 * read: private contacts are never in it. With the app lock on and the phone or Parley locked, counts only. Resizable
 * down to one cell: smaller sizes show fewer rows, then no title, and one cell shows the first person due with a Call
 * tap ([PeopleWidgetPlan.circleFit]).
 */
internal object CircleDrawing {
    /** One row: a person or a date. */
    data class Row(val contactId: Long, val name: String, val line: String, val phone: String?)

    data class Content(val people: List<Row>, val dates: List<Row>)

    /** Dates this many days ahead. */
    const val DATE_DAYS = 14

    /** Within the few seconds a widget broadcast may take; past that the widget shows what it has. */
    private const val INDEX_WAIT_MS = 8_000L

    suspend fun load(ctx: Context, c: DataContainer): Content {
        val contacts = withTimeoutOrNull(20_000) { c.contacts.contacts.filterNotNull().first() }?.associateBy { it.lookupKey }
            ?: return Content(emptyList(), emptyList())
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val res = ctx.resources
        val events = catching { c.contacts.events() }.getOrDefault(emptyList())
            .filter { it.lookupKey in contacts && !LifeEvents.isDeath(it.type, it.label) }
            .mapNotNull { e -> EventDate.parse(e.date)?.let { d -> e to d.daysUntil(today).toInt() } }
            .filter { it.second in 0..DATE_DAYS }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
        val dates = events.map { (e, days) ->
            val label = eventLabel(res, EventItem(date = e.date, type = e.type, label = e.label))
            Row(e.contactId, e.name, whenText(ctx, days) + res.getString(R.string.main_separator) + label, e.phone)
        }
        // The digest's people, worked out the same way (the serendipity pick stays in the Sunday notification).
        // The index follows its subscribers, and none may be running in a process started for the widget or a
        // worker: wait for it (bounded), or calls made yesterday would count for nothing and show people as due.
        val idx = c.history.index.value ?: withTimeoutOrNull(INDEX_WAIT_MS) { c.history.awaitIndex() }
        val members = c.circle.members().filter { it.lookupKey in contacts }
        val lasts = members.associate { m -> m.lookupKey to c.circle.lastContact(m.lookupKey, idx) }
        // A next catch-up set after a call keeps them off the widget until that day, as in the reminders.
        val toCall = catching { c.toCall.load() }.getOrNull()
        val iso = c.directory.countryIso
        val planned = members.map { m ->
            val catchUp = toCall?.let { s -> contacts[m.lookupKey]?.let { ct -> CatchUp.of(s, ct.phones.map { PhoneIdentity.key(it.number, iso) }) } }
            CirclePlanner.Member(m.lookupKey, m.days, lasts[m.lookupKey]?.time, CatchUp.quietUntil(m.rhythm.snoozedUntil, catchUp))
        }
        val upcoming = events.filter { it.first.lookupKey in planned.map { p -> p.lookupKey }.toSet() }
            .map { (e, days) -> CircleDigest.UpcomingDate(e.lookupKey, days) }
        val picks = CircleDigest.pick(planned, upcoming, now, quiet = emptyList())
        val people = picks.mapNotNull { p ->
            val ct: ContactSummary = contacts[p.lookupKey] ?: return@mapNotNull null
            val line = when (p.reason) {
                CircleDigest.Reason.DATE -> events.firstOrNull { it.first.lookupKey == p.lookupKey }?.let { (e, days) ->
                    whenText(ctx, days) + res.getString(R.string.main_separator) + eventLabel(res, EventItem(date = e.date, type = e.type, label = e.label))
                } ?: res.getString(R.string.circle_widget_date_soon)
                else -> CircleText.last(res, lasts[p.lookupKey], now)
            }
            Row(ct.id, ct.displayName, line, ct.primaryNumber)
        }
        return Content(people, dates.filter { d -> people.none { it.contactId == d.contactId && it.line == d.line } })
    }

    private fun whenText(ctx: Context, days: Int): String = when (days) {
        0 -> ctx.getString(R.string.label_today)
        1 -> ctx.getString(R.string.circle_widget_tomorrow)
        else -> ctx.resources.getQuantityString(R.plurals.circle_widget_in_days, days, days)
    }

    private val personIds = listOf(
        intArrayOf(R.id.circle_person_0, R.id.circle_person_0_name, R.id.circle_person_0_line, R.id.circle_person_0_call),
        intArrayOf(R.id.circle_person_1, R.id.circle_person_1_name, R.id.circle_person_1_line, R.id.circle_person_1_call),
        intArrayOf(R.id.circle_person_2, R.id.circle_person_2_name, R.id.circle_person_2_line, R.id.circle_person_2_call),
    )
    private val dateIds = intArrayOf(R.id.circle_date_0, R.id.circle_date_1, R.id.circle_date_2)

    /** Widget [id] drawn as [look] says, for each size it may show at ([WidgetSizing]). */
    fun views(ctx: Context, id: Int, manager: AppWidgetManager, content: Content, look: PeopleWidget.Look): RemoteViews =
        WidgetSizing.responsive(manager, id) { size ->
            val fit = PeopleWidgetPlan.circleFit(size.widthDp, size.heightDp, content.people.size, content.dates.size)
            if (fit.compact) compact(ctx, id, content, look, size.heightDp) else sized(ctx, id, content, look, fit)
        }

    private fun openCircle(ctx: Context, id: Int): PendingIntent = WidgetTaps.activity(
        ctx, WidgetTaps.Kind.CIRCLE_APP, id, 0,
        IntentRoutes.own(ctx).setAction(MainActivity.ACTION_SHOW_CIRCLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** While locked: a tap re-draws the widget (names return once the phone is unlocked), or opens Parley to unlock it. */
    private fun revealTap(ctx: Context, id: Int, look: PeopleWidget.Look): PendingIntent =
        if (look.unlockInParley) openCircle(ctx, id) else PeopleWidget.reveal(ctx, WidgetTaps.Kind.CIRCLE_REVEAL, id)

    /**
     * One cell: the first person due with a Call tap on the icon, else "Circle" and a tap that opens it. Locked, only
     * "Circle" shows.
     */
    private fun compact(ctx: Context, id: Int, content: Content, look: PeopleWidget.Look, heightDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_compact)
        v.setViewVisibility(R.id.compact_label, if (WidgetShape.labelFits(heightDp)) View.VISIBLE else View.GONE)
        val first = content.people.firstOrNull()?.takeUnless { look.locked }
        val title = ctx.getString(R.string.label_circle)
        val open = if (look.locked) revealTap(ctx, id, look) else openCircle(ctx, id)
        v.setOnClickPendingIntent(R.id.compact_root, open)
        v.setOnClickPendingIntent(R.id.compact_label, open)
        if (first?.phone != null) {
            v.setImageViewResource(R.id.compact_icon, R.drawable.ic_widget_call)
            v.setTextViewText(R.id.compact_label, first.name)
            v.setContentDescription(R.id.compact_icon, ctx.getString(R.string.action_call_named, first.name))
            val callIntent = Shortcuts.intent(ctx, Shortcuts.Kind.CALL, first.phone, first.contactId, first.name)
            v.setOnClickPendingIntent(R.id.compact_icon, WidgetTaps.activity(ctx, WidgetTaps.Kind.CIRCLE_CALL, id, 0, callIntent))
        } else {
            v.setImageViewResource(R.id.compact_icon, R.drawable.ic_widget_circle)
            v.setTextViewText(R.id.compact_label, first?.name ?: title)
            v.setContentDescription(R.id.compact_icon, title)
            v.setOnClickPendingIntent(R.id.compact_icon, open)
        }
        return v
    }

    private fun sized(ctx: Context, id: Int, content: Content, look: PeopleWidget.Look, fit: PeopleWidgetPlan.CircleFit): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_circle)
        val open = openCircle(ctx, id)
        v.setOnClickPendingIntent(R.id.circle_root, open)
        v.setViewVisibility(R.id.circle_title, if (fit.title) View.VISIBLE else View.GONE)
        personIds.forEach { v.setViewVisibility(it[0], View.GONE) }
        dateIds.forEach { v.setViewVisibility(it, View.GONE) }
        v.setViewVisibility(R.id.circle_dates_title, View.GONE)
        v.setViewVisibility(R.id.circle_message, View.GONE)
        if (look.locked) {
            // App lock on and the phone or Parley locked: counts only, never names.
            val res = ctx.resources
            val text = listOf(
                res.getQuantityString(R.plurals.circle_widget_count_people, content.people.size, content.people.size),
                res.getQuantityString(R.plurals.circle_widget_count_dates, content.dates.size, content.dates.size),
            ).joinToString("\n")
            v.setTextViewText(R.id.circle_message, if (fit.title) text + "\n" + res.getString(R.string.circle_widget_tap_reveal) else text)
            v.setViewVisibility(R.id.circle_message, View.VISIBLE)
            val reveal = revealTap(ctx, id, look)
            v.setOnClickPendingIntent(R.id.circle_root, reveal)
            v.setOnClickPendingIntent(R.id.circle_message, reveal)
            return v
        }
        if (content.people.isEmpty() && content.dates.isEmpty()) {
            v.setTextViewText(R.id.circle_message, ctx.getString(if (fit.title) R.string.circle_widget_empty else R.string.circle_widget_empty_short))
            v.setViewVisibility(R.id.circle_message, View.VISIBLE)
            return v
        }
        content.people.take(fit.people).forEachIndexed { i, p ->
            val ids = personIds[i]
            val row = ids[0]
            val name = ids[1]
            val line = ids[2]
            val call = ids[3]
            v.setViewVisibility(row, View.VISIBLE)
            v.setTextViewText(name, p.name)
            v.setTextViewText(line, p.line)
            v.setOnClickPendingIntent(row, contactIntent(ctx, p.contactId, WidgetTaps.Kind.CIRCLE_PERSON, id, i))
            if (p.phone != null) {
                v.setViewVisibility(call, View.VISIBLE)
                v.setContentDescription(call, ctx.getString(R.string.action_call_named, p.name))
                val callIntent = Shortcuts.intent(ctx, Shortcuts.Kind.CALL, p.phone, p.contactId, p.name)
                v.setOnClickPendingIntent(call, WidgetTaps.activity(ctx, WidgetTaps.Kind.CIRCLE_CALL, id, i, callIntent))
            } else {
                v.setViewVisibility(call, View.GONE)
            }
        }
        val dates = content.dates.take(fit.dates)
        if (dates.isNotEmpty()) {
            v.setViewVisibility(R.id.circle_dates_title, View.VISIBLE)
            dates.forEachIndexed { i, d ->
                v.setViewVisibility(dateIds[i], View.VISIBLE)
                v.setTextViewText(dateIds[i], ctx.getString(R.string.circle_widget_date_row, d.name, d.line))
                v.setOnClickPendingIntent(dateIds[i], contactIntent(ctx, d.contactId, WidgetTaps.Kind.CIRCLE_DATE, id, i))
            }
        }
        return v
    }

    private fun contactIntent(ctx: Context, contactId: Long, kind: WidgetTaps.Kind, id: Int, place: Int): PendingIntent = WidgetTaps.activity(
        ctx, kind, id, place,
        IntentRoutes.own(ctx).setAction(MainActivity.ACTION_SHOW_CALLER).putExtra(MainActivity.EXTRA_CONTACT_ID, contactId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
