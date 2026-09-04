@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.cairn.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cairn.reader.data.db.SourceEntity

/** Per-feed settings: which folder it lives in, whether to fetch full text on sync,
 *  notifications, and removing the feed. */
@Composable
fun FeedSettingsSheet(
    source: SourceEntity,
    folders: List<String>,
    onFolder: (String?) -> Unit,
    onFullText: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit = {},
    onOpenSite: (() -> Unit)? = null,
) {
    var title by remember(source.id) { mutableStateOf(source.title) }
    var folder by remember(source.id) { mutableStateOf(source.folder.orEmpty()) }
    var fullText by remember(source.id) { mutableStateOf(source.fullTextByDefault) }
    var notify by remember(source.id) { mutableStateOf(source.notify) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(source.feedUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))

            Text("Name", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; onRename(it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            Text("Folder", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it; onFolder(it) },
                singleLine = true,
                placeholder = { Text("No folder") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    folders.forEach { f ->
                        FilterChip(selected = folder.equals(f, ignoreCase = true), onClick = { folder = f; onFolder(f) }, label = { Text(f) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Full text on sync", style = MaterialTheme.typography.bodyLarge)
                    Text("Fetch the whole article when new items arrive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = fullText, onCheckedChange = { fullText = it; onFullText(it) })
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notifications", style = MaterialTheme.typography.bodyLarge)
                    Text("Notify when this feed has new articles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = notify, onCheckedChange = { notify = it; onNotify(it) })
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onRemove(); onDismiss() }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.height(18.dp))
                    Text("  Remove")
                }
                if (onOpenSite != null && !source.siteUrl.isNullOrBlank()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onOpenSite(); onDismiss() }) { Text("Open site") }
                }
            }
        }
    }
}
