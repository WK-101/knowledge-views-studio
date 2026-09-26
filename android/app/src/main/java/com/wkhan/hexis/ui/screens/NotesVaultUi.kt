package com.wkhan.hexis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wkhan.hexis.ui.AppViewModel
import com.wkhan.hexis.ui.components.AppTextField

/**
 * L11 — the wall shown when a vaulted note is opened before the session is unlocked. Its ciphertext body
 * is never rendered; the only way in is the passphrase.
 */
@Composable
fun VaultLockedPane(vm: AppViewModel, title: String, onBack: () -> Unit) {
    var showUnlock by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("🔐", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.size(10.dp))
            Text(title.ifBlank { "Locked note" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text("This note is in your Vault — encrypted at rest.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(20.dp))
            Button(onClick = { showUnlock = true }) { Text("Unlock") }
            Spacer(Modifier.size(6.dp))
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
    if (showUnlock) VaultUnlockDialog(vm = vm, onReady = { showUnlock = false }, onDismiss = { showUnlock = false })
}

/**
 * L11 — set up (first time) or unlock (this session) the Vault passphrase. The passphrase is never stored;
 * setup keeps only an encrypted verifier, and unlock validates against it. [onReady] fires once the vault
 * is unlocked for the session.
 */
@Composable
fun VaultUnlockDialog(vm: AppViewModel, onReady: () -> Unit, onDismiss: () -> Unit) {
    val configured = remember { vm.vaultConfigured() }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pass.length >= 4 && (configured || pass == confirm),
                onClick = {
                    if (configured) {
                        if (vm.unlockVault(pass)) onReady() else error = "Wrong passphrase."
                    } else { vm.setUpVault(pass); onReady() }
                },
            ) { Text(if (configured) "Unlock" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (configured) "Unlock Vault" else "Create your Vault") },
        text = {
            Column {
                com.wkhan.hexis.ui.components.SecureDialogFlag()   // SEC (R2-C) — no screenshots/recents of the passphrase prompt
                Text(
                    if (configured) "Enter your vault passphrase to open encrypted notes this session."
                    else "Choose a passphrase. It encrypts vault notes at rest and in backups, and is never stored — keep it safe; it can't be recovered, only re-entered on any device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(10.dp))
                AppTextField(
                    value = pass, onValueChange = { pass = it; error = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                )
                if (!configured) {
                    Spacer(Modifier.size(8.dp))
                    AppTextField(
                        value = confirm, onValueChange = { confirm = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Confirm passphrase") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    )
                }
                error?.let { Spacer(Modifier.size(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
