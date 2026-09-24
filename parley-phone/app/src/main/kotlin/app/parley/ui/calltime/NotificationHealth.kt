package app.parley.ui.calltime

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationImportant
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.Permissions
import app.parley.ui.CallColors
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle

/** One thing that decides whether calls reliably show up (A4). */
data class HealthCheck(
    val key: String,
    val title: String,
    val ok: Boolean,
    val whyOff: String,
    val fixLabel: String,
    /** Needed for incoming calls to show; the rest are recommendations and don't trigger the banner. */
    val critical: Boolean,
)

object NotificationHealth {
    fun check(context: Context): List<HealthCheck> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val dialer = Permissions.isDefaultDialer(context)
        val battery = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        fun s(id: Int) = context.getString(id)
        return listOf(
            HealthCheck("role", s(R.string.ct_health_role), dialer, s(R.string.ct_health_role_off), s(R.string.set_set_default), critical = true),
            HealthCheck("notif", s(R.string.ct_health_notif), notifications, s(R.string.ct_health_notif_off), s(R.string.ct_health_allow), critical = true),
            HealthCheck("fsi", s(R.string.ct_health_fsi), fullScreen, s(R.string.ct_health_fsi_off), s(R.string.ct_health_allow), critical = true),
            HealthCheck("battery", s(R.string.ct_health_battery), battery, s(R.string.ct_health_battery_off), s(R.string.set_action_change), critical = false),
        )
    }

    /** Identifies the current set of critical problems, so a dismissed banner comes back when something new breaks. */
    fun problemKey(checks: List<HealthCheck>): String = checks.filter { it.critical && !it.ok }.joinToString(",") { it.key }

    /** The screen that fixes [check]: app notification settings, the full-screen permission page, battery list. */
    fun fixIntent(context: Context, check: HealthCheck): Intent? = when (check.key) {
        "notif" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "fsi" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, ("package:" + context.packageName).toUri()) else null
        "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        "role" -> context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        else -> null
    }
}

/** Re-checks whenever the screen resumes (the user may be back from system settings). */
@Composable
private fun rememberHealth(vm: AppViewModel): List<HealthCheck> {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(NotificationHealth.check(context)) }
    LifecycleResumeEffect(Unit) {
        checks = NotificationHealth.check(context)
        vm.refreshEnvironment()
        onPauseOrDispose { }
    }
    return checks
}

@Composable
private fun rememberFixer(vm: AppViewModel, onDone: () -> Unit = {}): (HealthCheck) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshEnvironment(); onDone() }
    return { check ->
        NotificationHealth.fixIntent(context, check)?.let { intent ->
            runCatching { launcher.launch(intent) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + context.packageName).toUri())) }
            }
        }
    }
}

/** "Calls will reach you" card on the privacy dashboard: each check with a one-tap fix (A4). */
@Composable
fun NotificationHealthCard(vm: AppViewModel) {
    // Coming back from the fix screen resumes this screen, which checks again.
    val shown = rememberHealth(vm)
    val fix = rememberFixer(vm) {}
    val allOk = shown.all { it.ok }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = if (shown.any { it.critical && !it.ok }) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(if (allOk) R.string.ct_health_all_ok else R.string.ct_health_check),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            shown.forEach { c ->
                ListItem(
                    leadingContent = {
                        Icon(
                            if (c.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
                            tint = if (c.ok) CallColors.Accept else if (c.critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        )
                    },
                    headlineContent = { Text(c.title) },
                    supportingContent = { if (!c.ok) Text(c.whyOff) },
                    trailingContent = { if (!c.ok) TextButton({ fix(c) }) { Text(c.fixLabel) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/**
 * Home banner when something stops calls from showing (A4). Dismissing hides it until a different problem
 * appears.
 */
@Composable
fun NotificationHealthBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val checks = rememberHealth(vm)
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    val key = NotificationHealth.problemKey(checks)
    val fix = rememberFixer(vm) {}
    if (key.isEmpty() || key == config.healthBannerDismissed) return
    val first = checks.first { it.critical && !it.ok }
    Card(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.NotificationImportant, null, Modifier.padding(end = 12.dp, top = 2.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ct_health_banner_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    first.whyOff + (checks.count { it.critical && !it.ok }.takeIf { it > 1 }?.let { " " + stringResource(R.string.ct_health_more, it - 1) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ vm.c.calling.update { it.copy(healthBannerDismissed = key) } }) { Text(stringResource(R.string.ct_not_now)) }
            TextButton({ fix(first) }) { Text(first.fixLabel) }
        }
    }
}
