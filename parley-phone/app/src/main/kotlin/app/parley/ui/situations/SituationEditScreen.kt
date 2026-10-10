package app.parley.ui.situations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Quickreply
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.LabelRefs
import app.parley.common.SimAccount
import app.parley.common.catching
import app.parley.common.calls.SpeakerDefault
import app.parley.common.situations.DeviceTrigger
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationRing
import app.parley.telecom.CarAudio
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.MenuRow
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.blocking.ScheduleField
import app.parley.ui.drive.DriveRoutes
import app.parley.ui.drive.driveSummary
import app.parley.ui.rowColors
import app.parley.situations.SituationTriggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a Situation sets while it is on, and when it switches on by itself. Every behaviour starts "As it is" (left
 * alone); each change is kept at once, and the Situation on now takes it straight away.
 */
@Composable
fun SituationEditScreen(vm: AppViewModel, id: String, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val c = vm.c
    val list by c.situations.list.collectAsStateWithLifecycle()
    val s = list.firstOrNull { it.id == id }
    // Deleted (or never there): nothing to show.
    LaunchedEffect(s == null) { if (s == null) back() }
    if (s == null) return
    fun edit(f: (Situation) -> Situation) {
        c.scope.launch { c.situations.edit(id) { f(it) } }
    }
    val name = SituationTriggers.name(context, s)
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(name, back) {
        Text(
            stringResource(R.string.sit_editor_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        SegmentedGroup {
            item("sit_name") { LinkRow(stringResource(R.string.sit_rename), name, Icons.Rounded.Edit) { renaming = true } }
        }
        WhileOn(vm, s, ::edit)
        InTheCar(vm, s, open, ::edit)
        AbroadAndSims(vm, s, ::edit)
        TurnsOn(s, ::edit)
        SegmentedGroup {
            item("sit_remove") {
                if (s.builtIn) {
                    LinkRow(stringResource(R.string.sit_reset), null, Icons.AutoMirrored.Rounded.Undo) { removing = true }
                } else {
                    LinkRow(stringResource(R.string.sit_delete), null, Icons.Rounded.Delete) { removing = true }
                }
            }
        }
    }
    if (renaming) {
        NameDialog(stringResource(R.string.sit_rename), name, onDismiss = { renaming = false }) { n ->
            renaming = false
            edit { it.copy(name = n) }
        }
    }
    if (removing) {
        ConfirmDialog(
            title = stringResource(if (s.builtIn) R.string.sit_reset_title else R.string.sit_delete_title, name),
            text = stringResource(R.string.sit_delete_body),
            confirmLabel = stringResource(if (s.builtIn) R.string.sit_reset_confirm else R.string.sit_delete_confirm),
            destructive = !s.builtIn,
            onConfirm = {
                removing = false
                c.scope.launch { c.situations.delete(id) }
            },
            onDismiss = { removing = false },
        )
    }
}

/** "As it is", "On", "Off" for a switch a Situation may set. */
@Composable
private fun triOptions() = listOf(stringResource(R.string.sit_as_is), stringResource(R.string.sit_turn_on), stringResource(R.string.sit_turn_off))

private fun triIndex(v: Boolean?): Int = when (v) {
    null -> 0
    true -> 1
    false -> 2
}

private fun triValue(i: Int): Boolean? = when (i) {
    1 -> true
    2 -> false
    else -> null
}

@Composable
private fun TriRow(title: String, value: Boolean?, icon: ImageVector, sub: String? = null, onPick: (Boolean?) -> Unit) {
    MenuRow(title, triOptions(), triIndex(value), icon, sub) { onPick(triValue(it)) }
}

/** Where [s]'s "Who may ring" is in its menu: As it is, Everyone, Contacts, Favourites, then [labels]. */
private fun ringIndex(s: Situation, labels: List<String>): Int = when (s.ring) {
    null -> 0
    SituationRing.EVERYONE -> 1
    SituationRing.CONTACTS -> 2
    SituationRing.FAVOURITES -> 3
    SituationRing.LABEL -> 4 + labels.indexOfFirst { LabelRefs.key(it) == s.ringLabel?.let(LabelRefs::key) }.coerceAtLeast(0)
}

/** Who may ring, the reply, the speaker and auto-answer. */
@Composable
private fun WhileOn(vm: AppViewModel, s: Situation, edit: ((Situation) -> Situation) -> Unit) {
    // Title → a group row with it, so the Situation follows the label if it's renamed in any app.
    val labelRows by produceState(emptyMap<String, Long>()) {
        value = withContext(Dispatchers.IO) {
            catching { vm.c.contacts.groups().groupBy { LabelRefs.key(it.title) }.mapValues { (_, g) -> g.minOf { it.id } } }.getOrDefault(emptyMap())
        }
    }
    val labels = labelRows.keys.toList()
    // A label chosen earlier is offered even while the labels are read (or if it went).
    val labelChoices = (listOfNotNull(s.ringLabel?.takeIf { s.ring == SituationRing.LABEL }) + labels).distinctBy { LabelRefs.key(it) }
    val ringOptions = listOf(
        stringResource(R.string.sit_as_is), stringResource(R.string.sit_ring_everyone), stringResource(R.string.sit_ring_contacts),
        stringResource(R.string.sit_ring_favourites),
    ) + labelChoices
    val ringIndex = ringIndex(s, labelChoices)
    val speakerOptions = listOf(
        stringResource(R.string.sit_as_is), stringResource(R.string.set_speaker_never), stringResource(R.string.set_speaker_always),
        stringResource(R.string.set_speaker_unknown),
    )
    var replying by rememberSaveable { mutableStateOf(false) }
    val answerSub = stringResource(R.string.sit_auto_answer_sub)
    SegmentedGroup(stringResource(R.string.sit_group_sets)) {
        item("sit_ring") {
            val ringSub = labelGone(LocalContext.current, s) ?: stringResource(R.string.sit_ring_sub)
            MenuRow(stringResource(R.string.sit_ring), ringOptions, ringIndex, Icons.Rounded.People, ringSub) { i ->
                edit {
                    when (i) {
                        0 -> it.copy(ring = null, ringLabel = null, ringLabelId = null, ringLabelGone = false)
                        1 -> it.copy(ring = SituationRing.EVERYONE, ringLabel = null, ringLabelId = null, ringLabelGone = false)
                        2 -> it.copy(ring = SituationRing.CONTACTS, ringLabel = null, ringLabelId = null, ringLabelGone = false)
                        3 -> it.copy(ring = SituationRing.FAVOURITES, ringLabel = null, ringLabelId = null, ringLabelGone = false)
                        else -> labelChoices.getOrNull(i - 4).let { t ->
                            it.copy(ring = SituationRing.LABEL, ringLabel = t, ringLabelId = t?.let { k -> labelRows[LabelRefs.key(k)] }, ringLabelGone = false)
                        }
                    }
                }
            }
        }
        item("sit_reply") {
            LinkRow(
                stringResource(R.string.sit_reply), s.reply?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sit_reply_none),
                Icons.Rounded.Quickreply,
            ) { replying = true }
        }
        item("sit_reply_silenced") {
            TriRow(stringResource(R.string.sit_reply_silenced), s.replyToSilenced, Icons.Rounded.Sms, stringResource(R.string.sit_reply_silenced_sub)) { v ->
                edit { it.copy(replyToSilenced = v) }
            }
        }
        item("sit_speaker") {
            MenuRow(stringResource(R.string.sit_speaker), speakerOptions, s.speaker?.let { it.ordinal + 1 } ?: 0, Icons.AutoMirrored.Rounded.VolumeUp) { i ->
                edit { it.copy(speaker = if (i == 0) null else SpeakerDefault.entries[i - 1]) }
            }
        }
        item("sit_auto_headset") {
            TriRow(stringResource(R.string.sit_auto_headset), s.autoAnswerHeadset, Icons.Rounded.Headset, answerSub) { v ->
                edit { it.copy(autoAnswerHeadset = v) }
            }
        }
        item("sit_auto_chosen") {
            TriRow(stringResource(R.string.sit_auto_chosen), s.autoAnswerChosen, Icons.Rounded.PhoneInTalk, answerSub) { v ->
                edit { it.copy(autoAnswerChosen = v) }
            }
        }
    }
    if (replying) ReplyDialog(s.reply.orEmpty(), onDismiss = { replying = false }) { text ->
        replying = false
        edit { it.copy(reply = text.trim().takeIf(String::isNotEmpty)) }
    }
}

@Composable
private fun ReplyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    ConfirmDialog(
        title = stringResource(R.string.sit_reply_title),
        text = stringResource(R.string.sit_reply_body),
        confirmLabel = stringResource(R.string.sit_save),
        onConfirm = { onSave(text) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(text, { text = it.take(MAX_REPLY) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    }
}

private const val MAX_REPLY = 160

/** The drive profile's switches (they act only while the car is connected) and the way to the cars. */
@Composable
private fun InTheCar(vm: AppViewModel, s: Situation, open: (Destination) -> Unit, edit: ((Situation) -> Situation) -> Unit) {
    val drive by vm.c.driveProfile.config.collectAsStateWithLifecycle()
    val sub = stringResource(R.string.sit_drive_sub)
    SegmentedGroup(stringResource(R.string.sit_group_car)) {
        item("sit_drive_announce") {
            TriRow(stringResource(R.string.sit_drive_announce), s.driveAnnounce, Icons.Rounded.RecordVoiceOver, sub) { v ->
                edit { it.copy(driveAnnounce = v) }
            }
        }
        item("sit_drive_favourites") {
            TriRow(stringResource(R.string.sit_drive_favourites), s.driveAnswerFavourites, Icons.Rounded.Star, sub) { v ->
                edit { it.copy(driveAnswerFavourites = v) }
            }
        }
        item("sit_drive_silence") {
            TriRow(stringResource(R.string.sit_drive_silence), s.driveSilenceUnknown, Icons.Rounded.NotificationsOff, sub) { v ->
                edit { it.copy(driveSilenceUnknown = v) }
            }
        }
        item("sit_drive_cars") {
            val carSub = if (drive.enabled) driveSummary(drive) else stringResource(R.string.sit_drive_no_car)
            LinkRow(stringResource(R.string.sit_drive_cars), carSub, Icons.Rounded.DirectionsCar) { open(DriveRoutes.Profile) }
        }
    }
}

/** Assisted dialling, the local-SIM hint and the SIM for calls. */
@Composable
private fun AbroadAndSims(vm: AppViewModel, s: Situation, edit: ((Situation) -> Situation) -> Unit) {
    val sims by produceState(emptyList<SimAccount>()) {
        value = withContext(Dispatchers.IO) { catching { vm.c.sims.accounts() }.getOrDefault(emptyList()) }
    }
    val missing = s.simId?.takeIf { id -> sims.none { it.id == id } }
    val simOptions = listOf(stringResource(R.string.sit_as_is)) + sims.map { it.label } +
        listOfNotNull(missing?.let { stringResource(R.string.sit_sim_missing, s.simLabel ?: it) })
    val simIndex = when {
        s.simId == null -> 0
        missing != null -> simOptions.lastIndex
        else -> 1 + sims.indexOfFirst { it.id == s.simId }
    }
    SegmentedGroup(stringResource(R.string.sit_group_abroad)) {
        item("sit_assisted") {
            TriRow(stringResource(R.string.sit_assisted), s.assistedDialling, Icons.Rounded.Public) { v -> edit { it.copy(assistedDialling = v) } }
        }
        item("sit_local_sim") {
            TriRow(stringResource(R.string.sit_local_sim), s.localSimHint, Icons.Rounded.SimCard) { v -> edit { it.copy(localSimHint = v) } }
        }
        // Only with a choice to make: two SIMs or more, or one chosen earlier.
        if (sims.size > 1 || s.simId != null) {
            item("sit_sim") {
                MenuRow(stringResource(R.string.sit_sim), simOptions, simIndex, Icons.Rounded.SimCard, stringResource(R.string.sit_sim_sub)) { i ->
                    val sim = sims.getOrNull(i - 1)
                    edit {
                        when {
                            i == 0 -> it.copy(simId = null, simLabel = null)
                            sim != null -> it.copy(simId = sim.id, simLabel = sim.label)
                            else -> it
                        }
                    }
                }
            }
        }
    }
}

/** A window of time and a connection that switch it on by itself. */
@Composable
private fun TurnsOn(s: Situation, edit: ((Situation) -> Situation) -> Unit) {
    val context = LocalContext.current
    // The Bluetooth audio devices connected now, by the name Android gives without any permission.
    val connected = remember { CarAudio.connected(context).mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.distinct() }
    val names = (listOfNotNull(s.deviceName?.takeIf { s.device == DeviceTrigger.NAMED }) + connected).distinctBy { it.lowercase() }
    val deviceOptions = listOf(
        stringResource(R.string.sit_device_none), stringResource(R.string.sit_device_car), stringResource(R.string.sit_device_any),
        stringResource(R.string.sit_device_roaming),
    ) + names
    val deviceIndex = when (s.device) {
        null -> 0
        DeviceTrigger.CAR -> 1
        DeviceTrigger.ANY_BLUETOOTH -> 2
        DeviceTrigger.ROAMING -> 3
        DeviceTrigger.NAMED -> 4 + names.indexOfFirst { it.equals(s.deviceName, ignoreCase = true) }.coerceAtLeast(0)
    }
    SegmentedGroup(stringResource(R.string.sit_group_auto)) {
        item("sit_time") {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
                ParleyListItem(
                    colors = rowColors(),
                    leadingContent = { Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(stringResource(R.string.sit_time), modifier = Modifier.semantics { heading() }) },
                    supportingContent = { Text(stringResource(R.string.sit_time_sub)) },
                )
                ScheduleField(s.schedule, { v -> edit { it.copy(schedule = v) } }, alwaysLabel = stringResource(R.string.sit_time_none))
            }
        }
        item("sit_device") {
            MenuRow(stringResource(R.string.sit_device), deviceOptions, deviceIndex, Icons.Rounded.Bluetooth, stringResource(R.string.sit_device_sub)) { i ->
                edit {
                    when (i) {
                        0 -> it.copy(device = null, deviceName = null)
                        1 -> it.copy(device = DeviceTrigger.CAR, deviceName = null)
                        2 -> it.copy(device = DeviceTrigger.ANY_BLUETOOTH, deviceName = null)
                        3 -> it.copy(device = DeviceTrigger.ROAMING, deviceName = null)
                        else -> it.copy(device = DeviceTrigger.NAMED, deviceName = names.getOrNull(i - 4))
                    }
                }
            }
        }
    }
}
