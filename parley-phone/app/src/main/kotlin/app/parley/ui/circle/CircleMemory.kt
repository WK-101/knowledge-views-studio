package app.parley.ui.circle

import app.parley.calls.AgendaTicks
import app.parley.calls.ExpectedCallHints
import app.parley.data.circle.AgendaTarget
import android.app.Application
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.CallEntry
import app.parley.common.circle.GoodTime
import app.parley.common.circle.Promises
import app.parley.data.NumberInfo
import app.parley.data.circle.CircleRepository.NoteSource
import app.parley.data.circle.CircleRepository.PersonNote
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.common.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import app.parley.ui.ParleySheet
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

private val clearRow @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** What Parley remembers about a person: every note, the newest one and the open promises. */
data class PersonMemory(val notes: List<PersonNote> = emptyList()) {
    /** The newest dated note (the pinned note shows on its own). */
    val lastNote: PersonNote? get() = notes.firstOrNull { it.source != NoteSource.PINNED }

    /** Open promises, newest note first (the agenda's items last, with the note for calls). */
    val promises: List<Pair<PersonNote, Promises.Item>> get() = notes.flatMap { n -> Promises.open(n.text).map { n to it } }

    /** The agenda: things to talk about, the open items of the note for calls ([app.parley.common.circle.Agenda]). */
    val agenda: List<Pair<PersonNote, Promises.Item>> get() = promises.filter { it.first.source == NoteSource.PINNED }

    /** Open promises in the other notes (call notes, logged chats and visits). */
    val owed: List<Pair<PersonNote, Promises.Item>> get() = promises.filter { it.first.source != NoteSource.PINNED }
}

/**
 * A note field with the checkbox button, which starts a promise line ("[ ] "), and a one-line hint explaining
 * the convention.
 */
@Composable
fun PromiseNoteField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, label: String? = null, placeholder: String? = null, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange,
        modifier = modifier,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        minLines = 2,
        trailingIcon = {
            IconButton({
                val (text, cursor) = Promises.insertBox(value.text, value.selection.start)
                onChange(TextFieldValue(text, TextRange(cursor)))
            }) { Icon(Icons.Rounded.CheckBox, stringResource(R.string.circle_insert_promise)) }
        },
        supportingText = { Text(stringResource(R.string.circle_promise_hint)) },
    )
}

/**
 * Ticks a promise off (or back on), with Undo. The note for calls holds the agenda: its items are ticked by their text
 * where that note is kept (a private contact's is inside its sealed entry, not in Parley's table).
 */
suspend fun tickPromise(vm: AppViewModel, lookupKey: String, note: PersonNote, item: Promises.Item, done: Boolean) {
    val res = vm.getApplication<Application>().resources
    val agenda = if (note.source == NoteSource.PINNED) AgendaTarget.forKey(lookupKey) else null
    if (agenda != null) {
        if (!AgendaTicks.setDone(vm.c, agenda, item.text, done)) return
        if (done) CircleSnacks.show(CircleSnack(res.getString(R.string.circle_promise_done, item.text)) { AgendaTicks.setDone(vm.c, agenda, item.text, false) })
        return
    }
    if (!vm.c.circle.setPromiseDone(lookupKey, note, item.line, done)) return
    // A promise of a call that is done no longer lets anyone ring through.
    runCatching { ExpectedCallHints.promiseTicked(vm.c, lookupKey, note) }
    if (done) {
        val after = note.copy(text = Promises.setDone(note.text, item.line, true))
        CircleSnacks.show(CircleSnack(res.getString(R.string.circle_promise_done, item.text)) {
            vm.c.circle.setPromiseDone(lookupKey, after, item.line, false)
            runCatching { ExpectedCallHints.promiseTicked(vm.c, lookupKey, after) }
        })
    }
}

/**
 * The open promises on the contact's page, each with a box to tick off. Nothing shows without any. The agenda's items
 * (the note for calls) show with that note instead.
 */
@Composable
fun PromisesCard(vm: AppViewModel, lookupKey: String, memory: PersonMemory) {
    val promises = memory.owed
    if (promises.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SegmentedGroup(stringResource(R.string.circle_promises)) {
        promises.forEach { (note, p) ->
            item {
                ParleyListItem(
                    colors = clearRow,
                    leadingContent = { Checkbox(false, { scope.launch { tickPromise(vm, lookupKey, note, p, true) } }) },
                    headlineContent = { Text(p.text) },
                    supportingContent = { Text(sourceText(LocalResources.current, note) { Format.fullDate(context, it) }) },
                )
            }
        }
    }
}

private fun sourceText(res: Resources, note: PersonNote, date: (Long) -> String): String = when (note.source) {
    NoteSource.PINNED -> res.getString(R.string.circle_from_pinned)
    NoteSource.CALL -> res.getString(R.string.circle_from_call, date(note.time))
    NoteSource.LOGGED -> res.getString(R.string.circle_from_logged, date(note.time))
}

/**
 * "Usually free 6–9 pm · 7:40 pm there" from the calls with this person ([calls], any of their numbers). Their
 * time zone comes from [number] (offline, by country and area code); the time there is only added when it differs
 * from yours. Null with fewer than eight answered calls or no clear pattern.
 */
fun goodTimeText(res: Resources, calls: List<CallEntry>, number: String?, countryIso: String, now: Long = System.currentTimeMillis()): String? {
    val mine = ZoneId.systemDefault()
    val theirs = NumberInfo.timeZone(number, countryIso, now)
    val w = GoodTime.window(calls, theirs ?: mine, now) ?: return null
    val locale = Locale.getDefault()
    val hour = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "j"), locale)
    val time = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "jmm"), locale)
    val day = LocalDate.now()
    val range = res.getString(R.string.circle_good_time_range, day.atTime(w.startHour, 0).format(hour), day.atTime(w.endOfDay, 0).format(hour))
    val free = res.getString(R.string.circle_good_time, range)
    if (!GoodTime.differs(theirs, mine, now)) return free
    return free + res.getString(R.string.main_separator) + res.getString(R.string.circle_time_there, Instant.ofEpochMilli(now).atZone(theirs).format(time))
}

/** Whether the pre-call peek has anything to say. */
fun hasPeek(memory: PersonMemory, goodTime: String?): Boolean = memory.lastNote != null || memory.promises.isNotEmpty() || goodTime != null

/**
 * The pre-call peek before dialling from a contact's page: a good time to call, the last note, the things to talk about
 * and the open promises (tick them off right here), an organisation's case file, then Call. It can be turned off from the sheet or in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // A sheet's inputs and callbacks, each one used.
fun PreCallPeekSheet(
    vm: AppViewModel,
    lookupKey: String,
    name: String,
    memory: PersonMemory,
    goodTime: String?,
    onCall: () -> Unit,
    onDismiss: () -> Unit,
    /** More to see before calling (an organisation's case file), under the title. */
    extra: @Composable () -> Unit = {},
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    ParleySheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.circle_peek_title, name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            )
            extra()
            goodTime?.let {
                ParleyListItem(colors = clearRow, leadingContent = { Icon(Icons.Rounded.Schedule, null) }, headlineContent = { Text(it) })
            }
            memory.lastNote?.let { n ->
                ParleyListItem(
                    colors = clearRow,
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
                    headlineContent = { Text(Promises.preview(n.text), maxLines = 4, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(sourceText(res, n) { Format.fullDate(context, it) }) },
                )
            }
            // Things to talk about first: this call is the time for them.
            (memory.agenda + memory.owed).forEach { (note, p) ->
                ParleyListItem(
                    colors = clearRow,
                    leadingContent = { Checkbox(false, { scope.launch { tickPromise(vm, lookupKey, note, p, true) } }) },
                    headlineContent = { Text(p.text) },
                    supportingContent = {
                        Text(stringResource(if (note.source == NoteSource.PINNED) R.string.agenda_title else R.string.circle_open_promise))
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton({
                    vm.c.circle.updateConfig { it.copy(preCallPeek = false) }
                    vm.toast(res.getString(R.string.circle_peek_off))
                    onCall()
                }) { Text(stringResource(R.string.circle_peek_dont_show)) }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) }
                Button(onCall) {
                    Icon(Icons.Rounded.Call, null, Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.circle_widget_call))
                }
            }
        }
    }
}
