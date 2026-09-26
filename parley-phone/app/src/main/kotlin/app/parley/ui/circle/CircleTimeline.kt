package app.parley.ui.circle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.EventDate
import app.parley.common.circle.InteractionType
import app.parley.common.circle.Interactions
import app.parley.common.circle.Timeline
import app.parley.common.circle.TimelineEntry
import app.parley.data.ContactDetails
import app.parley.data.EventItem
import app.parley.data.circle.Interaction
import app.parley.data.circle.InteractionStore
import app.parley.data.db.CallNoteEntity
import app.parley.ui.Bidi
import app.parley.ui.SegmentedGroup
import app.parley.ui.common.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

private val clearRow @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * R2: log a meeting, message, video call or anything else with an optional note ([initial] null), or edit an entry.
 * An edit keeps the entry's time unless a new day is picked (then the time of day stays).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogInteractionDialog(name: String, initial: Interaction?, onDismiss: () -> Unit, onSave: (InteractionType, String?, Long) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    var type by rememberSaveable { mutableStateOf(initial?.type ?: InteractionType.MEET) }
    var note by rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial?.note.orEmpty())) }
    var time by rememberSaveable { mutableLongStateOf(initial?.time ?: System.currentTimeMillis()) }
    var picking by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.circle_log_with, name) else stringResource(R.string.circle_edit_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InteractionType.entries.forEach { t ->
                        FilterChip(type == t, { type = t }, label = { Text(CircleText.type(res, t)) }, leadingIcon = { Icon(CircleText.typeIcon(t), null) })
                    }
                }
                // R9: the checkbox button starts a promise line.
                PromiseNoteField(note, { note = it }, label = stringResource(R.string.circle_note))
                ListItem(
                    modifier = Modifier.clickable { picking = true },
                    colors = clearRow,
                    leadingContent = { Icon(Icons.Rounded.Event, null) },
                    headlineContent = { Text(Format.fullDate(context, time)) },
                    supportingContent = { Text(stringResource(R.string.circle_when)) },
                )
            }
        },
        confirmButton = { TextButton({ onSave(type, note.text.trim().ifEmpty { null }, time) }) { Text(stringResource(R.string.main_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
    if (picking) {
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(time).atZone(zone)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= now
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        // Another day, same time of day; never in the future.
                        time = minOf(date.atTime(day.toLocalTime()).atZone(zone).toInstant().toEpochMilli(), now)
                    }
                    picking = false
                }) { Text(stringResource(R.string.dc_ok)) }
            },
            dismissButton = { TextButton({ picking = false }) { Text(stringResource(R.string.main_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** Saves a new entry (or an edit) and says so; a note that can't be encrypted isn't saved. */
suspend fun saveInteraction(vm: AppViewModel, d: ContactDetails, contactId: Long, initial: Interaction?, type: InteractionType, note: String?, time: Long) {
    val res = vm.getApplication<android.app.Application>().resources
    try {
        if (initial == null) {
            vm.c.circle.interactions.log(d.lookupKey, contactId, type, null, time, note, Interactions.manualKey(UUID.randomUUID().toString()))
            vm.toast(res.getString(R.string.circle_logged, d.given.ifBlank { d.displayName }))
        } else {
            vm.c.circle.interactions.edit(initial.id, type, note, time.takeIf { it != initial.time })
        }
    } catch (_: InteractionStore.SealException) {
        vm.toast(res.getString(R.string.circle_note_failed))
    }
}

/**
 * R2: the contact's timeline: calls, logged interactions, call notes and dates, newest first, one group per month.
 * Three months show at first; "Show earlier" adds more. Logged entries can be edited or deleted (with Undo).
 */
@Composable
fun ContactTimeline(
    vm: AppViewModel,
    d: ContactDetails,
    history: List<CallEntry>,
    interactions: List<Interaction>,
    notes: List<CallNoteEntity>,
    onEdit: (Interaction) -> Unit,
    onAllCalls: (() -> Unit)?,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var months by rememberSaveable { mutableIntStateOf(3) }
    val grouped = remember(history, interactions, notes, d.events) {
        val entries = history.map { TimelineEntry.Call(it) } +
            interactions.map { TimelineEntry.Logged(it.id, it.time, it.type, it.channel, it.note) } +
            notes.map { TimelineEntry.Note(it.id, it.callDate, it.text) }
        val dates = Timeline.dates(
            d.events.filterNot { app.parley.common.people.LifeEvents.isDeath(it.type, it.label) }.mapNotNull { e -> EventDate.parse(e.date)?.let { Triple(e.type, e.label, it) } },
            entries, LocalDate.now(), zone,
        )
        Timeline.group(entries + dates, zone)
    }
    val monthFormat = remember { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMyyyy")) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (grouped.isEmpty()) {
            SegmentedGroup(stringResource(R.string.circle_timeline)) {
                item { ListItem(colors = clearRow, headlineContent = { Text(stringResource(R.string.circle_timeline_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }) }
            }
        }
        grouped.take(months).forEachIndexed { i, m ->
            val title = m.month.atDay(1).format(monthFormat)
            SegmentedGroup(if (i == 0) stringResource(R.string.circle_timeline) + stringResource(R.string.main_separator) + title else title) {
                m.entries.forEach { e ->
                    item {
                        when (e) {
                            is TimelineEntry.Call -> {
                                ListItem(
                                    colors = clearRow,
                                    leadingContent = { app.parley.ui.home.CallTypeIcon(e.call.type, durationSec = e.call.durationSec) },
                                    trailingContent = { app.parley.ui.home.CallLengthGlance(e.call) },
                                    headlineContent = { Text(Format.fullDate(context, e.time)) },
                                    supportingContent = {
                                        Text(listOf(Bidi.ltr(Format.number(e.call.number, vm.countryIso)), Format.duration(e.call.durationSec)).filter { it.isNotBlank() }.joinToString(stringResource(R.string.main_separator)))
                                    },
                                )
                            }
                            is TimelineEntry.Logged -> LoggedRow(e, interactions.firstOrNull { it.id == e.id }, onEdit) { item ->
                                scope.launch {
                                    val gone = vm.c.circle.interactions.delete(item.id) ?: return@launch
                                    CircleSnacks.show(CircleSnack(res.getString(R.string.circle_entry_deleted)) { vm.c.circle.interactions.restore(gone) })
                                }
                            }
                            is TimelineEntry.Note -> ListItem(
                                colors = clearRow,
                                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
                                headlineContent = { app.parley.ui.contact.LinkifiedText(e.text) },
                                supportingContent = { Text(stringResource(R.string.circle_call_note) + stringResource(R.string.main_separator) + Format.fullDate(context, e.time)) },
                            )
                            is TimelineEntry.Date -> ListItem(
                                colors = clearRow,
                                leadingContent = { Icon(if (e.type == android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) Icons.Rounded.Cake else Icons.Rounded.Event, null) },
                                headlineContent = { Text(app.parley.ui.people.eventLabel(res, EventItem(date = e.date.format(), type = e.type, label = e.label))) },
                                supportingContent = { Text(Instant.ofEpochMilli(e.time).atZone(zone).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG))) },
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(start = 16.dp)) {
            if (grouped.size > months) TextButton({ months += 6 }) { Text(stringResource(R.string.circle_timeline_more)) }
            onAllCalls?.let { TextButton(it) { Text(stringResource(R.string.circle_all_calls)) } }
        }
    }
}

@Composable
private fun LoggedRow(e: TimelineEntry.Logged, item: Interaction?, onEdit: (Interaction) -> Unit, onDelete: (Interaction) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    val sep = stringResource(R.string.main_separator)
    val headline = CircleText.type(res, e.type) + (e.channel?.let { sep + CircleText.channel(res, it) } ?: "")
    ListItem(
        modifier = Modifier.clickable(enabled = item != null) { item?.let(onEdit) },
        colors = clearRow,
        leadingContent = { Icon(CircleText.typeIcon(e.type), null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(headline) },
        supportingContent = {
            Column {
                Text(Format.fullDate(context, e.time))
                e.note?.let { app.parley.ui.contact.LinkifiedText(it) }
            }
        },
        trailingContent = if (item == null) null else ({
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.main_edit)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit(item) })
                    DropdownMenuItem({ Text(stringResource(R.string.main_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete(item) })
                }
            }
        }),
    )
}
