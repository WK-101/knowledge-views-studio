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
import app.parley.data.DataContainer
import app.parley.data.EventItem
import app.parley.ui.circle.CircleText
import app.parley.ui.people.eventLabel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * The People widget in Circle mode (and the Circle widgets placed before it, [CircleWidget]): upcoming dates in the
 * next 14 days and up to three people from the Circle digest, each with a Call button (through the shortcut
 * trampoline, so the pocket guard applies). Only system contacts are read: private contacts are never in it. With the
 * app lock on and the phone or Parley locked, counts only. Resizable: smaller sizes show fewer rows.
 */
internal object CircleDrawing {
    /** One row: a person or a date. */
    data class Row(val contactId: Long, val name: String, val line: String, val phone: String?)

    data class Content(val people: List<Row>, val dates: List<Row>)

    /** Dates this many days ahead. */
    const val DATE_DAYS = 14

    private const val MAX_DATES = 3

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

    /** Widget [id] drawn as [look] says. */
    fun views(ctx: Context, id: Int, manager: AppWidgetManager, content: Content, look: PeopleWidget.Look): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_circle)
        val open = WidgetTaps.activity(
            ctx, WidgetTaps.Kind.CIRCLE_APP, id, 0,
            IntentRoutes.own(ctx).setAction(MainActivity.ACTION_SHOW_CIRCLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        v.setOnClickPendingIntent(R.id.circle_root, open)
        // Smaller widgets show fewer rows: about 44 dp per person, 20 dp per date, after the title.
        val height = manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 } ?: 180
        var room = height - 40
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
            v.setTextViewText(R.id.circle_message, text + "\n" + res.getString(R.string.circle_widget_tap_reveal))
            v.setViewVisibility(R.id.circle_message, View.VISIBLE)
            // A tap re-draws the widget (names return when the phone is unlocked); opening Parley does too, and is
            // what a tap does when only Parley is locked.
            val reveal = if (look.unlockInParley) open else PeopleWidget.reveal(ctx, WidgetTaps.Kind.CIRCLE_REVEAL, id, look)
            v.setOnClickPendingIntent(R.id.circle_root, reveal)
            v.setOnClickPendingIntent(R.id.circle_message, reveal)
            return v
        }
        if (content.people.isEmpty() && content.dates.isEmpty()) {
            v.setTextViewText(R.id.circle_message, ctx.getString(R.string.circle_widget_empty))
            v.setViewVisibility(R.id.circle_message, View.VISIBLE)
            return v
        }
        content.people.take(personIds.size).forEachIndexed { i, p ->
            if (room < 40) return@forEachIndexed
            room -= 44
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
        val dates = content.dates.take(MAX_DATES)
        if (dates.isNotEmpty() && room >= 40) {
            v.setViewVisibility(R.id.circle_dates_title, View.VISIBLE)
            room -= 24
            dates.forEachIndexed { i, d ->
                if (room < 18) return@forEachIndexed
                room -= 20
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
