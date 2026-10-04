package app.parley.ui.export

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.backup.WrongKeyException
import app.parley.common.vcard.SealedVCard
import app.parley.data.vault.VaultCrypto
import app.parley.security.AppLock
import app.parley.ui.ConfirmDialog
import app.parley.ui.backup.PassField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks for an encrypted vCard's passphrase and checks it before anything is imported (a wrong one, or a backup picked
 * by mistake, is said here, not after the import starts). Private contacts are unlocked next, so the ones in the file
 * can land private. [onOpened] gets the passphrase; the caller wipes it after the import.
 */
@Composable
fun SealedImportDialog(uri: Uri, onDismiss: () -> Unit, onOpened: (CharArray) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun check() {
        checking = true
        problem = null
        val secret = pass.toCharArray()
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                // Opening may keep its own copy of the passphrase: it is wiped here, whatever happens.
                val copy = secret.copyOf()
                try {
                    context.contentResolver.openInputStream(uri)?.use { SealedVCard.open(it, copy) }
                    null
                } catch (_: WrongKeyException) {
                    res.getString(R.string.import_sealed_wrong)
                } catch (_: SealedVCard.BackupFileException) {
                    res.getString(R.string.import_sealed_backup)
                } catch (_: Exception) {
                    res.getString(R.string.import_sealed_unreadable)
                } finally {
                    copy.fill('\u0000')
                }
            }
            checking = false
            if (error != null) {
                secret.fill('\u0000')
                problem = error
                return@launch
            }
            pass = ""
            val act = context as? ComponentActivity
            if (VaultCrypto.detailNeedsUnlock() && act != null) {
                // Unlock cancelled: nothing is imported (private cards would fail, and a second try would repeat the rest).
                AppLock.authenticateForVault(act) { ok ->
                    if (ok) {
                        onOpened(secret)
                    } else {
                        secret.fill('\u0000')
                        problem = res.getString(R.string.import_sealed_locked)
                    }
                }
            } else {
                onOpened(secret)
            }
        }
    }

    ConfirmDialog(
        title = stringResource(R.string.import_sealed_title),
        text = null,
        confirmLabel = stringResource(R.string.import_sealed_open),
        onConfirm = ::check,
        onDismiss = onDismiss,
        confirmEnabled = pass.isNotEmpty() && !checking,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.import_sealed_text), style = MaterialTheme.typography.bodyMedium)
                PassField(stringResource(R.string.export_pass), pass) { pass = it; problem = null }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (checking) LinearProgressIndicator()
            }
        },
    )
}
