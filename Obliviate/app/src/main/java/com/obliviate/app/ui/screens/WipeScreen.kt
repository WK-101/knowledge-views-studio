package com.obliviate.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obliviate.app.core.formatBytes
import com.obliviate.app.core.formatDuration
import com.obliviate.app.core.formatSpeed
import com.obliviate.app.core.service.WipeService
import com.obliviate.app.core.wipe.WipeConfig
import com.obliviate.app.core.wipe.WipeMethod
import com.obliviate.app.core.wipe.WipePhase
import com.obliviate.app.core.wipe.WipeTarget
import com.obliviate.app.core.wipe.WipeUiState
import com.obliviate.app.ui.components.InfoBanner
import com.obliviate.app.ui.components.ObliviateCard
import com.obliviate.app.ui.components.StatLine
import com.obliviate.app.ui.components.StorageGauge

@Composable
fun WipeScreen() {
    val context = LocalContext.current
    val state by WipeService.state.collectAsStateWithLifecycle()

    var targetIdx by rememberSaveable { mutableIntStateOf(WipeTarget.INTERNAL.ordinal) }
    var methodIdx by rememberSaveable { mutableIntStateOf(WipeMethod.RANDOM.ordinal) }
    val target = WipeTarget.entries[targetIdx]
    val method = WipeMethod.entries[methodIdx]

    var showConfirm by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless — the notification is a nicety, not a requirement */
        WipeService.start(context, WipeConfig(target, method))
    }

    fun launchWipe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            WipeService.start(context, WipeConfig(target, method))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        when (val s = state) {
            is WipeUiState.Running -> RunningCard(s, onCancel = { WipeService.cancel(context) })
            is WipeUiState.Done -> ResultCard(
                success = true,
                title = "Wipe complete",
                lines = listOf(
                    "Overwritten" to formatBytes(s.bytesOverwritten),
                    "Passes" to s.passes.toString(),
                    "Time" to formatDuration(s.elapsedMs),
                    "Target" to s.target.label,
                ),
                onDismiss = { WipeService.reset() },
            )
            is WipeUiState.Cancelled -> ResultCard(
                success = false,
                title = "Wipe cancelled",
                lines = listOf("Overwritten before stopping" to formatBytes(s.bytesOverwritten)),
                onDismiss = { WipeService.reset() },
            )
            is WipeUiState.Failed -> ResultCard(
                success = false,
                title = "Wipe failed",
                lines = listOf("Reason" to s.message),
                onDismiss = { WipeService.reset() },
            )
            WipeUiState.Idle -> Unit
        }

        if (state !is WipeUiState.Running) {
            Spacer(Modifier.height(4.dp))
            SectionTitle("Where to wipe")
            WipeTarget.entries.forEach { t ->
                SelectableRow(
                    selected = t == target,
                    title = t.label,
                    subtitle = t.description,
                    onClick = { targetIdx = t.ordinal },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle("Method")
            WipeMethod.entries.forEach { m ->
                SelectableRow(
                    selected = m == method,
                    title = m.label,
                    subtitle = m.description,
                    onClick = { methodIdx = m.ordinal },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))
            InfoBanner(
                icon = Icons.Rounded.Warning,
                text = "During the wipe, free space is temporarily filled almost to the brim " +
                    "(${formatBytes(WipeConfig.DEFAULT_KEEP_FREE_BYTES)} is kept free for stability) and then " +
                    "released. Keep the phone charged; you can cancel any time.",
                container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                onContainer = MaterialTheme.colorScheme.tertiary,
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Start wipe", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text("Start wiping free space?") },
            text = {
                Text(
                    "Obliviate will overwrite the free space on ${target.label.lowercase()} " +
                        "using \"${method.label}\". This can take a while and will use the " +
                        "storage heavily until it finishes."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showConfirm = false
                    launchWipe()
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RunningCard(s: WipeUiState.Running, onCancel: () -> Unit) {
    val p = s.progress
    val percent = (p.fraction * 100).toInt()
    val phaseLabel = when (p.phase) {
        WipePhase.PREPARING -> "Preparing"
        WipePhase.FILLING -> "Overwriting · pass ${p.pass}/${p.totalPasses}"
        WipePhase.DELETING -> "Releasing space"
        WipePhase.DONE -> "Finishing"
    }
    ObliviateCard {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            StorageGauge(
                fraction = p.fraction,
                centerLabel = "$percent%",
                subLabel = phaseLabel,
            )
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { p.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )
        Spacer(Modifier.height(16.dp))
        StatLine("Overwritten", formatBytes(p.bytesWritten))
        StatLine("Speed", formatSpeed(p.speedBytesPerSec))
        StatLine("Estimated total", formatBytes(p.bytesTarget))
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Cancel wipe") }
    }
}

@Composable
private fun ResultCard(
    success: Boolean,
    title: String,
    lines: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    ObliviateCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                "  $title",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(12.dp))
        lines.forEach { (k, v) -> StatLine(k, v) }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SelectableRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    ObliviateCard(
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(20.dp),
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
