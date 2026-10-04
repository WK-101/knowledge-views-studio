package app.parley.ui.calls

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewModelScope
import app.parley.AppViewModel
import app.parley.R
import app.parley.calls.ToCallReminders
import app.parley.common.PhoneIdentity
import app.parley.common.calls.ToCall
import app.parley.common.suspendRunCatching
import app.parley.data.PhoneEnv
import app.parley.telecom.R as TelecomR
import app.parley.telecom.ui.RemindTimes
import app.parley.ui.Bidi
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import kotlinx.coroutines.launch

/**
 * "Remind me to call" by hand, from Recents' long-press, the contact page and number history: the same fixed times as
 * Remind me on a missed call or after a call, onto the same To call list. [name] titles the sheet when known.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList") // The number, its alternatives and who it is.
fun RemindToCallSheet(
    vm: AppViewModel,
    number: String,
    name: String? = null,
    accountId: String? = null,
    /** A contact's numbers with their type ("Mobile", "Work"), when there are several: the user picks which one. */
    numbers: List<Pair<String, String>> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var chosen by rememberSaveable(number) { mutableStateOf(number) }
    ParleySheet(
        onDismissRequest = onDismiss,
        title = name?.let { stringResource(R.string.to_call_remind_me_to_call_name, it) } ?: stringResource(R.string.to_call_remind_me_to_call),
    ) {
        if (numbers.size > 1) {
            Text(
                stringResource(R.string.to_call_which_number), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
            Column(Modifier.selectableGroup()) {
                numbers.forEach { (n, type) ->
                    ParleyListItem(
                        headlineContent = { Text(Bidi.ltr(n)) },
                        supportingContent = { Text(type) },
                        leadingContent = { RadioButton(selected = n == chosen, onClick = null) },
                        modifier = Modifier.selectable(selected = n == chosen, role = Role.RadioButton) { chosen = n },
                    )
                }
            }
            HorizontalDivider()
        }
        RemindTimes.choices().forEach { (choice, at) ->
            ParleyListItem(
                headlineContent = { Text(RemindTimes.label(context, choice, at)) },
                leadingContent = { Icon(Icons.Rounded.AlarmAdd, null) },
                modifier = Modifier.clickable(role = Role.Button) { onDismiss(); remindWithUndo(vm, chosen, accountId, at) },
            )
        }
        Spacer(Modifier.navigationBarsPadding().padding(bottom = Spacing.l))
    }
}

/** Puts [number] on To call for [at], then says when, with Undo (which puts the list back as it was for them). */
fun remindWithUndo(vm: AppViewModel, number: String, accountId: String?, at: Long) {
    val app = vm.getApplication<Application>()
    vm.viewModelScope.launch {
        val before = suspendRunCatching { vm.c.toCall.load() }.getOrNull()
        val set = before != null && suspendRunCatching { ToCallReminders.remind(app, number, accountId, at) }.getOrDefault(false)
        if (before == null || !set) {
            vm.toast(app.getString(R.string.to_call_remind_failed))
            return@launch
        }
        val key = PhoneIdentity.key(number, PhoneEnv.countryIso(app, accountId))
        vm.offerUndo(app.getString(TelecomR.string.remind_set, RemindTimes.whenText(app, at))) {
            ToCallReminders.update(app) { ToCall.undoRemind(it, before, key) }
        }
    }
}
