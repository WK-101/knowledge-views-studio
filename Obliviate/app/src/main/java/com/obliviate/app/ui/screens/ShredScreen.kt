package com.obliviate.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obliviate.app.core.formatBytes
import com.obliviate.app.core.wipe.WipeMethod
import com.obliviate.app.ui.components.IconLabel
import com.obliviate.app.ui.components.InfoBanner
import com.obliviate.app.ui.components.ObliviateCard

@Composable
fun ShredScreen(vm: ShredViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.setFiles(uris) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        InfoBanner(
            icon = Icons.Rounded.Info,
            text = "Pick specific files to overwrite and delete. This uses Android's file picker, " +
                "so no broad storage permission is required — you only grant access to what you choose.",
        )
        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !vm.running,
        ) {
            IconLabel(Icons.Rounded.Add, "Select files")
        }

        if (vm.items.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Selected (${vm.items.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ObliviateCard {
                vm.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.name,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (item.sizeBytes >= 0) formatBytes(item.sizeBytes) else "?",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Method", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WipeMethod.entries.forEach { m ->
                    FilterChip(
                        selected = vm.methodIdx == m.ordinal,
                        onClick = { vm.methodIdx = m.ordinal },
                        label = { Text(m.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.run() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !vm.running,
            ) {
                IconLabel(Icons.Rounded.Warning, "Shred ${vm.items.size} file(s)")
            }
        }

        if (vm.running) {
            Spacer(Modifier.height(16.dp))
            ObliviateCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(
                        vm.progressText,
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (vm.results.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Results", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ObliviateCard {
                vm.results.forEach { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            if (r.deleted) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                            contentDescription = null,
                            tint = if (r.deleted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                r.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.clear() }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
