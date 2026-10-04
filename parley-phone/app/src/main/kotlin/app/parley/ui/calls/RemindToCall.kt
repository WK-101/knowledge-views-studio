package app.parley.ui.calls

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun RemindToCallSheet(vm: AppViewModel, number: String, name: String? = null, accountId: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ParleySheet(
        onDismissRequest = onDismiss,
        title = name?.let { stringResource(R.string.to_call_remind_me_to_call_name, it) } ?: stringResource(R.string.to_call_remind_me_to_call),
    ) {
        RemindTimes.choices().forEach { (choice, at) ->
            ParleyListItem(
                headlineContent = { Text(RemindTimes.label(context, choice, at)) },
                leadingContent = { Icon(Icons.Rounded.AlarmAdd, null) },
                modifier = Modifier.clickable(role = Role.Button) { onDismiss(); remindWithUndo(vm, number, accountId, at) },
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
