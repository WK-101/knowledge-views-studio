package com.obliviate.app.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obliviate.app.core.clean.JunkCleaner
import com.obliviate.app.core.formatBytes
import com.obliviate.app.ui.components.IconLabel
import com.obliviate.app.ui.components.InfoBanner
import com.obliviate.app.ui.components.ObliviateCard
import com.obliviate.app.ui.components.StatLine

@Composable
fun CleanScreen(vm: CleanViewModel = viewModel()) {
    val context = LocalContext.current

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.scanJunk() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- App cache ------------------------------------------------------
        Text("App cache", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ObliviateCard {
            StatLine("Obliviate cache", formatBytes(vm.cacheBytes))
            vm.lastFreed?.let {
                StatLine("Last cleaned", formatBytes(it), MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.clearCache() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconLabel(Icons.Rounded.CleaningServices, "Clear cache")
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Storage breakdown ---------------------------------------------
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ObliviateCard {
            StorageBar("Internal (/data)", vm.internal)
            Spacer(Modifier.height(14.dp))
            StorageBar("Shared storage", vm.shared)
        }

        Spacer(Modifier.height(20.dp))

        // ---- Junk scan (optional all-files access) --------------------------
        Text("Junk scan", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        InfoBanner(
            icon = Icons.Rounded.Info,
            text = "Scans shared storage for clearly-disposable items only — temp files " +
                "(.tmp, .log, .part…) and empty folders. It never touches your documents, photos, " +
                "or other apps. This needs \"All files access\", which you can revoke anytime.",
        )
        Spacer(Modifier.height(12.dp))
        ObliviateCard {
            val granted = hasAllFilesAccess()
            if (!granted) {
                Text(
                    "All files access is not granted yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }.onFailure {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        } else {
                            legacyLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(Icons.Rounded.FolderOpen, "Grant all files access")
                }
            } else if (vm.scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(
                        "Scanning… ${vm.scanCount} found",
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else if (vm.junk.isNotEmpty()) {
                val total = vm.junk.sumOf { it.sizeBytes }
                StatLine("Junk items", vm.junk.size.toString())
                StatLine("Reclaimable", formatBytes(total))
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.deleteJunk() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(Icons.Rounded.DeleteForever, "Delete ${vm.junk.size} item(s)")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.scanJunk() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Re-scan")
                }
            } else {
                vm.lastFreed?.let {
                    StatLine("Last cleaned", formatBytes(it), MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = { vm.scanJunk() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan for junk")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StorageBar(label: String, stat: JunkCleaner.StorageStat) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatBytes(stat.freeBytes)} free",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { stat.usedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatBytes(stat.usedBytes)} used of ${formatBytes(stat.totalBytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
