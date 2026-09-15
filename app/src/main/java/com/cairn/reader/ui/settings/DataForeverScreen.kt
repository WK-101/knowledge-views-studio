@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.settings

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.ui.theme.ReadingSerif

/**
 * The "Your data, forever" panel — Cairn's headline promise made tangible. This screen makes the
 * *case* (no account, no lock-in, open formats, everything on your device); the actual backup,
 * export and transfer tools live in one place, Settings → Backup & restore, which the single
 * button here opens. Keeping the promise and the tooling separate is deliberate: this is the "why",
 * Backup & restore is the "how".
 */
@Composable
fun DataForeverScreen(
    padding: PaddingValues,
    onOpenDrawer: () -> Unit = {},
    onOpenBackupSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val scheme = MaterialTheme.colorScheme
    val highlights by viewModel.highlightCount.collectAsStateWithLifecycle()
    val saved by viewModel.savedCount.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.your_data_forever), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) } },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Shield, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.nothing_here_is_held_hostage),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = ReadingSerif),
                        fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.cairn_has_no_account_and_no),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHighest).padding(14.dp)) {
                        Text(
                            "$saved article${if (saved == 1) "" else "s"} · $highlights highlight${if (highlights == 1) "" else "s"} safe on this device",
                            style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            item {
                // The single door out. Every backup, export and transfer tool lives behind it, so this
                // screen never duplicates them — it just points here.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.secondaryContainer)
                        .clickable(onClick = onOpenBackupSettings)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Back up & restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSecondaryContainer)
                        Text(
                            "Full backups, Markdown & EPUB export, automatic and WebDAV backups, and device-to-device transfer — all in one place.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer.copy(alpha = 0.85f),
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.onSecondaryContainer)
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.no_account_no_servers_no_lock),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
