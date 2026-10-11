package app.parley.telecom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.NotificationPrivacy
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPath
import app.parley.common.ux.Tips
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Menu memory is offered in a connected call the user placed, never an emergency call or a conference. */
private fun menuMemoryApplies(call: CallUi): Boolean =
    !call.incoming && !call.hidden && !call.isEmergency && !call.isConference && !call.number.isNullOrBlank() &&
        (call.state == CallState.ACTIVE || call.state == CallState.HOLDING)

/** Saving and "Don't remember" outlive the dialog (and the row) that started them. */
private val menuScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Calls whose "Last time" row was turned off here ("Don't remember digits for this number"), for the screen's life. */
private val stoppedHere = mutableStateListOf<String>()

/** The tip was closed during this screen's life (the stored flag is read once per row). */
private var tipClosedHere by mutableStateOf(false)

/** What the user did with the row in this call: saved it ("Saved as …"), or saving failed. */
private class MenuRowState {
    var menu by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var askStop by mutableStateOf(false)
    var savedAs by mutableStateOf<String?>(null)
    var saveFailed by mutableStateOf(false)
}

/**
 * The in-call keypad's top row: "Last time: 2 › 1 › 4" for a number Parley remembers digits for, with **Replay**
 * (sends them again with the recorded pauses; Stop, or any key, stops it) and ⋮ with "Save as shortcut…" and "Don't
 * remember digits for this number". Shows nothing when there is nothing remembered.
 */
@Composable
internal fun MenuMemoryRow(call: CallUi) {
    val applies = menuMemoryApplies(call)
    var path by remember(call.id) { mutableStateOf<MenuPath?>(null) }
    LaunchedEffect(call.id, applies) {
        if (!applies || path != null) return@LaunchedEffect
        val number = call.number.orEmpty()
        path = runCatching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.menuPath(number, call.accountId) } }.getOrNull()
    }
    val p = path?.takeIf { it.steps.isNotEmpty() }
    if (!applies || p == null) return
    if (call.id in stoppedHere) return
    val st = remember(call.id) { MenuRowState() }
    // The call screen shows over the lock screen: whoever holds the locked phone sees that keys are remembered, not which.
    val locked = rememberKeyguardLocked()
    Column(Modifier.widthIn(max = CallButtonSize.panelMaxWidth).fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        MenuTip()
        MenuRowCard(call, p, st, locked)
    }
    if (st.saving && !locked) MenuSaveDialog(call, p, st)
    if (st.askStop) MenuStopDialog(call, st)
}

@Composable
private fun MenuRowCard(call: CallUi, p: MenuPath, st: MenuRowState, locked: Boolean) {
    val replay by CallManager.menuReplay.collectAsState()
    val sending = replay?.takeIf { it.callId == call.id }
    val label = Bidi.ltr(MenuMemory.label(p.steps))
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = ParleyShapes.card, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.heightIn(min = 56.dp).padding(start = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    val title = when {
                        locked && sending != null -> stringResource(R.string.menu_sending_locked)
                        locked -> stringResource(R.string.menu_remembered)
                        sending != null -> stringResource(R.string.menu_sending, label)
                        else -> stringResource(R.string.menu_last_time, label)
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val note = if (locked) null else st.savedAs?.let { stringResource(R.string.menu_saved, it) }
                        ?: stringResource(R.string.menu_save_failed).takeIf { st.saveFailed }
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sending != null) StopButton() else ReplayButton(call, p, label.takeUnless { locked })
                Box {
                    IconButton(onClick = { st.menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.menu_more)) }
                    DropdownMenu(expanded = st.menu, onDismissRequest = { st.menu = false }) {
                        // Saving shows the keys and names the shortcut: only once the phone is unlocked.
                        if (!locked) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_save_shortcut)) }, onClick = { st.menu = false; st.saving = true })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_dont_remember)) }, onClick = { st.menu = false; st.askStop = true })
                    }
                }
            }
            if (sending != null) {
                LinearProgressIndicator(
                    progress = { sending.sent.toFloat() / sending.steps.size.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.s),
                )
            }
        }
    }
}

@Composable
private fun StopButton() {
    FilledTonalButton(onClick = { CallManager.stopMenuReplay() }, modifier = Modifier.padding(start = Spacing.s)) {
        Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(stringResource(R.string.menu_stop))
    }
}

@Composable
private fun ReplayButton(call: CallUi, p: MenuPath, label: String?) {
    val spoken = if (label == null) stringResource(R.string.menu_replay_spoken_locked) else stringResource(R.string.menu_replay_spoken, label)
    FilledTonalButton(
        onClick = { CallManager.replayMenu(call.id, p.steps) },
        enabled = call.state == CallState.ACTIVE,
        modifier = Modifier.padding(start = Spacing.s).semantics { contentDescription = spoken },
    ) {
        Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(stringResource(R.string.menu_replay))
    }
}

@Composable
private fun MenuSaveDialog(call: CallUi, p: MenuPath, st: MenuRowState) {
    val number = call.number.orEmpty()
    // A private contact (no device contact behind the name) is suggested by number: the name would reach the
    // launcher's shortcut store and pages that discreet mode or the vault's lock hide.
    val private = call.contactId == null || NotificationPrivacy.isVaultLabel(call.label)
    val who = MenuMemory.shortcutWho(call.name, number, private)
    var name by remember { mutableStateOf(MenuMemory.suggestedName(who, p.steps)) }
    ConfirmDialog(
        title = stringResource(R.string.menu_save_title),
        text = stringResource(R.string.menu_save_body, Bidi.ltr(number), Bidi.ltr(MenuMemory.label(p.steps))),
        confirmLabel = stringResource(R.string.tc_save),
        confirmEnabled = MenuMemory.cleanName(name) != null,
        onConfirm = {
            st.saving = false
            val chosen = name
            menuScope.launch {
                val ok = runCatching { TelecomGraph.dependencies.saveMenuShortcut(number, call.accountId, chosen, p.steps) }.getOrDefault(false)
                st.saveFailed = !ok
                st.savedAs = if (ok) MenuMemory.cleanName(chosen) else null
            }
        },
        onDismiss = { st.saving = false },
        content = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(MenuMemory.MAX_NAME) }, singleLine = true,
                label = { Text(stringResource(R.string.postcall_name)) }, modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun MenuStopDialog(call: CallUi, st: MenuRowState) {
    ConfirmDialog(
        title = stringResource(R.string.menu_stop_title),
        text = stringResource(R.string.menu_stop_body),
        confirmLabel = stringResource(R.string.menu_stop_confirm),
        onConfirm = {
            st.askStop = false
            if (CallManager.menuReplay.value?.callId == call.id) CallManager.stopMenuReplay()
            stoppedHere += call.id
            val number = call.number.orEmpty()
            menuScope.launch { runCatching { TelecomGraph.dependencies.stopMenuMemory(number, call.accountId) } }
        },
        onDismiss = { st.askStop = false },
    )
}

/** The one-time explainer above the row: what is remembered, and what never is. */
@Composable
private fun MenuTip() {
    if (tipClosedHere) return
    val seen = remember { runCatching { TelecomGraph.dependencies.tipSeen(Tips.MENU_MEMORY) }.getOrDefault(true) }
    if (seen) return
    CallTip(stringResource(R.string.menu_tip)) {
        tipClosedHere = true
        runCatching { TelecomGraph.dependencies.markTipSeen(Tips.MENU_MEMORY) }
    }
}
