package app.parley.ui.situations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.TextSearch
import app.parley.common.calls.CheckIns
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RescueRequest
import app.parley.common.calls.RescueWhen
import app.parley.rescue.CheckInHelper
import app.parley.rescue.RescueCalls
import app.parley.ui.Banner
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.InfoRow
import app.parley.ui.LinkRow
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.PersonRow
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.family.FamilyRoutes
import app.parley.ui.rowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Rescue call's screen: who calls (a name, or a contact, whose call shows as theirs would), when, and a sound to hear
 * once answered. Reached from Calls › Situations, Tools, the launcher shortcut and a long press on the Situation tile;
 * never from the home screen. The choices are remembered on this phone only; a duress unlock hides them and the call
 * waiting ([RescueCalls]). Its second mode, Check-in, sets the same call with a helper to offer if it isn't answered.
 */
@Composable
fun RescueCallScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // While a duress unlock hides things: no call waiting and none of the last choices (the waiting call still rings).
    val pending by remember { RescueCalls.shown(context) }.collectAsStateWithLifecycle(null)
    val duress by vm.privacy.collectAsStateWithLifecycle()
    // The stored choices are sealed: opened off the main thread, and only if nothing was changed meanwhile.
    var choices by remember(duress.hiding) { mutableStateOf(RescueCalls.Choices()) }
    LaunchedEffect(duress.hiding) {
        val stored = withContext(Dispatchers.IO) { RescueCalls.choices(context) }
        if (choices == RescueCalls.Choices()) choices = stored
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<Int?>(null) }
    // A call set before the phone restarted, or long past its time, can't ring: say so rather than show it waiting.
    LaunchedEffect(Unit) { if (withContext(Dispatchers.IO) { RescueCalls.refresh(context) }) notice = R.string.rescue_none_waiting }
    fun update(c: RescueCalls.Choices) {
        choices = c
        // Sealed, off the main thread, one write after another.
        scope.launch(saving) { RescueCalls.saveChoices(context, c) }
    }
    // The helpers a check-in can offer, named as they may be shown now (read again as the hiding changes).
    var helpers by remember { mutableStateOf<List<CheckInHelper>>(emptyList()) }
    LaunchedEffect(duress.hiding) { helpers = RescueCalls.helpers(context) }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kept = keepSound(context, uri, choices.clip)
        if (kept == null) notice = R.string.rescue_sound_failed else update(choices.copy(clip = uri.toString(), clipName = kept))
    }

    SettingsScaffold(stringResource(R.string.label_rescue_call), back) {
        ModeChooser(choices.checkIn) { update(withMode(choices, it)) }
        ModeNote(if (choices.checkIn) R.string.checkin_intro else R.string.rescue_intro, intro = true)
        pending?.let { p ->
            Banner(
                pendingText(context, p, helpers), modifier = Modifier.padding(horizontal = Spacing.listInset, vertical = Spacing.s),
                action = stringResource(R.string.rescue_cancel), onAction = { RescueCalls.cancel(context) },
            )
        }
        notice?.let { n ->
            Banner(
                stringResource(n), modifier = Modifier.padding(horizontal = Spacing.listInset, vertical = Spacing.s),
                onDismiss = { notice = null },
            )
        }
        WhoGroup(choices, onName = { update(choices.copy(name = it.take(RescuePlan.MAX_NAME))) }, onPick = { picking = true }) {
            update(choices.copy(number = null))
        }
        WhenGroup(context, choices, onTime = { timeOpen = true }) { update(choices.copy(whenChoice = it)) }
        val helper = offeredHelper(choices, helpers)
        if (choices.checkIn) HelperGroup(helpers, helper, onAdd = { open(FamilyRoutes.Helpers) }) { update(choices.copy(helper = it)) }
        SoundGroup(choices, onPick = { pickSound.launch(arrayOf("audio/*")) }) {
            releaseSound(context, choices.clip)
            update(choices.copy(clip = null, clipName = null))
        }
        SetButton(choices, helper) { set ->
            scope.launch {
                val outcome = RescueCalls.set(context, set)
                notice = if (outcome == RescueCalls.Outcome.REAL_CALL) R.string.rescue_real_call else null
            }
        }
        ModeNote(if (choices.checkIn) R.string.checkin_facts else R.string.rescue_facts, intro = false)
    }
    if (picking) {
        RescueContactPicker(vm, onDismiss = { picking = false }) { c ->
            picking = false
            update(choices.copy(name = c.displayName, number = c.primaryNumber))
        }
    }
    if (timeOpen) {
        TimeDialog(choices.minuteOfDay, onDismiss = { timeOpen = false }) { m ->
            update(choices.copy(whenChoice = RescueWhen.AT_TIME, minuteOfDay = m))
            timeOpen = false
        }
    }
}

/** The sound heard once answered: a file the user picks, or silence. */
@Composable
private fun SoundGroup(choices: RescueCalls.Choices, onPick: () -> Unit, onSilence: () -> Unit) {
    SegmentedGroup(stringResource(R.string.rescue_after)) {
        item("rescue_sound") {
            val sub = choices.clipName ?: stringResource(R.string.action_silence)
            LinkRow(stringResource(R.string.rescue_sound), sub, Icons.Rounded.GraphicEq, onClick = onPick)
        }
        if (choices.clip != null) {
            item("rescue_sound_off") { LinkRow(stringResource(R.string.rescue_sound_off), null, Icons.Rounded.VolumeOff, onClick = onSilence) }
        }
    }
}

/** The clock for "At a time", in the phone's 12- or 24-hour style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minuteOfDay: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, is24Hour = DateFormat.is24HourFormat(context))
    ConfirmDialog(
        title = stringResource(R.string.rescue_time_title),
        text = null,
        confirmLabel = stringResource(R.string.dc_ok),
        onConfirm = { onPick(state.hour * 60 + state.minute) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.dc_cancel),
        content = { TimePicker(state) },
    )
}

/** A name to type, or a contact chosen (then their row, with the way back to a name). */
@Composable
private fun WhoGroup(choices: RescueCalls.Choices, onName: (String) -> Unit, onPick: () -> Unit, onUnpick: () -> Unit) {
    val number = choices.number
    if (number == null) {
        OutlinedTextField(
            choices.name, onName, singleLine = true,
            label = { Text(stringResource(R.string.label_name)) },
            placeholder = { Text(stringResource(R.string.rescue_name_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
    }
    SegmentedGroup(stringResource(R.string.rescue_who)) {
        if (number == null) {
            item("rescue_contact") {
                val sub = stringResource(R.string.rescue_choose_contact_sub)
                LinkRow(stringResource(R.string.action_choose_a_contact), sub, Icons.Rounded.Person, onClick = onPick)
            }
        } else {
            item("rescue_contact") {
                InfoRow(choices.name, Bidi.ltr(number), Icons.Rounded.Person) {
                    IconButton(onUnpick) { Icon(Icons.Rounded.Close, stringResource(R.string.rescue_contact_remove)) }
                }
            }
            item("rescue_contact_change") { LinkRow(stringResource(R.string.action_choose_a_contact), null, onClick = onPick) }
        }
    }
}

/** Now, in 1, 5 or 15 minutes, or at a time (which opens the clock). */
@Composable
private fun WhenGroup(context: Context, choices: RescueCalls.Choices, onTime: () -> Unit, onPick: (RescueWhen) -> Unit) {
    SegmentedGroup(stringResource(R.string.label_when)) {
        (if (choices.checkIn) CheckIns.whenChoices else RescueWhen.entries).forEach { w ->
            item("rescue_when_${w.name}") {
                val selected = choices.whenChoice == w
                val sub = if (w == RescueWhen.AT_TIME) stringResource(R.string.rescue_when_time_sub, timeText(context, choices.minuteOfDay)) else null
                ParleyListItem(
                    headlineContent = { Text(stringResource(whenLabel(w))) },
                    supportingContent = sub?.let { { Text(it) } },
                    leadingContent = { RadioButton(selected, onClick = null) },
                    colors = rowColors(),
                    modifier = Modifier.selectable(selected, role = Role.RadioButton) { if (w == RescueWhen.AT_TIME) onTime() else onPick(w) },
                )
            }
        }
    }
}

private fun whenLabel(w: RescueWhen): Int = when (w) {
    RescueWhen.NOW -> R.string.label_now
    RescueWhen.IN_1 -> R.string.rescue_when_1
    RescueWhen.IN_5 -> R.string.rescue_when_5
    RescueWhen.IN_15 -> R.string.rescue_when_15
    RescueWhen.AT_TIME -> R.string.rescue_when_time
}

/**
 * "Mum calls at 14:05", or for a check-in "Check-in at 23:00… Parley offers to reach Sam", saying it may be late when
 * Parley can't keep the phone awake that long.
 */
private fun pendingText(context: Context, p: RescueRequest, helpers: List<CheckInHelper>): String {
    val at = DateFormat.getTimeFormat(context).format(p.atMillis)
    val late = !RescuePlan.keepsAwake(p.atMillis - System.currentTimeMillis())
    p.helper?.let { h ->
        val who = helpers.firstOrNull { it.number == h }?.name ?: h.takeIf { it.isNotBlank() }?.let(Bidi::ltr)
            ?: context.getString(R.string.checkin_pending_helper)
        return context.getString(if (late) R.string.checkin_pending_late else R.string.checkin_pending, at, who)
    }
    val name = p.name.ifBlank { p.number?.let(Bidi::ltr) ?: context.getString(R.string.rescue_pending_nobody) }
    return context.getString(if (late) R.string.rescue_pending_late else R.string.rescue_pending, name, at)
}

/** [c] switched to Check-in (which never rings now) or back to Rescue call. */
private fun withMode(c: RescueCalls.Choices, checkIn: Boolean): RescueCalls.Choices =
    c.copy(checkIn = checkIn, whenChoice = if (checkIn) CheckIns.whenFor(c.whenChoice) else c.whenChoice)

/** A check-in offers the helper shown as chosen (the first, until another is picked); a rescue call none. */
private fun offeredHelper(choices: RescueCalls.Choices, helpers: List<CheckInHelper>): String? =
    if (!choices.checkIn) null else (helpers.firstOrNull { it.number == choices.helper } ?: helpers.firstOrNull())?.number

/** The mode's words: what it does (above), or what it never does (below the button). */
@Composable
private fun ModeNote(text: Int, intro: Boolean) {
    Text(
        stringResource(text),
        style = if (intro) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (intro) {
            Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s)
        } else {
            Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)
        },
    )
}

/** Ring now, Set the call or Set the check-in: a check-in with no name typed rings as "Check-in", offering [helper]. */
@Composable
private fun SetButton(choices: RescueCalls.Choices, helper: String?, onSet: (RescueCalls.Choices) -> Unit) {
    val checkInName = stringResource(R.string.checkin_caller_default)
    Button(
        onClick = { onSet(if (!choices.checkIn) choices else choices.copy(helper = helper, name = choices.name.ifBlank { checkInName })) },
        enabled = CheckIns.ready(choices.checkIn, choices.name, choices.number, helper),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.l).heightIn(min = 56.dp),
    ) {
        val label = when {
            choices.checkIn -> R.string.checkin_set
            choices.whenChoice == RescueWhen.NOW -> R.string.rescue_ring_now
            else -> R.string.rescue_set
        }
        Text(stringResource(label))
    }
}

/** Rescue call · Check-in: one screen, two modes. Each button is a radio for TalkBack. */
@Composable
private fun ModeChooser(checkIn: Boolean, onPick: (Boolean) -> Unit) {
    val modes = listOf(false to R.string.label_rescue_call, true to R.string.checkin_mode)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s)) {
        modes.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = mode == checkIn,
                onClick = { if (mode != checkIn) onPick(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) { Text(stringResource(label), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The helper a check-in offers if it isn't answered: one of the helpers, or the way to add one. */
@Composable
private fun HelperGroup(helpers: List<CheckInHelper>, selected: String?, onAdd: () -> Unit, onPick: (String) -> Unit) {
    SegmentedGroup(stringResource(R.string.checkin_helper_title)) {
        helpers.forEach { h ->
            item("checkin_helper_${h.number}") {
                val on = h.number == selected
                ParleyListItem(
                    headlineContent = { Text(h.name) },
                    supportingContent = { Text(Bidi.ltr(h.number)) },
                    leadingContent = { RadioButton(on, onClick = null) },
                    colors = rowColors(),
                    modifier = Modifier.selectable(on, role = Role.RadioButton) { onPick(h.number) },
                )
            }
        }
        if (helpers.isEmpty()) {
            item("checkin_helper_add") {
                LinkRow(stringResource(R.string.checkin_helper_add), stringResource(R.string.checkin_helper_add_sub), Icons.Rounded.PersonAdd, onClick = onAdd)
            }
        }
    }
}

private fun timeText(context: Context, minuteOfDay: Int): String {
    val c = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
    }
    return DateFormat.getTimeFormat(context).format(c.time)
}

/** Keeps reading the picked sound after a restart (the picker's grant, no permission); its name, or null when it can't. */
private fun keepSound(context: Context, uri: Uri, previous: String?): String? {
    val cr = context.contentResolver
    val ok = runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
    if (!ok) return null
    if (previous != null && previous != uri.toString()) releaseSound(context, previous)
    return runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}

/** Writes the choices in the order they were made. */
private val saving = Dispatchers.IO.limitedParallelism(1)

/** Lets go of the sound, unless a call waiting will play it once answered. */
private fun releaseSound(context: Context, uri: String?) {
    if (uri == null || RescueCalls.clipInUse(uri)) return
    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

/** Contacts with a number (private ones too, unless hidden), to pick who calls. */
@Composable
private fun RescueContactPicker(vm: AppViewModel, onDismiss: () -> Unit, onPick: (ContactSummary) -> Unit) {
    val all by vm.everyone.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(all, query) {
        all.orEmpty().filter { it.primaryNumber != null && TextSearch.matches(query, it.displayName, it.phones.map { p -> p.number }) }.take(MAX_SHOWN)
    }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rescue_contact_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    query, { query = it }, label = { Text(stringResource(R.string.action_search)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.rescue_no_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.s))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { c ->
                        PersonRow(c.displayName, c.photoUri, modifier = Modifier.clickable { onPick(c) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

private const val MAX_SHOWN = 200
