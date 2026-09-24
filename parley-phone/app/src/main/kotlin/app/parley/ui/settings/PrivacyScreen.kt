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
import app.parley.AppViewModel
import app.parley.ui.CallColors
import app.parley.ui.contact.Section

private val reasons = mapOf(
    "android.permission.CALL_PHONE" to "Place the calls you start.",
    "android.permission.READ_PHONE_STATE" to "List your SIM cards and show which one a call uses.",
    "android.permission.ANSWER_PHONE_CALLS" to "Answer and end calls from headsets and notifications.",
    "android.permission.READ_CALL_LOG" to "Show your call history.",
    "android.permission.WRITE_CALL_LOG" to "Delete call history entries when you ask.",
    "android.permission.READ_CONTACTS" to "Show your contacts and caller names.",
    "android.permission.WRITE_CONTACTS" to "Create, edit, merge and delete contacts.",
    "android.permission.GET_ACCOUNTS" to "Let you choose which account a contact is saved in.",
    "android.permission.POST_NOTIFICATIONS" to "Show incoming, ongoing and missed-call notifications.",
    "android.permission.USE_FULL_SCREEN_INTENT" to "Show incoming calls over the lock screen.",
    "android.permission.WAKE_LOCK" to "Turn the screen off when the phone is at your ear, and keep a call time limit on time.",
    "android.permission.VIBRATE" to "Keypad and call vibrations.",
    "android.permission.MODIFY_AUDIO_SETTINGS" to "Switch between earpiece, speaker and headsets.",
    "android.permission.READ_PHONE_NUMBERS" to "Optional: know your own number for neighbour-spoofing protection.",
    "android.permission.BLUETOOTH_CONNECT" to "Optional: show Bluetooth headset names during calls.",
    "android.permission.READ_SYNC_SETTINGS" to "Tell you in the health check when contacts sync is off for an account.",
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
        TopAppBar(title = { Text("Privacy dashboard") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
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
                            Text(if (hasInternet) "Internet permission present" else "No internet access", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (hasInternet) "This build requests internet access. Official builds never do."
                                else "Android itself confirms this app did not request the INTERNET permission. Your contacts and calls cannot leave this phone through Parley.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "No analytics, no crash reporting, no ads, no accounts. Backups are files you export yourself.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { app.parley.ui.calltime.NotificationHealthCard(vm) }
            item { app.parley.ui.people.PrivacyLinks(vm) }
            item { Section("What Parley keeps private") }
            item {
                val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
                val priv by vm.c.vault.privateCalls.collectAsStateWithLifecycle()
                val s by vm.settings.collectAsStateWithLifecycle()
                val journalCount by androidx.compose.runtime.produceState(0) { value = vm.c.meta.journalCount() }
                ListItem(headlineContent = { Text("${vault.size} private contacts") }, supportingContent = { Text("Encrypted; invisible to every other app") })
                ListItem(headlineContent = { Text("${priv.size} private calls") }, supportingContent = { Text("Kept out of the system call log") })
                val archiveOn by vm.c.history.prefs.state.collectAsStateWithLifecycle()
                ListItem(
                    headlineContent = { Text(if (s.callLogRetentionDays > 0) "Call history kept ${s.callLogRetentionDays} days" else "Parley never deletes call history on its own") },
                    supportingContent = {
                        Text(
                            if (archiveOn.archiveEnabled) "Android may keep only recent calls on some phones, so Parley keeps its own encrypted copy on this phone. Change in Settings → Calls"
                            else "Android itself may keep only recent calls on some phones; turn on “Keep full call history” in Settings → Calls to keep them all",
                        )
                    },
                )
                ListItem(headlineContent = { Text("$journalCount changes you can undo") }, supportingContent = { Text("Deleted and edited contacts are kept for 30 days on this phone only") })
                ListItem(
                    headlineContent = { Text(if (s.appLock) "App lock on" else "App lock off") },
                    supportingContent = { Text(if (s.secureScreen) "Screen content hidden from screenshots" else "Screenshots allowed") },
                )
            }
            item { Section("Permissions") }
            items(requested.filter { it.startsWith("android.permission.") }) { perm ->
                val granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                ListItem(
                    leadingContent = { Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (granted) CallColors.Accept else MaterialTheme.colorScheme.outline) },
                    headlineContent = { Text(perm.removePrefix("android.permission.").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                    supportingContent = { Text(reasons[perm] ?: "") },
                )
            }
        }
    }
}
