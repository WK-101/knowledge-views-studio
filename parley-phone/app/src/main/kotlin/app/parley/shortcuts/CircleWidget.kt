package app.parley.shortcuts

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.parley.MainActivity
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.EventDate
import app.parley.common.circle.CircleDigest
import app.parley.common.circle.CirclePlanner
import app.parley.common.people.LifeEvents
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.EventItem
import app.parley.ui.circle.CircleText
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * R7: the Circle widget. Plain RemoteViews (no Glance): upcoming dates in the next 14 days and up to three people
 * from the Circle digest, each with a Call button (through the shortcut trampoline, so the pocket guard applies).
 *
 * - With the app lock on, it shows only counts, never names, while the device is locked; names come back once the
 *   phone is unlocked: USER_PRESENT while Parley runs, when Parley is opened ([refreshIfShownLocked]), or with a tap
 *   on the counts (a broadcast to this provider, which works after the process has died too).
 * - Private (vault) contacts are never in it: only system contacts are read.
 * - The app refreshes it when the Circle, interactions, the call history or the lock setting change, and daily from
 *   the reminders worker. Resizable: smaller sizes show fewer rows.
 */
class CircleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        // R7: "tap to show names" on the locked rendering (drawn again with names only if the phone is unlocked now).
        if (intent.action == ACTION_REVEAL) refreshAsync(context) else super.onReceive(context, intent)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        context.container.scope.launch {
            try {
                refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    /** One row: a person or a date. */
    private data class Row(val contactId: Long, val name: String, val line: String, val phone: String?)

    private data class Content(val people: List<Row>, val dates: List<Row>)

    companion object {
        /** Dates this many days ahead. */
        const val DATE_DAYS = 14

        private const val MAX_DATES = 3

        private fun ids(context: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, CircleWidget::class.java)) }.getOrDefault(IntArray(0))

        private const val ACTION_REVEAL = "app.parley.action.CIRCLE_WIDGET_REVEAL"

        /** Whether the last drawing was the locked (counts only) one; null: unknown (e.g. a new process). */
        @Volatile
        private var shownLocked: Boolean? = null

        /** Parley came to the front (so the phone is unlocked): brings names back if the widget still hides them. */
        suspend fun refreshIfShownLocked(context: Context) {
            if (shownLocked != false) refresh(context)
        }

        /** Re-draws every Circle widget (no-op without any). */
        suspend fun refresh(context: Context) {
            val ctx = context.applicationContext
            val ids = ids(ctx)
            if (ids.isEmpty()) return
            val c = ctx.container
            val content = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(ctx, c) } }.getOrNull() ?: Content(emptyList(), emptyList())
            val locked = c.settings.current().appLock && ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
            val manager = AppWidgetManager.getInstance(ctx)
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, views(ctx, id, manager, content, locked)) } }
            shownLocked = locked
        }

        private suspend fun load(ctx: Context, c: DataContainer): Content {
            val contacts = withTimeoutOrNull(20_000) { c.contacts.contacts.filterNotNull().first() }?.associateBy { it.lookupKey } ?: return Content(emptyList(), emptyList())
            val today = LocalDate.now()
            val now = System.currentTimeMillis()
            val res = ctx.resources
            val events = runCatching { c.contacts.events() }.getOrDefault(emptyList())
                .filter { it.lookupKey in contacts && !LifeEvents.isDeath(it.type, it.label) }
                .mapNotNull { e -> EventDate.parse(e.date)?.let { d -> e to d.daysUntil(today).toInt() } }
                .filter { it.second in 0..DATE_DAYS }
                .sortedWith(compareBy({ it.second }, { it.first.name }))
            val dates = events.map { (e, days) ->
                val label = app.parley.ui.people.eventLabel(res, EventItem(date = e.date, type = e.type, label = e.label))
                Row(e.contactId, e.name, whenText(ctx, days) + res.getString(R.string.main_separator) + label, e.phone)
            }
            // The digest's people, worked out the same way (the serendipity pick stays in the Sunday notification).
            val idx = c.history.index.value
            val members = c.circle.members().filter { it.lookupKey in contacts }
            val lasts = members.associate { m -> m.lookupKey to c.circle.lastContact(m.lookupKey, idx) }
            val planned = members.map { m -> CirclePlanner.Member(m.lookupKey, m.days, lasts[m.lookupKey]?.time, m.rhythm.snoozedUntil) }
            val upcoming = events.filter { it.first.lookupKey in planned.map { p -> p.lookupKey }.toSet() }
                .map { (e, days) -> CircleDigest.UpcomingDate(e.lookupKey, days) }
            val picks = CircleDigest.pick(planned, upcoming, now, quiet = emptyList())
            val people = picks.mapNotNull { p ->
                val ct: ContactSummary = contacts[p.lookupKey] ?: return@mapNotNull null
                val line = when (p.reason) {
                    CircleDigest.Reason.DATE -> events.firstOrNull { it.first.lookupKey == p.lookupKey }?.let { (e, days) ->
                        whenText(ctx, days) + res.getString(R.string.main_separator) + app.parley.ui.people.eventLabel(res, EventItem(date = e.date, type = e.type, label = e.label))
                    } ?: res.getString(R.string.c2_widget_date_soon)
                    else -> CircleText.last(res, lasts[p.lookupKey], now)
                }
                Row(ct.id, ct.displayName, line, (ct.phones.firstOrNull { it.isPrimary } ?: ct.phones.firstOrNull())?.number)
            }
            return Content(people, dates.filter { d -> people.none { it.contactId == d.contactId && it.line == d.line } })
        }

        private fun whenText(ctx: Context, days: Int): String = when (days) {
            0 -> ctx.getString(R.string.c2_widget_today)
            1 -> ctx.getString(R.string.c2_widget_tomorrow)
            else -> ctx.resources.getQuantityString(R.plurals.c2_widget_in_days, days, days)
        }

        private val personIds = listOf(
            intArrayOf(R.id.circle_person_0, R.id.circle_person_0_name, R.id.circle_person_0_line, R.id.circle_person_0_call),
            intArrayOf(R.id.circle_person_1, R.id.circle_person_1_name, R.id.circle_person_1_line, R.id.circle_person_1_call),
            intArrayOf(R.id.circle_person_2, R.id.circle_person_2_name, R.id.circle_person_2_line, R.id.circle_person_2_call),
        )
        private val dateIds = intArrayOf(R.id.circle_date_0, R.id.circle_date_1, R.id.circle_date_2)

        private fun views(ctx: Context, id: Int, manager: AppWidgetManager, content: Content, locked: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_circle)
            val open = PendingIntent.getActivity(
                ctx, 7100 + id,
                Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CIRCLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.circle_root, open)
            // Smaller widgets show fewer rows: about 44 dp per person, 20 dp per date, after the title.
            val height = manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 } ?: 180
            var room = height - 40
            personIds.forEach { v.setViewVisibility(it[0], View.GONE) }
            dateIds.forEach { v.setViewVisibility(it, View.GONE) }
            v.setViewVisibility(R.id.circle_dates_title, View.GONE)
            v.setViewVisibility(R.id.circle_message, View.GONE)
            if (locked) {
                // App lock on and the phone locked: counts only, never names.
                val res = ctx.resources
                val text = listOf(
                    res.getQuantityString(R.plurals.c2_widget_count_people, content.people.size, content.people.size),
                    res.getQuantityString(R.plurals.c2_widget_count_dates, content.dates.size, content.dates.size),
                ).joinToString("\n")
                v.setTextViewText(R.id.circle_message, text + "\n" + res.getString(R.string.c2_widget_tap_reveal))
                v.setViewVisibility(R.id.circle_message, View.VISIBLE)
                // A tap re-draws the widget (names return when the phone is unlocked); opening Parley does too.
                val reveal = PendingIntent.getBroadcast(
                    ctx, 7500 + id,
                    Intent(ctx, CircleWidget::class.java).setAction(ACTION_REVEAL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                v.setOnClickPendingIntent(R.id.circle_root, reveal)
                v.setOnClickPendingIntent(R.id.circle_message, reveal)
                return v
            }
            if (content.people.isEmpty() && content.dates.isEmpty()) {
                v.setTextViewText(R.id.circle_message, ctx.getString(R.string.c2_widget_empty))
                v.setViewVisibility(R.id.circle_message, View.VISIBLE)
                return v
            }
            content.people.take(personIds.size).forEachIndexed { i, p ->
                if (room < 40) return@forEachIndexed
                room -= 44
                val (row, name, line, call) = personIds[i].let { listOf(it[0], it[1], it[2], it[3]) }
                v.setViewVisibility(row, View.VISIBLE)
                v.setTextViewText(name, p.name)
                v.setTextViewText(line, p.line)
                v.setOnClickPendingIntent(row, contactIntent(ctx, p.contactId, 7200 + id * 8 + i))
                if (p.phone != null) {
                    v.setViewVisibility(call, View.VISIBLE)
                    v.setContentDescription(call, ctx.getString(R.string.widget_call_name, p.name))
                    v.setOnClickPendingIntent(call, PendingIntent.getActivity(ctx, 7300 + id * 8 + i, Shortcuts.intent(ctx, Shortcuts.Kind.CALL, p.phone, p.contactId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
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
                    v.setTextViewText(dateIds[i], ctx.getString(R.string.c2_widget_date_row, d.name, d.line))
                    v.setOnClickPendingIntent(dateIds[i], contactIntent(ctx, d.contactId, 7400 + id * 8 + i))
                }
            }
            return v
        }

        private fun contactIntent(ctx: Context, contactId: Long, code: Int): PendingIntent = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_CALLER).putExtra(MainActivity.EXTRA_CONTACT_ID, contactId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Keeps the widgets current while Parley runs: after changes to the Circle (contact_meta), interactions, the
         * call history, the contacts or the app-lock setting, and when the screen turns off (names hide) or the phone
         * is unlocked (names return). Called once from the Application.
         */
        @OptIn(FlowPreview::class)
        fun observe(context: Context, c: DataContainer) {
            val ctx = context.applicationContext
            c.scope.launch {
                combine(
                    c.meta.allMeta().map { rows -> rows.map { Triple(it.lookupKey, it.reachOutDays, it.rhythm) } },
                    c.circle.interactions.changes,
                    c.history.index,
                    c.contacts.contacts,
                    c.settings.settings.map { it.appLock },
                ) { a, b, idx, contacts, lock -> listOf(a, b, idx?.calls?.size, contacts?.size, lock) }
                    .drop(1)
                    .debounce(3_000)
                    .collect { if (ids(ctx).isNotEmpty()) runCatching { refresh(ctx) } }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (ids(ctx).isEmpty() || !c.settings.settings.value.appLock) return
                    c.scope.launch { runCatching { refresh(ctx) } }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // System broadcasts only; not exported to other apps.
            runCatching { androidx.core.content.ContextCompat.registerReceiver(ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) }
        }
    }
}
