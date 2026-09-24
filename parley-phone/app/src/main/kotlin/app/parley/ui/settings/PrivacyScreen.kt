package app.parley.ui.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.ui.CallColors
import app.parley.ui.contact.Section

private val reasons = mapOf(
    "android.permission.CALL_PHONE" to R.string.set_perm_call_phone,
    "android.permission.READ_PHONE_STATE" to R.string.set_perm_read_phone_state,
    "android.permission.ANSWER_PHONE_CALLS" to R.string.set_perm_answer_phone_calls,
    "android.permission.READ_CALL_LOG" to R.string.set_perm_read_call_log,
    "android.permission.WRITE_CALL_LOG" to R.string.set_perm_write_call_log,
    "android.permission.READ_CONTACTS" to R.string.set_perm_read_contacts,
    "android.permission.WRITE_CONTACTS" to R.string.set_perm_write_contacts,
    "android.permission.GET_ACCOUNTS" to R.string.set_perm_get_accounts,
    "android.permission.POST_NOTIFICATIONS" to R.string.set_perm_post_notifications,
    "android.permission.USE_FULL_SCREEN_INTENT" to R.string.set_perm_full_screen_intent,
    "android.permission.WAKE_LOCK" to R.string.set_perm_wake_lock,
    "android.permission.VIBRATE" to R.string.set_perm_vibrate,
    "android.permission.MODIFY_AUDIO_SETTINGS" to R.string.set_perm_modify_audio,
    "android.permission.READ_PHONE_NUMBERS" to R.string.set_perm_read_phone_numbers,
    "android.permission.BLUETOOTH_CONNECT" to R.string.set_perm_bluetooth_connect,
    "android.permission.READ_SYNC_SETTINGS" to R.string.set_perm_read_sync_settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val requested = remember {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
    }
    val hasInternet = "android.permission.INTERNET" in requested
    Scaffold(topBar = {
        TopAppBar(title = { Text(settingTitle("privacy_dashboard")) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Card(
                    Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (hasInternet) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WifiOff, null, Modifier.padding(end = 16.dp))
                        Column {
                            Text(stringResource(if (hasInternet) R.string.set_privacy_internet_present else R.string.set_no_internet), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (hasInternet) R.string.set_privacy_internet_present_body else R.string.set_privacy_no_internet_body),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.set_privacy_no_analytics),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { app.parley.ui.calltime.NotificationHealthCard(vm) }
            item { app.parley.ui.people.PrivacyLinks(vm) }
            item { Section(stringResource(R.string.set_privacy_keeps_private)) }
            item {
                val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
                val priv by vm.c.vault.privateCalls.collectAsStateWithLifecycle()
                val s by vm.settings.collectAsStateWithLifecycle()
                val journalCount by androidx.compose.runtime.produceState(0) { value = vm.c.meta.journalCount() }
                ListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.set_privacy_private_contacts, vault.size, vault.size)) },
                    supportingContent = { Text(stringResource(R.string.set_privacy_private_contacts_body)) },
                )
                ListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.set_privacy_private_calls, priv.size, priv.size)) },
                    supportingContent = { Text(stringResource(R.string.set_privacy_private_calls_body)) },
                )
                app.parley.messaging.MessagedRecordSection { vm.navigate(app.parley.NavEvent.Route(app.parley.messaging.MessagingRoutes.MESSAGED)) }
                val archiveOn by vm.c.history.prefs.state.collectAsStateWithLifecycle()
                ListItem(
                    headlineContent = {
                        Text(
                            if (s.callLogRetentionDays > 0) pluralStringResource(R.plurals.set_privacy_history_kept, s.callLogRetentionDays, s.callLogRetentionDays)
                            else stringResource(R.string.set_privacy_history_never_deleted),
                        )
                    },
                    supportingContent = {
                        Text(stringResource(if (archiveOn.archiveEnabled) R.string.set_privacy_archive_on else R.string.set_privacy_archive_off))
                    },
                )
                ListItem(
                    headlineContent = { Text(pluralStringResource(R.plurals.set_privacy_undo_changes, journalCount, journalCount)) },
                    supportingContent = { Text(stringResource(R.string.set_privacy_undo_body)) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(if (s.appLock) R.string.set_privacy_app_lock_on else R.string.set_privacy_app_lock_off)) },
                    supportingContent = { Text(stringResource(if (s.secureScreen) R.string.set_privacy_screenshots_hidden else R.string.set_privacy_screenshots_allowed)) },
                )
            }
            item { Section(stringResource(R.string.set_privacy_permissions)) }
            items(requested.filter { it.startsWith("android.permission.") }) { perm ->
                val granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                ListItem(
                    leadingContent = { Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (granted) CallColors.Accept else MaterialTheme.colorScheme.outline) },
                    headlineContent = { Text(perm.removePrefix("android.permission.").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                    supportingContent = { Text(reasons[perm]?.let { stringResource(it) } ?: "") },
                )
            }
        }
    }
}
