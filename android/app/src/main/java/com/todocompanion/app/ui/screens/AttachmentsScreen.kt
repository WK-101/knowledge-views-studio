package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel

/** A hub listing every attachment across all tasks — open one in a viewer, jump to its task, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val items by vm.allAttachments.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val titleById = tasks.associate { it.id to it.title }
    val totalBytes = items.sumOf { it.sizeBytes }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Attachments") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("${items.size} files · ${humanSize(totalBytes)} total · stored offline in your backup",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No attachments yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(items, key = { it.id }) { a ->
                        val (icon, tint) = glyphFor(a.mime, a.isImage)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(tint.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(humanSize(a.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("· in ${titleById[a.taskId] ?: "task"}",
                                        Modifier.clickable { onOpenTask(a.taskId) },
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            IconButton(onClick = { vm.openAttachment(a.id, a.fileName, a.mime) }) { Icon(Icons.Filled.OpenInNew, "Open") }
                            IconButton(onClick = { vm.removeAttachment(a.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                        }
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    }
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun glyphFor(mime: String, isImage: Boolean): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> = when {
    isImage || mime.startsWith("image/") -> Icons.Filled.Image to Color(0xFF12A594)
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf to Color(0xFFE5484D)
    "sheet" in mime || "excel" in mime || "csv" in mime -> Icons.Filled.TableChart to Color(0xFF0EA371)
    else -> Icons.Filled.Description to Color(0xFF3E7BFA)
}
