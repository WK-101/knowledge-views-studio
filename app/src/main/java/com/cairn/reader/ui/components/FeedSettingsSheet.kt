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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
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
import androidx.compose.ui.text.input.KeyboardType
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
    onPodcast: (Boolean) -> Unit = {},
    onFeedUrl: (String) -> Unit = {},
    onOpenIn: (String) -> Unit = {},
    onMaxItems: (Int?) -> Unit = {},
) {
    var title by remember(source.id) { mutableStateOf(source.title) }
    var folder by remember(source.id) { mutableStateOf(source.folder.orEmpty()) }
    var feedUrl by remember(source.id) { mutableStateOf(source.feedUrl) }
    var fullText by remember(source.id) { mutableStateOf(source.fullTextByDefault) }
    var notify by remember(source.id) { mutableStateOf(source.notify) }
    var podcast by remember(source.id) { mutableStateOf(source.isPodcast) }
    var openIn by remember(source.id) { mutableStateOf(source.openIn) }
    var maxItems by remember(source.id) { mutableStateOf(source.maxItems) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Name", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; onRename(it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // ---- Feed link (editable) -------------------------------------------------------
            Text("Feed link", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("The RSS/Atom URL this feed pulls from. Change it to repoint the feed.") },
                modifier = Modifier.fillMaxWidth(),
            )
            val linkChanged = feedUrl.trim().isNotBlank() && feedUrl.trim() != source.feedUrl
            if (linkChanged) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { feedUrl = source.feedUrl }) { Text("Reset") }
                    TextButton(onClick = { onFeedUrl(feedUrl.trim()) }) { Text("Update link") }
                }
            }
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
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This is a podcast", style = MaterialTheme.typography.bodyLarge)
                    Text("Treat new items as audio episodes (shown under Podcasts)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = podcast, onCheckedChange = { podcast = it; onPodcast(it) })
            }

            Spacer(Modifier.height(16.dp))
            Text("Keep at most", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data class KeepOpt(val value: Int?, val label: String)
                listOf(
                    KeepOpt(null, "Default"),
                    KeepOpt(0, "All"),
                    KeepOpt(25, "25"),
                    KeepOpt(50, "50"),
                    KeepOpt(100, "100"),
                    KeepOpt(200, "200"),
                    KeepOpt(500, "500"),
                ).forEach { opt ->
                    FilterChip(selected = maxItems == opt.value, onClick = { maxItems = opt.value; onMaxItems(opt.value) }, label = { Text(opt.label) })
                }
            }
            Text(
                "Older, un-engaged items beyond this are pruned on sync. \"Default\" follows the global limit in Settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            Text("Open articles in", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = openIn == "READER", onClick = { openIn = "READER"; onOpenIn("READER") }, label = { Text("Reader") })
                FilterChip(selected = openIn == "BROWSER", onClick = { openIn = "BROWSER"; onOpenIn("BROWSER") }, label = { Text("Browser") })
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
