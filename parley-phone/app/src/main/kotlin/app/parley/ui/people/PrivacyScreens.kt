package app.parley.ui.people

import androidx.annotation.StringRes
import app.parley.security.SensitiveScreen
import app.parley.ui.Destination
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
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.StartTab
import app.parley.common.people.LookupApproval
import app.parley.data.people.ContactsAccessApp
import app.parley.messaging.WhatsAppNotice
import app.parley.privatenames.PrivateDirectoryProvider
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.common.Format
import app.parley.ui.LinkRow
import app.parley.ui.SwitchRow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.people.LookupOutcome
import app.parley.ui.settings.settingTitle
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.startOrSay

/** Honest wording from the design notes (COMPETITIVE_ANALYSIS_2 §5.4). Parley never claims to control other apps. */
private object Wording {
    val HEADER = R.string.who_header
    val APPS = R.string.who_apps
    val PRIVATE = R.string.who_private
    val PICK = R.string.who_pick
}

/** Settings › Privacy › "Who can see your contacts". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoCanSeeScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val apps by produceState<List<ContactsAccessApp>?>(null) { value = vm.c.people.audit.appsWithAccess() }
    val graphene = remember { vm.c.people.audit.isGrapheneOs() }
    fun appSettings(pkg: String) = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }.onFailure { vm.toast(res.getString(R.string.who_settings_failed)) }

    // Scroll-linked top-bar tint.
    val barTint = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        ParleyTopBar(stringResource(R.string.privacy_who_can_see), onBack = back, scrollBehavior = barTint)
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Card(Modifier.padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, null, Modifier.padding(end = 16.dp))
                        Text(stringResource(Wording.HEADER), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (graphene) item {
                Card(Modifier.padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.who_graphene), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.who_graphene_text),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton({
                            val scopes = Intent(Intent.ACTION_VIEW, Uri.parse("https://grapheneos.org/usage#contact-scopes"))
                            if (!context.startOrSay(scopes)) vm.toast(res.getString(R.string.who_no_browser))
                        }) { Text(stringResource(R.string.who_scopes_link)) }
                    }
                }
            }

            item { Section(stringResource(R.string.who_apps_section)) }
            item { Text(stringResource(Wording.APPS), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            val list = apps
            if (list == null) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            else if (list.isEmpty()) item { ParleyListItem(headlineContent = { Text(stringResource(R.string.who_no_apps)) }) }
            else items(list, key = { it.packageName }) { a ->
                ParleyListItem(
                    modifier = Modifier.clickable { appSettings(a.packageName) },
                    leadingContent = { AppIcon(a.packageName) },
                    headlineContent = { Text(a.label) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                stringResource(R.string.who_can_read_all),
                                a.note,
                                if (a.system) stringResource(R.string.who_system) else null,
                                if (graphene) stringResource(R.string.who_scopes_tip) else null,
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = { TextButton({ appSettings(a.packageName) }) { Text(stringResource(R.string.who_change)) } },
                )
            }
            item {
                ParleyListItem(
                    leadingContent = { Icon(Icons.Rounded.Android, null) },
                    headlineContent = { Text(stringResource(R.string.who_unused)) },
                    supportingContent = { Text(stringResource(R.string.who_unused_text)) },
                )
            }

            item {
                // Messaging unsaved numbers keeps working without WhatsApp's Contacts permission, as far as Parley can tell.
                ParleyListItem(
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    headlineContent = { Text(stringResource(R.string.who_messengers)) },
                    supportingContent = { Text(stringResource(WhatsAppNotice.REVOKE_TEXT_RES)) },
                )
            }

            item { Section(stringResource(R.string.who_private_default)) }
            item {
                SwitchRow(stringResource(R.string.who_save_private), stringResource(R.string.who_save_private_summary), s.privateByDefault) { v ->
                    vm.people.update { it.copy(privateByDefault = v) }
                }
                Text(stringResource(Wording.PRIVATE), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.who_what_stops),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
                LinkRow(stringResource(R.string.who_move_private), stringResource(R.string.who_move_private_summary)) {
                    vm.navigate(NavEvent.Tab(StartTab.CONTACTS))
                    vm.toast(res.getString(R.string.who_move_private_hint))
                }
            }

            item { Section(stringResource(R.string.who_share_one)) }
            item {
                ParleyListItem(
                    leadingContent = { Icon(Icons.Rounded.Shield, null) },
                    headlineContent = { Text(stringResource(Wording.PICK)) },
                    supportingContent = {
                        Text(stringResource(whoPickerText(Build.VERSION.SDK_INT)))
                    },
                )
                SwitchRow(
                    stringResource(R.string.who_one_number), stringResource(R.string.who_one_number_summary),
                    s.pickerOneField,
                ) { v -> vm.people.update { it.copy(pickerOneField = v) } }
            }

            item { Section(stringResource(R.string.who_private_names_section)) }
            item {
                val pn by vm.c.people.privateNames.state.collectAsStateWithLifecycle()
                val allowed = pn.approvals.count { it.value == LookupApproval.ALLOWED }
                LinkRow(
                    settingTitle("private_directory"),
                    if (pn.directory) pluralStringResource(R.plurals.who_private_names_on, allowed, allowed) else stringResource(R.string.dc_off),
                ) {
                    open(PeopleRoutes.PrivateNames)
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

/** One app's decision, with a menu to allow, deny or forget it. */
@Composable
private fun ApprovalRow(pkg: String, label: String, a: LookupApproval, set: (LookupApproval?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ParleyListItem(
        modifier = Modifier.clickable { menu = true },
        leadingContent = { AppIcon(pkg) },
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                when (a) {
                    LookupApproval.ALLOWED -> stringResource(R.string.pn_allowed)
                    LookupApproval.DENIED -> stringResource(R.string.pn_not_allowed)
                    LookupApproval.PENDING -> stringResource(R.string.pn_waiting)
                },
            )
        },
        trailingContent = {
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.blk_allow)) }, onClick = { menu = false; set(LookupApproval.ALLOWED) })
                DropdownMenuItem({ Text(stringResource(R.string.privnames_deny)) }, onClick = { menu = false; set(LookupApproval.DENIED) })
                DropdownMenuItem({ Text(stringResource(R.string.pn_forget)) }, onClick = { menu = false; set(null) })
            }
        },
    )
}

/**
 * Settings › Privacy › "Private names in other phone apps": the opt-in contacts Directory, the phone apps allowed to
 * use it, and the access log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateNamesScreen(vm: AppViewModel, back: () -> Unit) {
    SensitiveScreen()
    val context = LocalContext.current
    val access = vm.c.people.privateNames
    val st by access.state.collectAsStateWithLifecycle()
    val pm = context.packageManager
    fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    // "Allow" shows the package and its signing certificate first; the other answers apply at once.
    var approving by remember { mutableStateOf<String?>(null) }
    approving?.let { pkg -> PrivateNameApprovalDialog(access, pkg) { approving = null } }
    fun decide(pkg: String, a: LookupApproval?) {
        if (a == LookupApproval.ALLOWED) approving = pkg else access.setApproval(pkg, a)
    }

    // Scroll-linked top-bar tint.
    val barTint = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        ParleyTopBar(stringResource(R.string.pn_title), onBack = back, scrollBehavior = barTint)
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                SwitchRow(
                    settingTitle("private_directory"),
                    stringResource(R.string.pn_directory_summary),
                    st.directory,
                ) { on -> PrivateDirectoryProvider.setEnabled(context, vm.c, on) }
                Text(
                    stringResource(R.string.pn_directory_text),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (st.directory || st.approvals.isNotEmpty()) {
                item { Section(stringResource(R.string.pn_directory_apps)) }
                if (st.approvals.isEmpty()) item { ParleyListItem(headlineContent = { Text(stringResource(R.string.pn_no_app)) }) }
                items(st.approvals.entries.sortedBy { label(it.key).lowercase() }, key = { it.key }) { (pkg, a) ->
                    ApprovalRow(pkg, label(pkg), a) { decide(pkg, it) }
                }
            }
            item { Section(stringResource(R.string.pn_log)) }
            if (st.log.isEmpty()) item { ParleyListItem(headlineContent = { Text(stringResource(R.string.pn_no_requests)) }) }
            items(st.log.asReversed().take(100)) { e ->
                ParleyListItem(
                    leadingContent = { Icon(Icons.Rounded.Lock, null) },
                    headlineContent = { Text(label(e.packageName)) },
                    supportingContent = { Text(stringResource(outcomeText(e.outcome)) + " · " + Format.shortWhen(context, e.time)) },
                )
            }
            if (st.log.isNotEmpty()) item {
                TextButton({ access.clearLog() }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.blk_clear_log)) }
            }
        }
    }
}

private fun outcomeText(o: LookupOutcome): Int = when (o) {
    LookupOutcome.ANSWERED -> R.string.pn_out_answered
    LookupOutcome.NOT_FOUND -> R.string.pn_out_not_found
    LookupOutcome.DENIED -> R.string.pn_out_denied
    LookupOutcome.ASKED -> R.string.pn_waiting
    LookupOutcome.OFF -> R.string.pn_out_off
    LookupOutcome.REJECTED -> R.string.pn_out_rejected
    LookupOutcome.RATE_LIMITED -> R.string.pn_out_rate
}

/**
 * What "Pick a contact" says on [sdk]: from Android 17 apps can also use Android's own picker, which never shows
 * private or archived contacts (they aren't in the address book).
 */
@StringRes
internal fun whoPickerText(sdk: Int): Int = if (sdk >= SYSTEM_PICKER_SDK) R.string.who_picker_text_37 else R.string.who_picker_text

/** Android 17, whose own contact picker (`ACTION_PICK_CONTACTS`) apps can use instead of Parley's. */
private const val SYSTEM_PICKER_SDK = 37
