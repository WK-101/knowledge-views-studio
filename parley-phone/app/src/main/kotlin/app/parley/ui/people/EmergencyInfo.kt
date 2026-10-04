package app.parley.ui.people

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.MedicalInformation
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.startOrSay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * My card › "In an emergency": Android's own emergency information (medical details and emergency contacts, which the
 * lock screen's Emergency button shows; Parley never touches that button) and an "ICE" label, the "in case of
 * emergency" convention helpers look for in a phone's contacts. Nothing of it is Parley's own copy.
 */
@Composable
internal fun EmergencyInfoGroup(vm: AppViewModel) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var noEmergencyApp by rememberSaveable { mutableStateOf(false) }
    SegmentedGroup(stringResource(R.string.me_emergency)) {
        item {
            ParleyListItem(
                modifier = Modifier.clickable { if (!openEmergencyInfo(context)) noEmergencyApp = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Rounded.MedicalInformation, null) },
                headlineContent = { Text(stringResource(R.string.me_emergency_info)) },
                supportingContent = { Text(stringResource(R.string.me_emergency_info_sub)) },
            )
        }
        item {
            ParleyListItem(
                modifier = Modifier.clickable {
                    scope.launch {
                        // The label if it exists, else made in the default account (as the labels screen would suggest).
                        val made = withContext(Dispatchers.IO) { ensureIceLabel(vm) }
                        if (made) vm.navigate(NavEvent.Route(PeopleRoutes.label(ICE_LABEL))) else vm.toast(res.getString(R.string.lbl_create_failed))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Label, null) },
                headlineContent = { Text(stringResource(R.string.me_ice_label)) },
                supportingContent = { Text(stringResource(R.string.me_ice_label_sub)) },
            )
        }
    }
    if (noEmergencyApp) {
        ParleyDialog(
            onDismissRequest = { noEmergencyApp = false },
            title = { Text(stringResource(R.string.me_emergency_info)) },
            text = { Text(stringResource(R.string.me_emergency_missing)) },
            confirmButton = {
                TextButton({
                    noEmergencyApp = false
                    context.startOrSay(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.me_emergency_open_settings)) }
            },
            dismissButton = { TextButton({ noEmergencyApp = false }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}

/** The label people look for in an emergency ("In case of emergency"), the same word in every language. */
private const val ICE_LABEL = "ICE"

/**
 * Opens Android's emergency information editor (the Emergency information app, or the phone maker's Safety app that
 * answers the same action). False when this phone has none: the caller then says where to look instead.
 */
private fun openEmergencyInfo(context: Context): Boolean = context.startOrSay(Intent(ACTION_EDIT_EMERGENCY_INFO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Settings' action for editing emergency information (not in the public SDK constants, but answered by the system). */
private const val ACTION_EDIT_EMERGENCY_INFO = "android.settings.EDIT_EMERGENCY_INFO"

/** Makes sure a label titled [ICE_LABEL] exists; true when it does now. */
private suspend fun ensureIceLabel(vm: AppViewModel): Boolean {
    val labels = vm.c.people.labels
    if (runCatching { labels.label(ICE_LABEL) }.getOrNull() != null) return true
    val accounts = runCatching { vm.c.contacts.accounts() }.getOrDefault(emptyList())
    val s = vm.settings.value
    val account = accounts.firstOrNull { it.type == s.defaultAccountType && it.name == s.defaultAccountName }
        ?: accounts.firstOrNull { it.type == "com.google" } ?: accounts.firstOrNull() ?: return false
    val made = runCatching { labels.create(ICE_LABEL, account) }.getOrNull() != null
    if (made) runCatching { vm.c.contacts.refresh() }
    return made
}
