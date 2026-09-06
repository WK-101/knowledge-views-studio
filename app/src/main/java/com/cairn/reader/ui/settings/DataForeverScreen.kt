@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.settings

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MenuBook
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.ui.theme.ReadingSerif
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * The "Your data, forever" panel — Cairn's headline promise made tangible. Everything Cairn holds is
 * yours, on your device, in open formats, and every door out is one tap away: a full backup, a
 * Markdown vault, an EPUB, an auto-backup folder. If Cairn ever disappeared, your library wouldn't.
 */
@Composable
fun DataForeverScreen(
    padding: PaddingValues,
    onOpenDrawer: () -> Unit = {},
    onOpenBackupSettings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val highlights by viewModel.highlightCount.collectAsStateWithLifecycle()
    val saved by viewModel.savedCount.collectAsStateWithLifecycle()

    val archiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Writing archive…", Toast.LENGTH_SHORT).show()
            viewModel.exportArchive(uri) { ok ->
                Toast.makeText(context, if (ok) "Full archive saved" else "Couldn't write the archive", Toast.LENGTH_LONG).show()
            }
        }
    }
    val markdownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Exporting Markdown…", Toast.LENGTH_SHORT).show()
            viewModel.exportMarkdownVault(uri) { s -> Toast.makeText(context, s, Toast.LENGTH_LONG).show() }
        }
    }
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setBackupFolder(uri.toString())
            Toast.makeText(context, "Auto-backup on — a copy was saved", Toast.LENGTH_SHORT).show()
        }
    }

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
                SectionLabel("TAKE IT WITH YOU")
                DataAction(
                    Icons.Outlined.Backup, "Full backup (.zip)",
                    "Everything — articles, offline copies, highlights, settings — in one file you can restore on any device.",
                ) { archiveLauncher.launch("cairn-backup-${today()}.zip") }
                DataAction(
                    Icons.Outlined.Description, "Markdown / Obsidian vault",
                    "Your whole library as plain .md files with frontmatter, tags and highlights — for Obsidian, Logseq, or anywhere.",
                ) { markdownLauncher.launch(null) }
                DataAction(
                    Icons.Outlined.MenuBook, "EPUB — send to Kindle",
                    "Bundle your library into one e-book for your Kindle, Kobo, or any reader.",
                ) {
                    Toast.makeText(context, "Building EPUB…", Toast.LENGTH_SHORT).show()
                    viewModel.exportLibraryEpub { file ->
                        if (file == null) Toast.makeText(context, "Nothing to export yet.", Toast.LENGTH_LONG).show()
                        else runCatching {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/epub+zip"; putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Cairn Library"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Send library to Kindle"))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionLabel("KEEP IT SAFE AUTOMATICALLY")
                DataAction(
                    Icons.Outlined.CloudSync, "Automatic backup folder",
                    "Pick a folder (local, Drive, Dropbox…) and Cairn writes a dated backup there on a schedule.",
                ) { backupFolderLauncher.launch(null) }
                DataAction(
                    Icons.AutoMirrored.Outlined.OpenInNew, "WebDAV sync & device-to-device transfer",
                    "Back up to your own Nextcloud/WebDAV server, or move everything to a new phone with no account.",
                ) { onOpenBackupSettings() }
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun DataAction(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.fillMaxWidth().padding(top = 1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

private fun today(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
