package com.obliviate.app.ui.screens

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obliviate.app.core.root.RootManager
import com.obliviate.app.ui.components.IconLabel
import com.obliviate.app.ui.components.InfoBanner
import com.obliviate.app.ui.components.ObliviateCard
import kotlinx.coroutines.launch

@Composable
fun DisposeScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    var recheck by remember { mutableIntStateOf(0) }

    val encrypted = remember(recheck) { isEncrypted(context) }
    val secureLock = remember(recheck) { isDeviceSecure(context) }
    val rootAvailable = remember { RootManager.isRootAvailable() }

    val scope = rememberCoroutineScope()
    var trimming by remember { mutableStateOf(false) }
    var trimOutput by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Prepare device for disposal", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "The only reliable way to make a phone's data unrecoverable is a factory " +
                "reset on an encrypted device — it destroys the encryption key, so every " +
                "remaining byte becomes unreadable ciphertext. Follow these steps in order.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        // ---- Readiness ------------------------------------------------------
        ObliviateCard {
            Text("Readiness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            StatusRow(
                ok = encrypted,
                label = "Storage encryption",
                detail = if (encrypted) "Active — a factory reset will crypto-erase." else "Not detected as active.",
            )
            Spacer(Modifier.height(8.dp))
            StatusRow(
                ok = secureLock,
                label = "Secure screen lock",
                detail = if (secureLock) "Set — strengthens the key that gets destroyed." else "Not set — set a PIN/password below.",
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { recheck++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Re-check")
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Steps", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        // ---- Step 1: screen lock -------------------------------------------
        StepCard(
            number = 1,
            icon = Icons.Rounded.Lock,
            title = "Set a strong screen lock",
            body = "Android entangles the disk-encryption key with your lockscreen secret. " +
                "A strong PIN or password makes the crypto-erase meaningful.",
            done = secureLock,
        ) {
            Button(
                onClick = {
                    openSettings(
                        context,
                        Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD),
                        Intent(Settings.ACTION_SECURITY_SETTINGS),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { IconLabel(Icons.Rounded.Lock, if (secureLock) "Change screen lock" else "Set screen lock") }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Step 2: wipe free space ---------------------------------------
        StepCard(
            number = 2,
            icon = Icons.Rounded.DeleteSweep,
            title = "Overwrite free space",
            body = "Scrub the accessible free space first (Both volumes, with verification). " +
                "This clears remnants of already-deleted files from the logical layer before the reset.",
            done = false,
        ) {
            Button(onClick = { onNavigate("wipe") }, modifier = Modifier.fillMaxWidth()) {
                IconLabel(Icons.Rounded.DeleteSweep, "Open the wipe tool")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Step 3: factory reset -----------------------------------------
        StepCard(
            number = 3,
            icon = Icons.Rounded.RestartAlt,
            title = "Factory reset (cryptographic erase)",
            body = "This is the step that makes data unrecoverable — even to chip-off forensics — " +
                "by destroying the encryption key. Open Settings and choose " +
                "System → Reset options → Erase all data (factory reset).",
            done = false,
        ) {
            Button(
                onClick = {
                    openSettings(
                        context,
                        Intent(Settings.ACTION_SETTINGS),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { IconLabel(Icons.Rounded.RestartAlt, "Open Settings") }
        }

        // ---- Advanced (root) -----------------------------------------------
        if (rootAvailable) {
            Spacer(Modifier.height(20.dp))
            Text("Advanced (root detected)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            ObliviateCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.size(8.dp))
                    Text("fstrim free blocks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Non-destructive: asks the storage controller to TRIM already-free blocks so it " +
                        "can erase them sooner. Raw-device overwrite is documented in docs/ROOT_MODE.md " +
                        "and deliberately not one-tap (a wrong device path can brick a phone).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!trimming) {
                            trimming = true
                            trimOutput = null
                            scope.launch {
                                val r = RootManager.fstrim()
                                trimOutput = if (r.output.isBlank()) "Done (exit ${r.exitCode})" else r.output
                                trimming = false
                            }
                        }
                    },
                    enabled = !trimming,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (trimming) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Running fstrim…")
                    } else {
                        IconLabel(Icons.Rounded.Terminal, "Run fstrim (root)")
                    }
                }
                trimOutput?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        InfoBanner(
            icon = Icons.Rounded.Warning,
            text = "A factory reset erases everything on the device. Make sure you have backed up " +
                "anything you want to keep, and that you've signed out of accounts (e.g. Google) " +
                "to avoid activation locks.",
            container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
            onContainer = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusRow(ok: Boolean, label: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    done: Boolean,
    action: @Composable () -> Unit,
) {
    ObliviateCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Rounded.CheckCircle else icon,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text("$number. $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        action()
    }
}

// ---- helpers ---------------------------------------------------------------

private fun isEncrypted(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        ?: return false
    val status = runCatching { dpm.storageEncryptionStatus }
        .getOrDefault(DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED)
    return status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
        status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
        status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY
}

private fun isDeviceSecure(context: Context): Boolean {
    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
    return km.isDeviceSecure
}

private fun openSettings(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
}
