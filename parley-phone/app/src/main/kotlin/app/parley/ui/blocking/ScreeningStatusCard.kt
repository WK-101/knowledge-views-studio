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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import app.parley.AppViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.blocking.BlockingText
import app.parley.data.Permissions
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.bidiLtrIfNumber

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
            stringResource(if (screener) R.string.blk_status_screener_gap else R.string.blk_status_not_screened),
            stringResource(R.string.blk_status_make_phone_app), { requestRole(RoleManager.ROLE_DIALER) },
        ))
        if (!dialer && !screener) add(Gap(stringResource(R.string.blk_status_or_screen), stringResource(R.string.blk_status_use_for_screening), { requestRole(RoleManager.ROLE_CALL_SCREENING) }))
        if (dialer && !screener) add(Gap(stringResource(R.string.blk_status_may_ring), stringResource(R.string.blk_status_screen_first), { requestRole(RoleManager.ROLE_CALL_SCREENING) }))
        if (!contacts) add(Gap(stringResource(R.string.blk_status_no_contacts), stringResource(R.string.blk_status_allow_contacts), { permission.launch(Manifest.permission.READ_CONTACTS) }))
        if (!notifications) add(Gap(stringResource(R.string.blk_status_no_notifications), stringResource(R.string.blk_status_turn_on), {
            roles.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }))
        if (Build.VERSION.SDK_INT < 29) add(Gap(stringResource(R.string.blk_status_unsupported), null, null))
    }
    val ok = dialer && contacts
    val title = stringResource(
        when {
            dialer -> R.string.blk_status_dialer
            screener -> R.string.blk_status_screener
            else -> R.string.blk_status_off
        },
    )
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.GppGood else Icons.Rounded.GppMaybe, null)
                Text("  $title", style = MaterialTheme.typography.titleSmall) // l10n-ok: no words
            }
            Text(stringResource(R.string.blk_status_offline), style = MaterialTheme.typography.bodySmall)
            gaps.forEach { g ->
                Text(g.text, style = MaterialTheme.typography.bodyMedium)
                if (g.fix != null && g.action != null) TextButton(g.action) { Text(g.fix) }
            }
        }
    }
}
