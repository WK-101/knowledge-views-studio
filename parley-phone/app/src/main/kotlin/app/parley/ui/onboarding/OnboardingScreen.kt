package app.parley.ui.onboarding

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ux.InstallSource
import kotlinx.coroutines.launch

/**
 * First run, in three short steps: what Parley promises, the default phone app (with a word about Android's
 * restricted settings first when Parley was installed from a file), then U1's permissions page: one row per
 * permission with why it's asked and what still works without it, a single "Allow all" and a switch per row.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }

    fun finish() {
        scope.launch { vm.c.settings.update { it.copy(onboardingDone = true) } }
        vm.refreshEnvironment()
        onDone()
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                0 -> WelcomeStep { step = 1 }
                1 -> DefaultDialerStep(vm) { step = 2 }
                else -> PermissionsStep(vm, ::finish)
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(next: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.onb_tagline), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Promise(Icons.Rounded.WifiOff, stringResource(R.string.onb_offline_title), stringResource(R.string.onb_offline_text))
    Promise(Icons.Rounded.Block, stringResource(R.string.onb_spam_title), stringResource(R.string.onb_spam_text))
    Promise(Icons.Rounded.Code, stringResource(R.string.onb_open_title), stringResource(R.string.onb_open_text))
    Spacer(Modifier.weight(1f))
    Button(next, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.ux_onb_next)) }
}

/** The installer package of this app (null when unknown or unreadable). */
private fun installerOf(context: Context): String? = runCatching {
    if (Build.VERSION.SDK_INT >= 30) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
}.getOrNull()

private fun appInfo(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ColumnScope.DefaultDialerStep(vm: AppViewModel, next: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val rm = remember { context.getSystemService(RoleManager::class.java) }
    val available = rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)
    // Nothing to ask: already the phone app, or a device without a phone role.
    LaunchedEffect(isDefault, available) { if (isDefault || !available) next() }
    val sideloaded = remember { InstallSource.needsRestrictedSettingsHelp(installerOf(context), Build.VERSION.SDK_INT) }
    var failed by remember { mutableStateOf(false) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshEnvironment()
        if (vm.isDefaultDialer.value) {
            next()
        } else {
            failed = true
            vm.toast(res.getString(R.string.onb_set_later))
        }
    }

    Spacer(Modifier.height(24.dp))
    Icon(Icons.Rounded.Phone, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.ux_onb_dialer_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.onb_prompt_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.ux_onb_dialer_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // U1: sideloaded on Android 13+: say what "Restricted setting" means before the role request can fail.
    // Hook: the default-dialer rescue guide (DialerRoleRescue) can take this card's place once it exists.
    if (sideloaded) RestrictedSettingsCard(emphasise = failed) { appInfo(context) }
    Spacer(Modifier.weight(1f))
    Button(
        { rm?.let { role.launch(it.createRequestRoleIntent(RoleManager.ROLE_DIALER)) } ?: next() },
        Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(R.string.onb_set_default)) }
    TextButton(next, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.ux_not_now)) }
}

/** U1: why the default-phone-app step may say "Restricted setting" on a sideloaded install, and the way out. */
@Composable
private fun RestrictedSettingsCard(emphasise: Boolean, openAppInfo: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (emphasise) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.ux_onb_restricted_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.ux_onb_restricted_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ux_onb_restricted_steps), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(openAppInfo) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ux_onb_app_info))
            }
        }
    }
}

/** One row of the permissions page: the Android permissions it covers, why, and what works without them. */
private class PermissionRow(val icon: ImageVector, val title: Int, val reason: Int, val without: Int, val permissions: List<String>)

private fun permissionRows(): List<PermissionRow> = buildList {
    add(PermissionRow(Icons.Rounded.People, R.string.ux_perm_contacts, R.string.ux_perm_contacts_why, R.string.ux_perm_contacts_without,
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS)))
    add(PermissionRow(Icons.Rounded.History, R.string.ux_perm_call_log, R.string.ux_perm_call_log_why, R.string.ux_perm_call_log_without,
        listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)))
    add(PermissionRow(Icons.Rounded.Phone, R.string.ux_perm_phone, R.string.ux_perm_phone_why, R.string.ux_perm_phone_without,
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.ANSWER_PHONE_CALLS)))
    if (Build.VERSION.SDK_INT >= 33) {
        add(PermissionRow(Icons.Rounded.Notifications, R.string.ux_perm_notifications, R.string.ux_perm_notifications_why, R.string.ux_perm_notifications_without,
            listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
    if (Build.VERSION.SDK_INT >= 31) {
        add(PermissionRow(Icons.Rounded.Bluetooth, R.string.ux_perm_nearby, R.string.ux_perm_nearby_why, R.string.ux_perm_nearby_without,
            listOf(Manifest.permission.BLUETOOTH_CONNECT)))
    }
}

@Composable
private fun ColumnScope.PermissionsStep(vm: AppViewModel, done: () -> Unit) {
    val context = LocalContext.current
    val rows = remember { permissionRows() }
    fun granted(r: PermissionRow) = r.permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var tick by remember { mutableIntStateOf(0) }
    // Rows asked for and still off: Android won't ask again, so their switch opens App info instead.
    var refused by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    var asking by remember { mutableStateOf(emptyList<Int>()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshEnvironment()
        refused = refused + asking.filter { !granted(rows[it]) }
        asking = emptyList()
        tick++
    }
    // Back from App info: show what changed there.
    LifecycleResumeEffect(Unit) {
        vm.refreshEnvironment()
        tick++
        onPauseOrDispose { }
    }
    val states = remember(tick) { rows.map { granted(it) } }
    fun ask(indices: List<Int>) {
        val wanted = indices.filter { !states[it] }
        if (wanted.isEmpty()) return
        if (wanted.all { it in refused }) { appInfo(context); return }
        asking = wanted
        request.launch(wanted.flatMap { rows[it].permissions }.toTypedArray())
    }

    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.ux_perm_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.ux_perm_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val allGranted = states.all { it }
    FilledTonalButton({ ask(rows.indices.toList()) }, enabled = !allGranted, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (allGranted) R.string.ux_perm_all_allowed else R.string.ux_perm_allow_all))
    }
    Column {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider()
            val on = states[i]
            val title = stringResource(r.title)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(r.icon, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(title) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(r.reason))
                        Text(
                            if (!on && i in refused) stringResource(R.string.ux_perm_in_app_info) else stringResource(R.string.ux_perm_without, stringResource(r.without)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingContent = {
                    // Granted rows can only be turned off in Android's settings, so their switch opens App info.
                    Switch(
                        checked = on,
                        onCheckedChange = { if (on) appInfo(context) else ask(listOf(i)) },
                        modifier = Modifier.semantics { contentDescription = title },
                    )
                },
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Button(done, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(if (allGranted) R.string.main_done else R.string.ux_perm_continue)) }
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
