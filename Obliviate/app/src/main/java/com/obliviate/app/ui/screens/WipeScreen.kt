package com.obliviate.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Switch
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
import com.obliviate.app.ui.components.IconLabel
import com.obliviate.app.ui.components.InfoBanner
import com.obliviate.app.ui.components.ObliviateCard
import com.obliviate.app.ui.components.StatLine
import com.obliviate.app.ui.components.StorageGauge

@Composable
fun WipeScreen() {
    val context = LocalContext.current
    val state by WipeService.state.collectAsStateWithLifecycle()

    var targetIdx by rememberSaveable { mutableIntStateOf(WipeTarget.BOTH.ordinal) }
    var methodIdx by rememberSaveable { mutableIntStateOf(WipeMethod.RANDOM.ordinal) }
    var verify by rememberSaveable { mutableStateOf(true) }
    var maxCoverage by rememberSaveable { mutableStateOf(false) }
    val target = WipeTarget.entries[targetIdx]
    val method = WipeMethod.entries[methodIdx]
    val keepFree = if (maxCoverage) {
        WipeConfig.AGGRESSIVE_KEEP_FREE_BYTES
    } else {
        WipeConfig.DEFAULT_KEEP_FREE_BYTES
    }

    var showConfirm by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless — the notification is a nicety, not a requirement */
        WipeService.start(context, WipeConfig(target, method, keepFree, verify))
    }

    fun launchWipe() {
        val config = WipeConfig(target, method, keepFree, verify)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            WipeService.start(context, config)
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
            is WipeUiState.Done -> {
                val clean = s.verifyMismatches == 0
                val verifyLine = when {
                    s.verifiedBytes <= 0 -> "Verification" to "skipped"
                    clean -> "Verified" to "${formatBytes(s.verifiedBytes)} sampled · OK"
                    else -> "Verified" to "${s.verifyMismatches} mismatch(es)"
                }
                ResultCard(
                    success = clean,
                    title = if (clean) "Wipe complete" else "Wipe complete — with warnings",
                    lines = listOf(
                        "Overwritten" to formatBytes(s.bytesOverwritten),
                        "Passes" to s.passes.toString(),
                        "Volumes wiped" to s.volumesWiped.toString(),
                        "Time" to formatDuration(s.elapsedMs),
                        verifyLine,
                    ),
                    onDismiss = { WipeService.reset() },
                )
            }
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
            SectionTitle("Options")
            ToggleRow(
                title = "Verify after wipe",
                subtitle = "Read the fill back to confirm it reached storage (logical-layer check).",
                checked = verify,
                onCheckedChange = { verify = it },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Maximum coverage",
                subtitle = "Fill closer to full — keeps only " +
                    "${formatBytes(WipeConfig.AGGRESSIVE_KEEP_FREE_BYTES)} free instead of " +
                    "${formatBytes(WipeConfig.DEFAULT_KEEP_FREE_BYTES)}. Slightly riskier.",
                checked = maxCoverage,
                onCheckedChange = { maxCoverage = it },
            )

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
                IconLabel(Icons.Rounded.Bolt, "Start wipe")
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
    val base = when (p.phase) {
        WipePhase.PREPARING -> "Preparing"
        WipePhase.FILLING -> "Overwriting · pass ${p.pass}/${p.totalPasses}"
        WipePhase.VERIFYING -> "Verifying"
        WipePhase.DELETING -> "Releasing space"
        WipePhase.DONE -> "Finishing"
    }
    val phaseLabel = if (p.volumeCount > 1) "$base · vol ${p.volume}/${p.volumeCount}" else base
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
            Spacer(Modifier.width(8.dp))
            Text(
                title,
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
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ObliviateCard(modifier = Modifier.clickable { onCheckedChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
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
