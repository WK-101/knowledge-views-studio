package app.parley.ui.blocking

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.parley.AppViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.data.Permissions

/** One gap in what screening can see, with its fix. */
private data class Gap(val text: String, val fix: String?, val action: (() -> Unit)?)

/**
 * B15: which path screening runs on and what it can't see, each gap with a one-tap fix.
 * - Phone app: every call is checked, including contacts, hidden numbers and the SIM.
 * - Screening only: Android never shows hidden callers or the SIM to a screening app.
 * - Neither: nothing is screened.
 */
@Composable
fun ScreeningStatusCard(vm: AppViewModel) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val roles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshEnvironment()
        refresh++
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshEnvironment()
        refresh++
    }
    val rm = context.getSystemService(RoleManager::class.java)
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val hasContacts by vm.hasContactsPermission.collectAsStateWithLifecycle()
    val dialer = remember(refresh, isDefault) { Permissions.isDefaultDialer(context) }
    val screener = remember(refresh, isDefault) { Permissions.isCallScreener(context) }
    val contacts = remember(refresh, hasContacts) { Permissions.has(context, Manifest.permission.READ_CONTACTS) }
    val notifications = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }

    fun requestRole(role: String) {
        if (rm != null && rm.isRoleAvailable(role)) roles.launch(rm.createRequestRoleIntent(role))
    }

    val gaps = buildList {
        if (!dialer) add(Gap(
            if (screener) "Hidden numbers and the SIM can't be seen in screening-only mode, so those rules are off." else "Calls aren't screened at all yet.",
            "Make Parley your phone app", { requestRole(RoleManager.ROLE_DIALER) },
        ))
        if (!dialer && !screener) add(Gap("Or keep your phone app and let Parley only screen calls.", "Use for screening", { requestRole(RoleManager.ROLE_CALL_SCREENING) }))
        if (dialer && !screener) add(Gap("Blocked calls may ring for a moment before Parley stops them.", "Screen before ringing", { requestRole(RoleManager.ROLE_CALL_SCREENING) }))
        if (!contacts) add(Gap("Without contacts access Parley can't tell who you know, so it lets every call ring rather than risk blocking a contact.", "Allow contacts", { permission.launch(Manifest.permission.READ_CONTACTS) }))
        if (!notifications) add(Gap("Notifications are off: you won't see blocked calls or quiet-hours replies.", "Turn on", {
            roles.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }))
        if (Build.VERSION.SDK_INT < 29) add(Gap("This Android version can't screen calls.", null, null))
    }
    val ok = dialer && contacts
    val title = when {
        dialer -> "Parley is your phone app: every call is checked, including contacts, hidden numbers and the SIM."
        screener -> "Screening only: Parley checks calls before your phone app rings."
        else -> "Screening is off"
    }
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.GppGood else Icons.Rounded.GppMaybe, null)
                Text("  $title", style = MaterialTheme.typography.titleSmall)
            }
            Text("Works offline: numbers are checked on your phone, never sent anywhere.", style = MaterialTheme.typography.bodySmall)
            gaps.forEach { g ->
                Text(g.text, style = MaterialTheme.typography.bodyMedium)
                if (g.fix != null && g.action != null) TextButton(g.action) { Text(g.fix) }
            }
        }
    }
}
