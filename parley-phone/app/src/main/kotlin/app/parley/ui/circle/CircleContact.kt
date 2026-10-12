package app.parley.ui.circle

import android.provider.ContactsContract.CommonDataKinds.Event
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.EventDate
import app.parley.common.calls.CatchUp
import app.parley.common.circle.CircleFeeds
import app.parley.common.circle.CircleRhythm
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.LabelFeed
import app.parley.common.circle.Seasons
import app.parley.common.circle.Interactions
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.RhythmMode
import app.parley.common.people.LifeEvents
import app.parley.data.ContactDetails
import app.parley.data.circle.Interaction
import app.parley.data.db.ContactMetaEntity
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.extras.LabelRhythmSuggestion
import app.parley.ui.history.RhythmSuggestion
import app.parley.ui.people.eventLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.parley.common.people.Chapters
import app.parley.ui.Spacing
import app.parley.ui.people.chapters.EndDialog
import app.parley.ui.ParleyDialog

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Answered call (incoming or outgoing with talk time): what counts as being in touch by phone. */
private fun CallEntry.answered() = durationSec > 0 && (type == CallType.INCOMING || type == CallType.OUTGOING)

/**
 * The "Stay in touch" card under the contact's actions: the rhythm, when you were last in touch and the next
 * date. Tap to change the rhythm; for someone outside the Circle it offers to add them.
 */
@Composable
fun StayInTouchCard(
    meta: ContactMetaEntity?,
    d: ContactDetails,
    history: List<CallEntry>,
    interactions: List<Interaction>,
    goodTime: String? = null,
    /** Null where a foldable section header already says "Stay in touch". */
    title: String? = stringResource(R.string.label_stay_in_touch),
    /** False where the next date already shows (the contact page's at-a-glance line). */
    showNext: Boolean = true,
    /** False where "Add to your Circle" is offered elsewhere (the contact page's settings group). */
    invite: Boolean = true,
    /** How you two keep in touch (none while a duress unlock hides insights). */
    keep: KeepInTouchLines? = null,
    /** Their next catch-up ([CatchUp.of]): the rhythm stays quiet about them until then. */
    catchUpAt: Long? = null,
    /** The labels keeping them in the Circle ("In your Circle via Family"). */
    feeds: List<LabelFeed> = emptyList(),
    onEdit: () -> Unit,
) {
    val res = LocalResources.current
    val every = meta?.reachOutDays
    val rhythm = KeepRhythm.decode(meta?.rhythm)
    val last = Interactions.lastContact(history.firstOrNull { it.answered() }?.date, interactions.firstOrNull()?.let { it.time to it.type })
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val next = d.events.mapNotNull { ev -> EventDate.parse(ev.date)?.let { ev to it.daysUntil(today) } }
        .filter { it.second <= 60 && !LifeEvents.isDeath(it.first.type, it.first.label) }
        .minByOrNull { it.second }
    val effective = CircleFeeds.effective(every, rhythm, feeds, now)
    SegmentedGroup(title) {
        if (effective == null) {
            if (invite) item("stay") {
                ParleyListItem(
                    modifier = Modifier.clickable(onClick = onEdit),
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.Handshake, null) },
                    headlineContent = { Text(stringResource(R.string.circle_add_to_circle)) },
                    supportingContent = { Text(CircleText.last(res, last, now) + "\n" + stringResource(R.string.circle_add_to_circle_body)) },
                )
            }
        } else {
            item("stay") {
                val quiet = CatchUp.quietUntil(rhythm.snoozedUntil, catchUpAt)
                val status = CirclePlanner.status(CirclePlanner.Member(d.lookupKey, effective.days, last?.time, quiet), now)
                ParleyListItem(
                    modifier = Modifier.clickable(onClick = onEdit),
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.Handshake, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = {
                        Text(every?.let { CircleText.rhythm(res, it, rhythm) } ?: pluralStringResource(R.plurals.circle_every_days, effective.days, effective.days))
                    },
                    // "Every 3 days · until 30 Nov" or "In your Circle via Family" under when you were last in touch.
                    supportingContent = { Text(listOfNotNull(CircleText.last(res, last, now), CircleText.behind(res, effective)).joinToString("\n")) },
                    trailingContent = { StatusChip(status) },
                )
            }
        }
        // "You usually talk about every 3 weeks; it's been 5 weeks. Ana usually reaches out first."
        keep?.let { k -> item("keep") { KeepInTouchRow(k) } }
        // "Usually free 6–9 pm · 7:40 pm there".
        if (goodTime != null) item("good_time") {
            ParleyListItem(colors = rowColors, leadingContent = { Icon(Icons.Rounded.Schedule, null) }, headlineContent = { Text(goodTime) })
        }
        if (showNext && next != null) item("next") {
            val (ev, days) = next
            val label = eventLabel(res, ev)
            ParleyListItem(
                colors = rowColors,
                leadingContent = { Icon(if (ev.type == Event.TYPE_BIRTHDAY) Icons.Rounded.Cake else Icons.Rounded.Event, null) },
                headlineContent = {
                    Text(
                        if (days == 0L) stringResource(R.string.label_named_today, label) else pluralStringResource(
                            R.plurals.circle_next_date_days, days.toInt(), days.toInt(), label,
                        ),
                    )
                },
            )
        }
    }
}

/** How you two keep in touch: the facts, then the last year's summary under them. */
@Composable
private fun KeepInTouchRow(keep: KeepInTouchLines) {
    val lines = listOfNotNull(keep.sentence, keep.summary)
    ParleyListItem(
        colors = rowColors,
        leadingContent = { Icon(Icons.Rounded.Insights, null) },
        headlineContent = { Text(lines.first()) },
        supportingContent = lines.getOrNull(1)?.let { { Text(it) } },
    )
}

/**
 * Choose how often to stay in touch: the rhythm your history suggests, Natural rhythm, a fixed gap, or off.
 * Removing someone from the Circle can be undone.
 */
@Composable
fun RhythmDialog(vm: AppViewModel, d: ContactDetails, contactId: Long, meta: ContactMetaEntity?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    val current = meta?.reachOutDays
    val rhythm = KeepRhythm.decode(meta?.rhythm)
    val circle = vm.c.circle
    var season by rememberSaveable { mutableStateOf(false) }
    if (season) {
        SeasonDialog(rhythm, onDismiss = onDismiss) { days, until ->
            onDismiss()
            scope.launch { circle.setSeason(d.lookupKey, days, until) }
        }
        return
    }
    fun set(days: Int?, natural: Boolean = false) {
        onDismiss()
        scope.launch {
            circle.setRhythm(d.lookupKey, contactId, days, natural)
            if (days == null && current != null) {
                val before = meta
                CircleSnacks.show(CircleSnack(res.getString(R.string.circle_removed, d.displayName)) { circle.restoreMembership(before) })
            }
        }
    }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_keep_in_touch)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // A label's rhythm for people joining the Circle.
                if (current == null) LabelRhythmSuggestion(vm, contactId) { days -> set(days) }
                RhythmSuggestion(vm, d.phones.map { it.value }) { days -> set(days) }
                ParleyListItem(
                    modifier = Modifier.clickable { set(current ?: CirclePlannerDefaults.DAYS, natural = true) },
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.Update, null, tint = if (rhythm.mode == RhythmMode.NATURAL && current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(stringResource(R.string.circle_rhythm_natural)) },
                    supportingContent = { Text(stringResource(R.string.circle_rhythm_natural_body)) },
                )
                HorizontalDivider()
                listOf(
                    7 to stringResource(R.string.detail_every_week), 14 to stringResource(R.string.detail_every_2_weeks),
                    30 to stringResource(
                        R.string.detail_every_month,
                    ), 90 to stringResource(R.string.detail_every_3_months), 180 to stringResource(R.string.detail_every_6_months),
                ).forEach { (days, label) ->
                    val chosen = current == days && rhythm.mode == RhythmMode.EVERY
                    ParleyListItem(
                        headlineContent = { Text(label, color = if (chosen) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                        colors = rowColors,
                        modifier = Modifier.clickable { set(days) },
                    )
                }
                // A season: a closer rhythm with an end, for members (the usual rhythm is what comes back).
                if (current != null) ParleyListItem(
                    modifier = Modifier.clickable { season = true },
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.HourglassBottom, null, tint = if (rhythm.inSeason(System.currentTimeMillis())) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(stringResource(R.string.circle_season_for_a_while)) },
                    supportingContent = {
                        val now = System.currentTimeMillis()
                        Text(
                            if (rhythm.inSeason(now)) {
                                CircleText.behind(res, CircleRhythm(rhythm.seasonDays!!, until = rhythm.seasonUntil)).orEmpty()
                            } else {
                                stringResource(R.string.circle_season_for_a_while_body)
                            },
                        )
                    },
                )
                if (current != null) ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.circle_remove)) },
                    colors = rowColors,
                    modifier = Modifier.clickable { set(null) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/**
 * "For a while": how often for now (every 3 days, every week, every 2 weeks) and until when (a date, or some weeks or
 * months), the Chapter's own end question. A running season can be ended here.
 */
@Composable
private fun SeasonDialog(rhythm: KeepRhythm, onDismiss: () -> Unit, onSet: (Int?, Long?) -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val running = rhythm.inSeason(now)
    var days by rememberSaveable { mutableIntStateOf(rhythm.seasonDays?.takeIf { running } ?: Seasons.CHOICES.first()) }
    val zone = ZoneId.systemDefault()
    EndDialog(
        title = stringResource(R.string.circle_season_title),
        confirm = stringResource(R.string.circle_season_set),
        initial = rhythm.seasonUntil?.takeIf { running }?.let { Instant.ofEpochMilli(it - 1).atZone(zone).toLocalDate() },
        onDismiss = onDismiss,
        onRemove = if (running) ({ onSet(null, null) }) else null,
        removeLabel = stringResource(R.string.circle_season_end_now),
        header = {
            Text(stringResource(R.string.circle_season_how_often), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Seasons.CHOICES.forEach { d ->
                    FilterChip(days == d, { days = d }, label = { Text(pluralStringResource(R.plurals.circle_every_days, d, d)) })
                }
            }
        },
    ) { length -> onSet(days, Chapters.endsAt(length, System.currentTimeMillis(), zone)) }
}

private object CirclePlannerDefaults {
    const val DAYS = 30
}
