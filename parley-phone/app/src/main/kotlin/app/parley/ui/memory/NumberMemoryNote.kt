package app.parley.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.catching
import app.parley.common.memory.MemoryHint
import app.parley.common.memory.MemorySource
import app.parley.common.memory.NumberMemory
import app.parley.common.people.ContactRef
import app.parley.common.ux.Tips
import app.parley.security.AppLock
import app.parley.ui.Destination
import app.parley.ui.ParleyListItem
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.calls.ToCallRoutes
import app.parley.ui.common.CoachMark
import app.parley.ui.journal.HistoryTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A remembered line, ready to show: its text and what its button does (with where it goes, resolved). */
private class Shown(val hint: MemoryHint, val text: String, val action: MemoryAction?, val noteTarget: Destination?)

/** The keypad's results row for a typed number no contact has ("Not in contacts" and what Parley remembers). */
@Composable
fun KeypadNumberMemory(vm: AppViewModel, number: String, open: (Destination) -> Unit) = NumberMemoryNote(vm, number, NumberMemory.Place.KEYPAD, open)

/** The line in a number's history header, for a number that isn't a contact. */
@Composable
fun HistoryNumberMemory(vm: AppViewModel, number: String, open: (Destination) -> Unit) = NumberMemoryNote(vm, number, NumberMemory.Place.HISTORY, open)

/**
 * Number memory in the app: what Parley remembers about [number] (not a contact), as one quiet line with its
 * action (Restore contact, Open note, Open snapshot…). [NumberMemory.Place.KEYPAD] is a results row under "Not in
 * contacts" (looked up once typing pauses); [NumberMemory.Place.HISTORY] a centred line in the number's history header.
 * Nothing shows when Parley remembers nothing. The first time, a one-line tip says what it is.
 */
@Composable
private fun NumberMemoryNote(vm: AppViewModel, number: String, place: NumberMemory.Place, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    val shown by produceState<Shown?>(null, number, place, round) {
        value = null
        if (place == NumberMemory.Place.KEYPAD) delay(TYPING_PAUSE_MS)
        value = withContext(Dispatchers.IO) { load(vm, context, number, place) }
    }
    val s = shown ?: return
    val runner = remember(s) { MemoryActions(vm, context, scope, number, s, open) { round++ } }
    val button: @Composable () -> Unit = {
        s.action?.let { a -> TextButton({ runner.run(a) }) { Text(stringResource(a.label), color = MaterialTheme.colorScheme.primary) } }
    }
    Column {
        if (place == NumberMemory.Place.KEYPAD) {
            ParleyListItem(
                headlineContent = { Text(stringResource(R.string.number_memory_not_in_contacts)) },
                supportingContent = { Text(s.text) },
                leadingContent = { Icon(Icons.Rounded.History, null) },
                trailingContent = if (s.action != null) button else null,
            )
        } else {
            Column(Modifier.padding(top = Spacing.s).widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.History, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(s.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                button()
            }
        }
        CoachMark(Tips.NUMBER_MEMORY, stringResource(R.string.number_memory_tip))
    }
}

/** The best hint for [number] at [place], in words, with its action (an "Open note" only when the page still exists). */
private suspend fun load(vm: AppViewModel, context: Context, number: String, place: NumberMemory.Place): Shown? {
    val hint = vm.c.numberMemory.best(number, place) ?: return null
    val target = hint.ref?.takeIf { hint.source == MemorySource.NOTE }?.let { noteTarget(vm, it) }
    val action = NumberMemoryText.action(hint, place)?.takeIf { it != MemoryAction.OPEN_NOTE || target != null }
    return Shown(hint, NumberMemoryText.line(context, hint), action, target)
}

/** What a line's button does; [changed] reloads the line after a restore. */
private class MemoryActions(
    private val vm: AppViewModel,
    private val context: Context,
    private val scope: CoroutineScope,
    private val number: String,
    private val s: Shown,
    private val open: (Destination) -> Unit,
    private val changed: () -> Unit,
) {
    fun run(action: MemoryAction) {
        when (action) {
            MemoryAction.RESTORE -> restore { vm.c.journal.restore(s.hint.ref!!.toLong()) }
            // The vault's own unlock first, as in History & undo.
            MemoryAction.RESTORE_PRIVATE -> (context as? ComponentActivity)?.let { activity ->
                AppLock.authenticateForVault(activity) { ok ->
                    if (ok) restore { vm.c.privateTrash.restore(s.hint.ref!!)?.let { ContactRef.Private(it).navId } }
                }
            }
            MemoryAction.OPEN_NOTE -> s.noteTarget?.let(open)
            MemoryAction.OPEN_SNAPSHOT -> open(Routes.journal(HistoryTab.SNAPSHOTS))
            MemoryAction.OPEN_TO_CALL -> open(ToCallRoutes.List)
            MemoryAction.OPEN_HISTORY -> open(Routes.history(number))
        }
    }

    private fun restore(block: suspend () -> Long?) = restoreAndOpen(vm, context, scope, s.hint.name.orEmpty(), open, changed, block)
}

/**
 * Restores a deleted contact ([block] returns its navigation id), says so, and opens it; [changed] runs once it is
 * back (the line or the search that offered it reads again).
 */
internal fun restoreAndOpen(
    vm: AppViewModel,
    context: Context,
    scope: CoroutineScope,
    name: String,
    open: (Destination) -> Unit,
    changed: () -> Unit,
    block: suspend () -> Long?,
) {
    val res = context.resources
    scope.launch {
        val id = catching { block() }.getOrNull() ?: return@launch vm.toast(res.getString(R.string.jr_restore_failed))
        vm.toast(res.getString(R.string.jr_restored_name, name))
        changed()
        open(Routes.contact(id))
    }
}

/** The page a note is on: a private contact by its key, a device contact by its lookup key; null when gone. */
internal fun noteTarget(vm: AppViewModel, key: String): Destination? {
    ContactRef.vaultIdOf(key)?.let { return Routes.contact(ContactRef.Private(it).navId) }
    return vm.c.contacts.contacts.value?.firstOrNull { it.lookupKey == key }?.let { Routes.contact(it.id) }
}

private const val TYPING_PAUSE_MS = 350L
