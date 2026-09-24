package app.parley.ui.people

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.StartTab
import app.parley.common.people.LookupApproval
import app.parley.data.people.ContactsAccessApp
import app.parley.security.launchVault
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import app.parley.ui.settings.LinkRow
import app.parley.ui.settings.SwitchRow

/** Honest wording from the design notes (COMPETITIVE_ANALYSIS_2 §5.4). Parley never claims to control other apps. */
private object Wording {
    const val HEADER = "Android doesn't let any contacts app decide what other apps see. Any app you've allowed “Contacts” can read every " +
        "contact on this phone, from every account. Here is what Parley can do."
    const val APPS = "These apps can read all your contacts. Parley can't limit them. Tap to change their permission in Android Settings."
    const val PRIVATE = "Private contacts are never stored where other apps can read them. Only Parley shows their names."
    const val PICK = "When an app asks you to pick a contact, only that contact is shared."
}

/** Settings › Privacy › "Who can see your contacts". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoCanSeeScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val apps by produceState<List<ContactsAccessApp>?>(null) { value = vm.c.people.audit.appsWithAccess() }
    val graphene = remember { vm.c.people.audit.isGrapheneOs() }
    fun appSettings(pkg: String) = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }.onFailure { vm.toast("Couldn't open Android Settings") }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Who can see your contacts") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Card(Modifier.padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, null, Modifier.padding(end = 16.dp))
                        Text(Wording.HEADER, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (graphene) item {
                Card(Modifier.padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("You're using GrapheneOS", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "GrapheneOS Contact Scopes are the real per-app control: an app can be given only the contacts you choose, and sees nothing else. " +
                                "Open an app below, then Permissions › Contacts › Contact Scopes.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton({
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://grapheneos.org/usage#contact-scopes"))) }
                                .onFailure { vm.toast("No browser available") }
                        }) { Text("How Contact Scopes work") }
                    }
                }
            }

            item { Section("Apps with access to your contacts") }
            item { Text(Wording.APPS, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            val list = apps
            if (list == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            else if (list.isEmpty()) item { ListItem(headlineContent = { Text("No other app on your home screen has Contacts access") }) }
            else items(list, key = { it.packageName }) { a ->
                ListItem(
                    modifier = Modifier.clickable { appSettings(a.packageName) },
                    leadingContent = { AppIcon(a.packageName) },
                    headlineContent = { Text(a.label) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                "Can read all contacts",
                                a.note,
                                if (a.system) "Part of the system" else null,
                                if (graphene) "Tip: use Contact Scopes for this app" else null,
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = { TextButton({ appSettings(a.packageName) }) { Text("Change in Settings") } },
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Android, null) },
                    headlineContent = { Text("Apps you haven't used for a while") },
                    supportingContent = {
                        Text(
                            "Android can remove permissions from apps you haven't used for a few months. Check that “Pause app activity if unused” " +
                                "is on in each app's settings. Apps without a home-screen icon aren't listed here: Parley doesn't ask to see every installed app.",
                        )
                    },
                )
            }

            item {
                // F30: messaging unsaved numbers keeps working without WhatsApp's Contacts permission, as far as Parley can tell.
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    headlineContent = { Text("WhatsApp and other messengers") },
                    supportingContent = { Text(app.parley.messaging.WhatsAppNotice.REVOKE_TEXT) },
                )
            }

            item { Section("Private by default") }
            item {
                SwitchRow("Save new contacts as private", "New contacts go to your private contacts instead of an account", s.privateByDefault) { v ->
                    vm.people.update { it.copy(privateByDefault = v) }
                }
                Text(Wording.PRIVATE, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "What stops showing their names:\n" +
                        "• your car and smartwatch (over Bluetooth they read the phone's contacts)\n" +
                        "• other apps: messengers, keyboards, another phone app\n" +
                        "• Android's call log, which other apps can read (they see only the number)\n" +
                        "Private contacts don't sync to Google or CardDAV; they're in Parley's encrypted backups.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
                LinkRow("Move contacts to private", "Select contacts in the Contacts tab, then More › Move to private") {
                    vm.navigate(NavEvent.Tab(StartTab.CONTACTS))
                    vm.toast("Long-press contacts to select them, then choose More › Move to private")
                }
            }

            item { Section("Share just one contact") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Shield, null) },
                    headlineContent = { Text(Wording.PICK) },
                    supportingContent = {
                        Text(
                            "Parley is your contact picker: an app that asks you to choose a contact gets that one contact, not your address book, " +
                                "and doesn't need the Contacts permission for it." +
                                if (Build.VERSION.SDK_INT >= 37) " On this Android version, apps can also use Android's own contact picker, which shares only what you choose in the same way." else "",
                        )
                    },
                )
                SwitchRow(
                    "Offer to share only a number", "When an app asks for a whole contact, choose to share just one phone number with it. Some apps may not accept this.",
                    s.pickerOneField,
                ) { v -> vm.people.update { it.copy(pickerOneField = v) } }
            }

            item { Section("Private names in other apps") }
            item {
                val pn by vm.c.people.privateNames.state.collectAsStateWithLifecycle()
                LinkRow("Let apps show private names", if (pn.enabled) "On · ${pn.approvals.count { it.value == LookupApproval.ALLOWED }} apps allowed" else "Off") {
                    open(PeopleRoutes.PRIVATE_NAMES)
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val bmp = remember(pkg) { runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    if (bmp != null) Image(bmp, null, Modifier.size(40.dp)) else Icon(Icons.Rounded.Android, null, Modifier.size(40.dp))
}

/** Settings › Privacy › "Let apps show private names": approvals and the access log for the lookup provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateNamesScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val access = vm.c.people.privateNames
    val st by access.state.collectAsStateWithLifecycle()
    val pm = context.packageManager
    fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    Scaffold(topBar = {
        TopAppBar(title = { Text("Private names in other apps") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                SwitchRow("Let apps show private names", "Off by default", st.enabled) { access.setEnabled(it) }
                Text(
                    "Apps you approve can ask Parley for the name of one phone number at a time, for example to show who is calling. " +
                        "They get only that name, never a list, and only after you allow each app. Parley asks you with a notification the first " +
                        "time an app tries, and every request is listed below.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { Section("Apps") }
            if (st.approvals.isEmpty()) item { ListItem(headlineContent = { Text("No app has asked yet") }) }
            items(st.approvals.entries.sortedBy { label(it.key).lowercase() }, key = { it.key }) { (pkg, a) ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { menu = true },
                    leadingContent = { AppIcon(pkg) },
                    headlineContent = { Text(label(pkg)) },
                    supportingContent = {
                        Text(
                            when (a) {
                                LookupApproval.ALLOWED -> "Allowed"
                                LookupApproval.DENIED -> "Not allowed"
                                LookupApproval.PENDING -> "Waiting for your answer"
                            },
                        )
                    },
                    trailingContent = {
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Allow") }, onClick = { menu = false; access.setApproval(pkg, LookupApproval.ALLOWED) })
                            DropdownMenuItem({ Text("Don't allow") }, onClick = { menu = false; access.setApproval(pkg, LookupApproval.DENIED) })
                            DropdownMenuItem({ Text("Forget (ask again)") }, onClick = { menu = false; access.setApproval(pkg, null) })
                        }
                    },
                )
            }
            item { Section("Access log") }
            if (st.log.isEmpty()) item { ListItem(headlineContent = { Text("No requests yet") }) }
            items(st.log.asReversed().take(100)) { e ->
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Lock, null) },
                    headlineContent = { Text(label(e.packageName)) },
                    supportingContent = { Text("${e.outcome.text} · ${Format.shortWhen(context, e.time)}") },
                )
            }
            if (st.log.isNotEmpty()) item { TextButton({ access.clearLog() }, Modifier.padding(horizontal = 8.dp)) { Text("Clear log") } }
            item {
                Text(
                    "For app developers: query content://${app.parley.privatenames.PrivateNameProvider.authority(context)}/lookup/<number> while holding " +
                        "the permission ${app.parley.privatenames.PrivateNameProvider.permission(context)}. The result has one row (display_name, photo_uri) or none.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Confirms moving selected contacts into the private vault (bulk "Move to private"). */
@Composable
fun MoveToPrivateDialog(vm: AppViewModel, ids: List<Long>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${ids.size} to private?") },
        text = {
            Text(
                Wording.PRIVATE + " They are removed from your accounts (Google, CardDAV…) and other phones stop seeing them; " +
                    "your car, watch and other apps show only their numbers.",
            )
        },
        confirmButton = {
            TextButton({
                onDismiss()
                scope.launchVault(context as? androidx.fragment.app.FragmentActivity, { e -> vm.toast("Couldn't move: ${e.message}") }) {
                    var moved = 0
                    for (id in ids) {
                        val d = vm.c.contacts.details(id) ?: continue
                        vm.moveToVault(id, d)
                        moved++
                    }
                    vm.toast("Moved $moved to your private contacts")
                    onDone()
                }
            }) { Text("Move") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
