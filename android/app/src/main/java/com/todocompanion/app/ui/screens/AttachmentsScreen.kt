package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.ui.AppViewModel

private enum class AttSort(val label: String) { DATE("Date added"), NAME("Name"), SIZE("Size"), TYPE("Type") }
private enum class AttType(val label: String) { ALL("All"), IMAGE("Images"), PDF("PDFs"), DOC("Docs") }

/** A hub listing every attachment across all tasks — sort, filter by type, optionally include
 *  files on completed tasks, open one in a viewer, jump to its task, or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val all by vm.allAttachments.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val titleById = tasks.associate { it.id to it.title }
    // A task counts as "done" (hidden by default) if completed, abandoned, or trashed.
    val doneIds = tasks.filter { it.completed || it.abandoned || it.trashed }.map { it.id }.toSet()

    var sort by remember { mutableStateOf(AttSort.DATE) }
    var type by remember { mutableStateOf(AttType.ALL) }
    var includeDone by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    val items = all
        .filter { includeDone || it.taskId !in doneIds }
        .filter { a ->
            when (type) {
                AttType.ALL -> true
                AttType.IMAGE -> a.isImage || a.mime.startsWith("image/")
                AttType.PDF -> a.mime == "application/pdf"
                AttType.DOC -> !(a.isImage || a.mime.startsWith("image/")) && a.mime != "application/pdf"
            }
        }
        .let { list ->
            when (sort) {
                AttSort.DATE -> list.sortedByDescending { it.addedAt }
                AttSort.NAME -> list.sortedBy { it.fileName.lowercase() }
                AttSort.SIZE -> list.sortedByDescending { it.sizeBytes }
                AttSort.TYPE -> list.sortedBy { it.mime }
            }
        }
    val totalBytes = items.sumOf { it.sizeBytes }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Attachments") },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            Text("SORT BY", Modifier.padding(14.dp, 8.dp, 14.dp, 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AttSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    trailingIcon = { if (s == sort) Icon(Icons.Filled.Sort, null, modifier = Modifier.size(16.dp)) },
                                    onClick = { sort = s; sortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Type + completed filter chips.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                }
                FilterChip(selected = includeDone, onClick = { includeDone = !includeDone }, label = { Text("Include completed") })
            }
            Text("${items.size} files · ${humanSize(totalBytes)} · stored offline in your backup",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No attachments" + if (type != AttType.ALL || !includeDone) " match these filters" else " yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            IconButton(onClick = { vm.openAttachment(a.id, a.fileName, a.mime) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open") }
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
