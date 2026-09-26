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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.Interactions
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.RhythmMode
import app.parley.data.ContactDetails
import app.parley.data.circle.Interaction
import app.parley.data.db.ContactMetaEntity
import app.parley.ui.SegmentedGroup
import java.time.LocalDate
import kotlinx.coroutines.launch

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Answered call (incoming or outgoing with talk time): what counts as being in touch by phone. */
private fun CallEntry.answered() = durationSec > 0 && (type == CallType.INCOMING || type == CallType.OUTGOING)

/**
 * U4 / R4: the "Stay in touch" card under the contact's actions: the rhythm, when you were last in touch and the next
 * date. Tap to change the rhythm; for someone outside the Circle it offers to add them.
 */
@Composable
fun StayInTouchCard(meta: ContactMetaEntity?, d: ContactDetails, history: List<CallEntry>, interactions: List<Interaction>, goodTime: String? = null, onEdit: () -> Unit) {
    val res = LocalResources.current
    val every = meta?.reachOutDays
    val rhythm = KeepRhythm.decode(meta?.rhythm)
    val last = Interactions.lastContact(history.firstOrNull { it.answered() }?.date, interactions.firstOrNull()?.let { it.time to it.type })
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val next = d.events.mapNotNull { ev -> EventDate.parse(ev.date)?.let { ev to it.daysUntil(today) } }
        .filter { it.second <= 60 && !app.parley.common.people.LifeEvents.isDeath(it.first.type, it.first.label) }
        .minByOrNull { it.second }
    SegmentedGroup(stringResource(R.string.circle_stay_in_touch)) {
        if (every == null) {
            item("stay") {
                ListItem(
                    modifier = Modifier.clickable(onClick = onEdit),
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.Handshake, null) },
                    headlineContent = { Text(stringResource(R.string.circle_add_to_circle)) },
                    supportingContent = { Text(CircleText.last(res, last, now) + "\n" + stringResource(R.string.circle_add_to_circle_body)) },
                )
            }
        } else {
            item("stay") {
                val status = CirclePlanner.status(CirclePlanner.Member(d.lookupKey, rhythm.days(every), last?.time, rhythm.snoozedUntil), now)
                ListItem(
                    modifier = Modifier.clickable(onClick = onEdit),
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.Handshake, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(CircleText.rhythm(res, every, rhythm)) },
                    supportingContent = { Text(CircleText.last(res, last, now)) },
                    trailingContent = { StatusChip(status) },
                )
            }
        }
        // X1: "Usually free 6–9 pm · 7:40 pm there".
        if (goodTime != null) item("good_time") {
            ListItem(colors = rowColors, leadingContent = { Icon(Icons.Rounded.Schedule, null) }, headlineContent = { Text(goodTime) })
        }
        if (next != null) item("next") {
            val (ev, days) = next
            val label = app.parley.ui.people.eventLabel(res, ev)
            ListItem(
                colors = rowColors,
                leadingContent = { Icon(if (ev.type == Event.TYPE_BIRTHDAY) Icons.Rounded.Cake else Icons.Rounded.Event, null) },
                headlineContent = { Text(if (days == 0L) stringResource(R.string.circle_next_date_today, label) else pluralStringResource(R.plurals.circle_next_date_days, days.toInt(), days.toInt(), label)) },
            )
        }
    }
}

/**
 * R4: choose how often to stay in touch: the rhythm your history suggests, Natural rhythm, a fixed gap, or off.
 * Removing someone from the Circle can be undone.
 */
@Composable
fun RhythmDialog(vm: AppViewModel, d: ContactDetails, contactId: Long, meta: ContactMetaEntity?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    val current = meta?.reachOutDays
    val rhythm = KeepRhythm.decode(meta?.rhythm)
    fun set(days: Int?, natural: Boolean = false) {
        onDismiss()
        scope.launch {
            vm.c.circle.setRhythm(d.lookupKey, contactId, days, natural)
            if (days == null && current != null) {
                val before = meta
                CircleSnacks.show(CircleSnack(res.getString(R.string.circle_removed, d.displayName)) { vm.c.circle.restoreMembership(before) })
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_keep_in_touch_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // X3: a label's rhythm for people joining the Circle.
                if (current == null) app.parley.ui.extras.LabelRhythmSuggestion(vm, contactId) { days -> set(days) }
                app.parley.ui.history.RhythmSuggestion(vm, d.phones.map { it.value }) { days -> set(days) }
                ListItem(
                    modifier = Modifier.clickable { set(current ?: CirclePlannerDefaults.DAYS, natural = true) },
                    colors = rowColors,
                    leadingContent = { Icon(Icons.Rounded.Update, null, tint = if (rhythm.mode == RhythmMode.NATURAL && current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(stringResource(R.string.circle_rhythm_natural)) },
                    supportingContent = { Text(stringResource(R.string.circle_rhythm_natural_body)) },
                )
                HorizontalDivider()
                listOf(
                    7 to stringResource(R.string.detail_every_week), 14 to stringResource(R.string.detail_every_2_weeks),
                    30 to stringResource(R.string.detail_every_month), 90 to stringResource(R.string.detail_every_3_months), 180 to stringResource(R.string.detail_every_6_months),
                ).forEach { (days, label) ->
                    val chosen = current == days && rhythm.mode == RhythmMode.EVERY
                    ListItem(
                        headlineContent = { Text(label, color = if (chosen) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                        colors = rowColors,
                        modifier = Modifier.clickable { set(days) },
                    )
                }
                if (current != null) ListItem(
                    headlineContent = { Text(stringResource(R.string.circle_remove)) },
                    colors = rowColors,
                    modifier = Modifier.clickable { set(null) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}

private object CirclePlannerDefaults {
    const val DAYS = 30
}
