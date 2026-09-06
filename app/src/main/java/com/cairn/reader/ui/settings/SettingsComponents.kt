@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.settings

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.data.prefs.ThemeMode
import com.cairn.reader.ui.components.FeedSettingsSheet
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
internal fun <T> LabeledChips(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(text) },
            )
        }
    }
}

/**
 * Self-hosted WebDAV / Nextcloud backup target. The user points Cairn at a folder on their own
 * server; backups then upload there on the same schedule as the local folder, and can be pulled
 * back (merged, de-duplicated) onto any device. No account, no third-party cloud.
 */
@Composable
internal fun WebDavBackupSection(
    savedUrl: String,
    savedUser: String,
    savedPass: String,
    viewModel: SettingsViewModel,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    // Seed the fields from what's persisted; re-seed only when the saved values actually change
    // (e.g. after Save, or when DataStore first emits) so in-progress typing isn't clobbered.
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var user by remember(savedUser) { mutableStateOf(savedUser) }
    var pass by remember(savedPass) { mutableStateOf(savedPass) }
    var busy by remember { mutableStateOf(false) }
    val configured = savedUrl.isNotBlank()

    Text(stringResource(R.string.self_hosted_backup_webdav_nextcloud), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
    Text(
        if (configured) "On — backups also upload to your server on schedule; the last few are kept. Restore pulls the newest and merges it, skipping duplicates already here."
        else "Mirror backups to a folder on your own server. Works with Nextcloud, ownCloud, or any WebDAV share — an app-password is recommended.",
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.server_folder_url)) },
        placeholder = { Text("https://cloud.example.com/remote.php/dav/files/me/Cairn/") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            enabled = !busy && url.isNotBlank(),
            onClick = {
                busy = true
                viewModel.testWebDav(url, user, pass) { ok ->
                    busy = false
                    if (ok) {
                        viewModel.setWebDav(url, user, pass)
                        Toast.makeText(context, "Connected — server saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Couldn't reach that server — check the URL and credentials", Toast.LENGTH_LONG).show()
                    }
                }
            },
        ) {
            Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.height(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.test_save))
        }
        if (configured) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    viewModel.backupToWebDavNow { msg -> busy = false; Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                },
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.back_up_now))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    viewModel.restoreFromWebDav { msg -> busy = false; Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                },
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.restore))
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    viewModel.setWebDav("", "", "")
                    url = ""; user = ""; pass = ""
                    Toast.makeText(context, "Self-hosted backup turned off", Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.off)) }
        }
    }
}

/**
 * Storage dashboard: shows exactly where on-disk space goes (offline article bodies, cached images,
 * imported PDFs, the database, the image cache, and orphaned leftovers) and offers a one-tap
 * Optimize that deletes orphans, clears the image cache, and compacts the database. This answers the
 * "0 articles readable offline but N MB used" confusion by naming every consumer of space.
 */
@Composable
internal fun StorageSection(viewModel: SettingsViewModel) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var data by remember { mutableStateOf<com.cairn.reader.data.blob.StorageManager.Breakdown?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) { data = runCatching { viewModel.storageBreakdown() }.getOrNull() }

    fun fmt(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

    val d = data
    if (d == null) {
        Text(stringResource(R.string.calculating), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        return
    }

    @Composable
    fun Line(label: String, sub: String?, bytes: Long) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Text(fmt(bytes), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }

    Text(
        "${fmt(d.total)} used on this device",
        style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Line("Offline article copies", if (d.articleCount > 0) "${d.articleCount} files" else "none", d.articleBytes)
    Line("Cached images", if (d.imageCount > 0) "${d.imageCount} files" else "none", d.imageBytes)
    Line("Imported PDFs", if (d.pdfCount > 0) "${d.pdfCount} files" else "none", d.pdfBytes)
    Line("Database", "feeds, items, tags, highlights", d.databaseBytes)
    Line("Image cache", "thumbnails; safe to clear", d.imageCacheBytes)
    if (d.orphanCount > 0) {
        Line("Leftover / orphaned files", "${d.orphanCount} files with no article — reclaimable", d.orphanBytes)
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        enabled = !busy,
        onClick = {
            busy = true
            viewModel.optimizeStorage { r ->
                busy = false
                refresh++
                android.widget.Toast.makeText(
                    context,
                    if (r.bytesFreed > 0) "Freed ${fmt(r.bytesFreed)} (${r.filesDeleted} files)" else "Already optimized — nothing to reclaim",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        },
    ) {
        Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.height(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (busy) "Optimizing…" else "Optimize storage")
    }
    Text(
        if (d.reclaimable > 0) "Reclaims about ${fmt(d.reclaimable)}: deletes orphaned files, clears the image cache, and compacts the database."
        else "Deletes orphaned files, clears the image cache, and compacts the database.",
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
internal fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.semantics { heading() },
    )
}

/** Best-effort human-readable name for a picked document (falls back to the last path segment). */
internal fun displayNameFor(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    } ?: uri.lastPathSegment
}.getOrNull()
