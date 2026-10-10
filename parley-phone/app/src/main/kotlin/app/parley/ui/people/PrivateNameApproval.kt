package app.parley.ui.people

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.people.LookupApproval
import app.parley.common.security.CertDigest
import app.parley.data.people.PrivateNameAccess
import androidx.activity.ComponentActivity
import app.parley.security.AppLock
import app.parley.security.SensitiveScreen
import app.parley.ui.ParleyDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Allowing a phone app to read private names through the contacts Directory, from its request notification or from
 * Privacy › Private names in other phone apps. It only ever
 * shows inside Parley, behind its lock, never from the lock screen or the notification shade, and "Allow" asks for the
 * Parley PIN (or, without one, the phone's unlock) once more. The app is named by its package and the SHA-256 of its signing certificate, which Android
 * vouches for; never by its label, which the app chooses itself (any app can call itself "Phone").
 */
@Composable
fun PrivateNameApprovalDialog(access: PrivateNameAccess, pkg: String, onDone: () -> Unit) {
    SensitiveScreen()
    // Null while it is read; "" when the app isn't installed (then there's nothing to allow).
    var cert by remember(pkg) { mutableStateOf<String?>(null) }
    LaunchedEffect(pkg) { cert = withContext(Dispatchers.IO) { access.certificateOf(pkg).orEmpty() } }
    val installed = cert?.isNotEmpty() == true
    val activity = LocalActivity.current as? ComponentActivity
    val confirmTitle = stringResource(R.string.pn_approve_title_directory)
    fun answer(a: LookupApproval) {
        access.setApproval(pkg, a)
        onDone()
    }

    // Allowing asks for Parley's PIN (or, without one, the phone's unlock) once more, whatever the app lock's delay.
    fun allow() {
        if (activity == null) return
        AppLock.confirm(activity, confirmTitle) { ok -> if (ok) answer(LookupApproval.ALLOWED) }
    }
    ParleyDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(confirmTitle) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pn_approve_package), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(pkg, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                }
                Text(stringResource(R.string.pn_approve_cert), style = MaterialTheme.typography.labelLarge)
                val c = cert
                when {
                    c == null -> LinearProgressIndicator()
                    c.isEmpty() -> Text(stringResource(R.string.pn_approve_missing), color = MaterialTheme.colorScheme.error)
                    else -> SelectionContainer {
                        Text(
                            CertDigest.shown(c), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        )
                    }
                }
                Text(stringResource(R.string.pn_approve_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(::allow, enabled = installed && activity != null) { Text(stringResource(R.string.blk_allow)) } },
        dismissButton = { TextButton({ answer(LookupApproval.DENIED) }) { Text(stringResource(R.string.privnames_deny)) } },
    )
}
