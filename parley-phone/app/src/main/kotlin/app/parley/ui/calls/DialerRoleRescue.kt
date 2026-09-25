package app.parley.ui.calls

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.calls.RoleRescue

/**
 * P4: asks Android to make Parley the default phone app. Some phones answer "no" at once without showing the question
 * (the user declined twice before, the maker blocks the request, or Android restricts a sideloaded install). A refusal
 * that comes back within [RoleRescue.SILENT_CANCEL_MS] can't be a person pressing Cancel, so then [DialerRoleGuide]
 * opens: how to set it by hand on this Android version, with App info. A real Cancel never shows the guide.
 *
 * Returns the function that starts the request. Onboarding, Settings and the blocking status card can all use it.
 */
@Composable
fun rememberDialerRoleRequest(onResult: (granted: Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    var startedAt by remember { mutableLongStateOf(0L) }
    var guide by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val granted = r.resultCode == Activity.RESULT_OK || isDefaultDialer(context)
        if (RoleRescue.silentlyRefused(granted, SystemClock.elapsedRealtime() - startedAt)) guide = true
        result(granted)
    }
    if (guide) DialerRoleGuide(onDismiss = { guide = false })
    return remember(launcher) {
        {
            val rm = context.getSystemService(RoleManager::class.java)
            when {
                rm == null || !rm.isRoleAvailable(RoleManager.ROLE_DIALER) -> guide = true
                rm.isRoleHeld(RoleManager.ROLE_DIALER) -> result(true)
                else -> {
                    startedAt = SystemClock.elapsedRealtime()
                    runCatching { launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }.onFailure { guide = true }
                }
            }
        }
    }
}

private fun isDefaultDialer(context: Context): Boolean =
    runCatching { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true }.getOrDefault(false)

/** P4: the by-hand guide for this Android version, with buttons to App info and to Default apps. */
@Composable
fun DialerRoleGuide(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val steps = when (RoleRescue.variant(Build.VERSION.SDK_INT)) {
        RoleRescue.Variant.ANDROID_10_11 -> stringResource(R.string.role_rescue_steps_q)
        RoleRescue.Variant.ANDROID_12 -> stringResource(R.string.role_rescue_steps_s)
        RoleRescue.Variant.ANDROID_13_PLUS -> stringResource(R.string.role_rescue_steps_s) + "\n\n" + stringResource(R.string.role_rescue_restricted)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.role_rescue_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.role_rescue_intro), style = MaterialTheme.typography.bodyMedium)
                Text(steps, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Row {
                TextButton({ open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }) {
                    Text(stringResource(R.string.role_rescue_app_info))
                }
                TextButton({ open(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text(stringResource(R.string.role_rescue_default_apps)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.role_rescue_close)) } },
    )
}

/** Some phones have no "Default apps" screen of their own: App info is always there. */
private fun open(context: Context, intent: Intent) {
    val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
