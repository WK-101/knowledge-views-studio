package app.parley.ui.situations

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingText
import app.parley.common.Schedule
import app.parley.common.situations.DeviceTrigger
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationCause
import app.parley.common.situations.SituationKind
import app.parley.common.situations.SituationRing
import app.parley.common.situations.SituationState
import app.parley.common.situations.Situations
import app.parley.situations.SituationTriggers
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.LinkRow
import app.parley.ui.SplitSwitchRow
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import app.parley.ui.settings.CallsRoutes
import app.parley.ui.settings.CallsSubPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Situation's icon. */
internal fun situationIcon(kind: SituationKind): ImageVector = when (kind) {
    SituationKind.DRIVING -> Icons.Rounded.DirectionsCar
    SituationKind.MEETING -> Icons.Rounded.Groups
    SituationKind.NIGHT -> Icons.Rounded.Bedtime
    SituationKind.TRAVELLING -> Icons.Rounded.Flight
    SituationKind.CUSTOM -> Icons.Rounded.Tune
}

/** "On until 07:00", "Turns on when your car connects", "Off until next time"… and when its label is gone, that first. */
internal fun situationStatus(context: Context, s: Situation, state: SituationState): String {
    val status = when {
        state.activeId == s.id -> onStatus(context, s, state.cause)
        !s.changesSomething -> context.getString(R.string.sit_nothing_yet)
        s.id in state.held -> context.getString(R.string.sit_off_held)
        else -> listOfNotNull(deviceStatus(context, s), s.schedule?.let { context.getString(R.string.sit_auto_window, BlockingText.schedule(context, it)) })
            .joinToString(context.getString(R.string.main_separator)).ifEmpty { context.getString(R.string.sit_off_manual) }
    }
    return labelGone(context, s)?.let { it + context.getString(R.string.main_separator) + status } ?: status
}

/** "“Family” is gone, so Favourites ring instead", or null. */
internal fun labelGone(context: Context, s: Situation): String? =
    s.ringLabel?.takeIf { s.ring == SituationRing.LABEL && s.ringLabelGone && it.isNotBlank() }?.let { context.getString(R.string.sit_label_gone, it.trim()) }

private fun onStatus(context: Context, s: Situation, cause: SituationCause): String = when (cause) {
    SituationCause.MANUAL -> context.getString(R.string.sit_on_manual)
    SituationCause.DEVICE -> context.getString(R.string.sit_on_device)
    SituationCause.SCHEDULE -> s.schedule?.takeIf { it.startMinute != it.endMinute }
        ?.let { context.getString(R.string.sit_on_until, Schedule.hm(it.endMinute)) } ?: context.getString(R.string.sit_on_manual)
}

private fun deviceStatus(context: Context, s: Situation): String? = when (s.device) {
    DeviceTrigger.CAR -> context.getString(R.string.sit_auto_car)
    DeviceTrigger.ANY_BLUETOOTH -> context.getString(R.string.sit_auto_any)
    DeviceTrigger.NAMED -> s.deviceName?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.sit_auto_named, it) }
    null -> null
}

/**
 * One Situation on Calls › Situations: the row opens it, the switch turns it on or off. Switching runs in the app's
 * scope, so leaving the screen never cuts a switch in half.
 */
@Composable
internal fun SituationRow(vm: AppViewModel, s: Situation, state: SituationState, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val name = SituationTriggers.name(context, s)
    val on = state.activeId == s.id
    SplitSwitchRow(
        title = name,
        sub = situationStatus(context, s, state),
        value = on,
        switchLabel = stringResource(R.string.sit_switch_cd, name),
        openLabel = stringResource(R.string.sit_edit_cd, name),
        icon = situationIcon(s.kind),
        onOpen = { open(SituationRoutes.Edit(s.id)) },
    ) { v ->
        val c = vm.c
        c.scope.launch { if (v) c.situations.turnOn(s.id) else c.situations.turnOff() }
    }
}

/** "Add a situation": asks for a name, then opens the new one to choose what it sets. */
@Composable
internal fun AddSituationRow(vm: AppViewModel, open: (Destination) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    // Opening the new one navigates: on the screen's own (main) scope.
    val scope = rememberCoroutineScope()
    LinkRow(stringResource(R.string.sit_add), stringResource(R.string.sit_add_sub), Icons.Rounded.Add) { naming = true }
    if (naming) {
        NameDialog(stringResource(R.string.sit_add_title), "", onDismiss = { naming = false }) { name ->
            naming = false
            val c = vm.c
            val id = c.situations.newId()
            scope.launch {
                if (c.situations.save(Situation(id, SituationKind.CUSTOM, name = name))) open(SituationRoutes.Edit(id))
            }
        }
    }
}

/** A name for a Situation; Save needs one. */
@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    ConfirmDialog(
        title = title,
        text = null,
        confirmLabel = stringResource(R.string.sit_save),
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onSave(text.trim()) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            text, { text = it.take(MAX_NAME) }, singleLine = true,
            label = { Text(stringResource(R.string.sit_name_label)) },
            placeholder = { Text(stringResource(R.string.sit_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MAX_NAME = 40

/**
 * The home screen's line while a Situation is on: its name, and Turn off (one tap, which puts back what was set).
 * The line opens Calls › Situations. Nothing about a contact is shown, so it is the same locked or not.
 */
@Composable
fun SituationChip(vm: AppViewModel, open: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val c = vm.c
    // Situations are read from disk when first used: off the main thread, and nothing is shown until then.
    val sit = produceState(c.situationsIfReady()) { if (value == null) value = withContext(Dispatchers.IO) { c.situations } }.value ?: return
    val state by sit.state.collectAsStateWithLifecycle()
    val list by sit.list.collectAsStateWithLifecycle()
    val active = state.activeId?.let { id -> list.firstOrNull { it.id == id } }
    AnimatedVisibility(active != null, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        val s = active ?: return@AnimatedVisibility
        val context = LocalContext.current
        val name = SituationTriggers.name(context, s)
        val offCd = stringResource(R.string.sit_chip_off_cd, name)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = ParleyShapes.card,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = stringResource(R.string.sit_chip_open)) { open(CallsRoutes.Page(CallsSubPage.SITUATIONS.name)) }
                        .padding(start = Spacing.l, end = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(situationIcon(s.kind), null)
                    Spacer(Modifier.width(Spacing.m))
                    Text(
                        stringResource(R.string.sit_chip_on, name), style = MaterialTheme.typography.labelLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { c.scope.launch { sit.turnOff() } },
                    modifier = Modifier.padding(end = Spacing.xs).semantics { contentDescription = offCd },
                ) { Text(stringResource(R.string.sit_chip_off)) }
            }
        }
    }
}

/** Whether one more can be added to [list] (at most [Situations.MAX]). */
internal fun canAdd(list: List<Situation>): Boolean = list.size < Situations.MAX
