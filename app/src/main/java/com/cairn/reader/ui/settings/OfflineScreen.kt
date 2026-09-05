@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The offline & storage policy screen: how aggressively Cairn uses the network and disk.
 * Everything here stays on-device — these are limits on Cairn's own fetching, not a sync service.
 */
@Composable
fun OfflineScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline & storage") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            SectionHeader("SYNCING")
            ToggleRow(
                title = "Sync on Wi-Fi only",
                subtitle = "Automatic background refresh waits for an un-metered network. Pull-to-refresh always works.",
                checked = prefs.syncWifiOnly,
                onCheckedChange = viewModel::setSyncWifiOnly,
            )

            SectionHeader("OFFLINE COPIES")
            ToggleRow(
                title = "Download images",
                subtitle = "“Save offline” fetches every image so the article is a true self-contained copy. Off = text only.",
                checked = prefs.cacheImagesOffline,
                onCheckedChange = viewModel::setCacheImagesOffline,
            )
            ToggleRow(
                title = "Images on Wi-Fi only",
                subtitle = "On a metered network, saving offline keeps the text and skips images until you're on Wi-Fi.",
                checked = prefs.imagesWifiOnly,
                enabled = prefs.cacheImagesOffline,
                onCheckedChange = viewModel::setImagesWifiOnly,
            )

            SectionHeader("STORAGE")
            val storage by androidx.compose.runtime.produceState(initialValue = -1L, prefs.maxItemsPerFeed) {
                value = viewModel.storageBytes()
            }
            Text(
                text = when {
                    storage < 0 -> "Measuring storage…"
                    else -> "Offline copies, images & PDFs use ${formatBytes(storage)} on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Keep per feed", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Older items you haven't starred, saved, archived, filed, highlighted, or saved offline are pruned as new ones arrive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    KeepOptions.forEach { n ->
                        FilterChip(
                            selected = prefs.maxItemsPerFeed == n,
                            onClick = { viewModel.setMaxItemsPerFeed(n) },
                            label = { Text(if (n == 0) "All" else n.toString()) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Delete older than", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Also drop un-engaged items past this age on sync. Kept items are never deleted.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    AgeOptions.forEach { d ->
                        FilterChip(
                            selected = prefs.maxAgeDays == d,
                            onClick = { viewModel.setMaxAgeDays(d) },
                            label = { Text(if (d == 0) "Never" else "$d days") },
                        )
                    }
                }
            }
        }
    }
}

private val KeepOptions = listOf(0, 50, 100, 200, 500)
private val AgeOptions = listOf(0, 7, 14, 30, 90)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.4f),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
