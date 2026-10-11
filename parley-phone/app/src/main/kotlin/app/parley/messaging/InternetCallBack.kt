package app.parley.messaging

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyDialog
import app.parley.ui.showMessage

/** [pkg]'s name, looked up once per app. */
@Composable
fun rememberCallAppLabel(pkg: String?): String? {
    val context = LocalContext.current
    return remember(pkg) { pkg?.let { CallApps.label(context, it) } }
}

/**
 * Call back for a call that came through [pkg] over the internet: asks whether to call in the app (the usual way)
 * or by phone, which goes through the phone network and may cost. Tapping outside calls nobody.
 */
@Composable
fun InternetCallBackDialog(number: String, accountId: String?, pkg: String, onCallByPhone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val app = rememberCallAppLabel(pkg) ?: pkg
    val installed = remember(pkg) { CallApps.installed(context, pkg) }
    var go by remember { mutableStateOf(false) }
    if (go) {
        LaunchedEffect(Unit) {
            if (!CallApps.callBack(context, pkg, number, accountId)) showMessage(context, res.getString(R.string.msg_app_unavailable, app))
            onDismiss()
        }
    }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recents_app_call_back_title, app)) },
        text = { Text(stringResource(if (installed) R.string.recents_app_call_back_text else R.string.recents_app_call_back_gone, app)) },
        confirmButton = {
            if (installed) {
                TextButton(onClick = { go = true }, enabled = !go) { Text(stringResource(R.string.recents_app_call_back_in_app, app)) }
            } else {
                TextButton(onClick = { onDismiss(); onCallByPhone() }) { Text(stringResource(R.string.recents_app_call_by_phone)) }
            }
        },
        dismissButton = {
            if (installed) {
                TextButton(onClick = { onDismiss(); onCallByPhone() }) { Text(stringResource(R.string.recents_app_call_by_phone)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dc_cancel)) }
            }
        },
    )
}
