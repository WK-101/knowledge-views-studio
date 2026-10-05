package app.parley.ui.circle

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import app.parley.AppViewModel
import app.parley.calls.AgendaTicks
import app.parley.common.catching
import app.parley.ui.ListSectionHeader
import app.parley.ui.ParleyListItem
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.circle.Agenda
import app.parley.data.DataContainer
import app.parley.data.circle.AgendaStore
import app.parley.data.circle.AgendaTarget
import app.parley.ui.ConfirmDialog

/**
 * "Something to talk about (with Ana)": one line for the person's agenda ([Agenda]). [name] is null for a number;
 * [initial] fills the field (a shared text). What
 * is typed survives a rotation only when [keepOnRotation] (never for a private contact: saved state is kept by the
 * system, outside Parley's sealed storage).
 */
@Composable
fun AgendaAddDialog(name: String?, onAdd: (String) -> Unit, onDismiss: () -> Unit, initial: String = "", keepOnRotation: Boolean = true) {
    val start = initial.take(Agenda.MAX_LENGTH)
    val state = if (keepOnRotation) rememberSaveable { mutableStateOf(start) } else remember { mutableStateOf(start) }
    var text by state
    ConfirmDialog(
        title = if (name.isNullOrBlank()) stringResource(R.string.agenda_add_title) else stringResource(R.string.agenda_add_title_with, name),
        text = stringResource(R.string.agenda_add_hint),
        confirmLabel = stringResource(R.string.agenda_add_save),
        confirmEnabled = Agenda.clean(text) != null,
        onConfirm = { onAdd(text) },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.main_cancel),
        content = {
            OutlinedTextField(
                text, { text = it.take(Agenda.MAX_LENGTH) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.agenda_add_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** Adds [text] to [target]'s agenda; what to tell the user. */
suspend fun addToAgenda(c: DataContainer, target: AgendaTarget, text: String): Int = when (c.agenda.add(target, text)) {
    AgendaStore.Added.ADDED -> R.string.agenda_added
    AgendaStore.Added.ALREADY -> R.string.agenda_already
    AgendaStore.Added.LOCKED -> R.string.agenda_unlock_first
    AgendaStore.Added.FAILED -> R.string.agenda_add_failed
}

/**
 * "To talk about" on a number's page: the agenda of whoever the number is (the contact, the private contact while
 * private contacts are open, or the number itself), ticked off with one tap, and "Add something to talk about". Nothing
 * for a number whose agenda can't be read now (a private contact while locked or hidden). [refresh]: re-read when it
 * changes (the number's notes changed).
 */
@Composable
fun NumberAgendaBlock(vm: AppViewModel, number: String, name: String?, refresh: Any?) {
    val scope = rememberCoroutineScope()
    var reads by remember(number) { mutableIntStateOf(0) }
    var adding by remember(number) { mutableStateOf(false) }
    val loaded by produceState<Pair<AgendaTarget, List<String>>?>(null, number, refresh, reads) {
        value = catching {
            val target = vm.c.agenda.targetFor(number) ?: return@catching null
            vm.c.agenda.open(target)?.let { target to it }
        }.getOrNull()
    }
    val (target, items) = loaded ?: return
    val isPrivate = target is AgendaTarget.Private
    if (adding) {
        AgendaAddDialog(
            name, keepOnRotation = !isPrivate,
            onAdd = { text ->
                adding = false
                scope.launch {
                    vm.toast(vm.getApplication<Application>().getString(addToAgenda(vm.c, target, text)))
                    reads++
                }
            },
            onDismiss = { adding = false },
        )
    }
    Column {
        ListSectionHeader(stringResource(R.string.agenda_title))
        items.forEach { item ->
            ParleyListItem(
                headlineContent = { Text(item) },
                leadingContent = { Checkbox(checked = false, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = false, role = Role.Checkbox) {
                    scope.launch {
                        if (AgendaTicks.setDone(vm.c, target, item, true)) {
                            vm.toast(vm.getApplication<Application>().getString(R.string.circle_promise_done, item))
                        }
                        reads++
                    }
                },
            )
        }
        ParleyListItem(
            headlineContent = { Text(stringResource(R.string.agenda_add)) },
            supportingContent = {
                Text(stringResource(if (target is AgendaTarget.Number) R.string.agenda_number_hint else R.string.agenda_add_hint))
            },
            leadingContent = { Icon(Icons.Rounded.Add, null) },
            modifier = Modifier.clickable { adding = true },
        )
    }
}
