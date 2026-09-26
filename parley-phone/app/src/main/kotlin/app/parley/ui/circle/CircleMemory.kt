package app.parley.ui.circle

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
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
import app.parley.ui.SegmentedGroup
import app.parley.ui.common.Format
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val clearRow @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** R8/R9: what Parley remembers about a person: every note, the newest one and the open promises. */
data class PersonMemory(val notes: List<PersonNote> = emptyList()) {
    /** The newest dated note (the pinned note shows on its own). */
    val lastNote: PersonNote? get() = notes.firstOrNull { it.source != NoteSource.PINNED }

    /** Open promises, newest note first. */
    val promises: List<Pair<PersonNote, Promises.Item>> get() = notes.flatMap { n -> Promises.open(n.text).map { n to it } }
}

/** Loads [lookupKey]'s notes, again whenever one of [keys] changes (call notes, interactions, contact meta). */
@Composable
fun rememberPersonMemory(vm: AppViewModel, lookupKey: String, numberKeys: Set<String>, vararg keys: Any?): State<PersonMemory> =
    produceState(PersonMemory(), lookupKey, numberKeys, *keys) {
        value = PersonMemory(runCatching { vm.c.circle.notesFor(lookupKey, numberKeys) }.getOrDefault(emptyList()))
    }

/**
 * R9: a note field with the checkbox button, which starts a promise line ("[ ] "), and a one-line hint explaining
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
            }) { Icon(Icons.Rounded.CheckBox, stringResource(R.string.c2_insert_promise)) }
        },
        supportingText = { Text(stringResource(R.string.c2_promise_hint)) },
    )
}

/** R9: ticks a promise off (or back on), with Undo. */
suspend fun tickPromise(vm: AppViewModel, lookupKey: String, note: PersonNote, item: Promises.Item, done: Boolean) {
    val res = vm.getApplication<android.app.Application>().resources
    if (!vm.c.circle.setPromiseDone(lookupKey, note, item.line, done)) return
    if (done) {
        val after = note.copy(text = Promises.setDone(note.text, item.line, true))
        CircleSnacks.show(CircleSnack(res.getString(R.string.c2_promise_done, item.text)) { vm.c.circle.setPromiseDone(lookupKey, after, item.line, false) })
    }
}

/** R9: the open promises on the contact's page, each with a box to tick off. Nothing shows without any. */
@Composable
fun PromisesCard(vm: AppViewModel, lookupKey: String, memory: PersonMemory) {
    val promises = memory.promises
    if (promises.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SegmentedGroup(stringResource(R.string.c2_promises)) {
        promises.forEach { (note, p) ->
            item {
                ListItem(
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
    NoteSource.PINNED -> res.getString(R.string.c2_from_pinned)
    NoteSource.CALL -> res.getString(R.string.c2_from_call, date(note.time))
    NoteSource.LOGGED -> res.getString(R.string.c2_from_logged, date(note.time))
}

/**
 * X1: "Usually free 6–9 pm · 7:40 pm there" from the calls with this person ([calls], any of their numbers). Their
 * time zone comes from [number] (offline, by country and area code); the time there is only added when it differs
 * from yours. Null with fewer than eight answered calls or no clear pattern.
 */
fun goodTimeText(res: Resources, calls: List<CallEntry>, number: String?, countryIso: String, now: Long = System.currentTimeMillis()): String? {
    val mine = ZoneId.systemDefault()
    val theirs = NumberInfo.timeZone(number, countryIso, now)
    val w = GoodTime.window(calls, theirs ?: mine, now) ?: return null
    val locale = Locale.getDefault()
    val hour = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "j"), locale)
    val time = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "jmm"), locale)
    val day = java.time.LocalDate.now()
    val range = res.getString(R.string.c2_good_time_range, day.atTime(w.startHour, 0).format(hour), day.atTime(w.endOfDay, 0).format(hour))
    val free = res.getString(R.string.c2_good_time, range)
    if (!GoodTime.differs(theirs, mine, now)) return free
    return free + res.getString(R.string.main_separator) + res.getString(R.string.c2_time_there, Instant.ofEpochMilli(now).atZone(theirs).format(time))
}

/** Whether the pre-call peek has anything to say. */
fun hasPeek(memory: PersonMemory, goodTime: String?): Boolean = memory.lastNote != null || memory.promises.isNotEmpty() || goodTime != null

/**
 * R8/R9/X1: the pre-call peek before dialling from a contact's page: a good time to call, the last note and the open
 * promises (tick them off right here), then Call. It can be turned off from the sheet or in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreCallPeekSheet(vm: AppViewModel, lookupKey: String, name: String, memory: PersonMemory, goodTime: String?, onCall: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.c2_peek_title, name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            goodTime?.let {
                ListItem(colors = clearRow, leadingContent = { Icon(Icons.Rounded.Schedule, null) }, headlineContent = { Text(it) })
            }
            memory.lastNote?.let { n ->
                ListItem(
                    colors = clearRow,
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
                    headlineContent = { Text(Promises.preview(n.text), maxLines = 4, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(sourceText(res, n) { Format.fullDate(context, it) }) },
                )
            }
            memory.promises.forEach { (note, p) ->
                ListItem(
                    colors = clearRow,
                    leadingContent = { Checkbox(false, { scope.launch { tickPromise(vm, lookupKey, note, p, true) } }) },
                    headlineContent = { Text(p.text) },
                    supportingContent = { Text(stringResource(R.string.c2_open_promise)) },
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    vm.c.circle.updateConfig { it.copy(preCallPeek = false) }
                    vm.toast(res.getString(R.string.c2_peek_off))
                    onCall()
                }) { Text(stringResource(R.string.c2_peek_dont_show)) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) }
                Button(onCall) {
                    Icon(Icons.Rounded.Call, null, Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.main_call))
                }
            }
        }
    }
}
