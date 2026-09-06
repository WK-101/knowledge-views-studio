package com.todocompanion.app.ui.screens
import com.todocompanion.app.ui.components.AppTextField
import com.todocompanion.app.ui.components.EmptyState

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.AttachmentMeta
import com.todocompanion.app.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AttSort(val label: String) { DATE("Date added"), NAME("Name"), SIZE("Size"), TYPE("Type") }
private enum class AttView { LIST, GRID_L, GRID_S }

// A richer type taxonomy than the old 4 chips — every attachment maps to exactly one bucket.
private enum class AttCat(val label: String) {
    ALL("All"), IMAGE("Images"), PDF("PDFs"), DOC("Documents"), SHEET("Sheets"),
    AUDIO("Audio"), VIDEO("Video"), ARCHIVE("Archives"), OTHER("Other"),
}
private enum class SizeBucket(val label: String) { ANY("Any size"), TINY("< 100 KB"), SMALL("100 KB – 1 MB"), MED("1 – 10 MB"), BIG("> 10 MB") }
private enum class TimeBucket(val label: String) { ANY("Any time"), TODAY("Today"), WEEK("Last 7 days"), MONTH("Last 30 days"), YEAR("This year") }

private data class AttFilter(
    val cat: AttCat = AttCat.ALL,
    val size: SizeBucket = SizeBucket.ANY,
    val time: TimeBucket = TimeBucket.ANY,
    val includeDone: Boolean = false,
    val sort: AttSort = AttSort.DATE,
    val asc: Boolean = false,
) {
    val active: Boolean get() = cat != AttCat.ALL || size != SizeBucket.ANY || time != TimeBucket.ANY || includeDone
}

private fun categoryOf(mime: String, isImage: Boolean): AttCat = when {
    isImage || mime.startsWith("image/") -> AttCat.IMAGE
    mime == "application/pdf" -> AttCat.PDF
    mime.startsWith("audio/") -> AttCat.AUDIO
    mime.startsWith("video/") -> AttCat.VIDEO
    "zip" in mime || "compress" in mime || "tar" in mime || "rar" in mime || "7z" in mime || "gzip" in mime -> AttCat.ARCHIVE
    "sheet" in mime || "excel" in mime || "csv" in mime -> AttCat.SHEET
    mime.startsWith("text/") || "word" in mime || "document" in mime || "rtf" in mime ||
        mime == "application/json" || "presentation" in mime -> AttCat.DOC
    else -> AttCat.OTHER
}

/** A hub listing every attachment across all tasks — extensive filters (type, size, age, completed),
 *  sort with direction, live search, and three views: a detailed list plus small and large grids with
 *  real image thumbnails. Open one in a viewer, jump to its task, or delete it. All offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsScreen(vm: AppViewModel, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val all by vm.allAttachments.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val titleById = remember(tasks) { tasks.associate { it.id to it.title } }
    val doneIds = remember(tasks) { tasks.filter { it.completed || it.abandoned || it.trashed }.map { it.id }.toSet() }

    var filter by remember { mutableStateOf(AttFilter()) }
    var view by remember { mutableStateOf(AttView.LIST) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AttachmentMeta?>(null) }   // confirm before a permanent delete
    val now = remember { System.currentTimeMillis() }

    val items = remember(all, filter, query, doneIds, now) {
        all.asSequence()
            .filter { filter.includeDone || it.taskId !in doneIds }
            .filter { filter.cat == AttCat.ALL || categoryOf(it.mime, it.isImage) == filter.cat }
            .filter { matchesSize(it.sizeBytes, filter.size) }
            .filter { matchesTime(it.addedAt, filter.time, now) }
            .filter { query.isBlank() || it.fileName.contains(query.trim(), ignoreCase = true) ||
                (titleById[it.taskId]?.contains(query.trim(), ignoreCase = true) == true) }
            .toList()
            .let { list ->
                val base = when (filter.sort) {
                    AttSort.DATE -> list.sortedBy { it.addedAt }
                    AttSort.NAME -> list.sortedBy { it.fileName.lowercase() }
                    AttSort.SIZE -> list.sortedBy { it.sizeBytes }
                    AttSort.TYPE -> list.sortedBy { it.mime }
                }
                if (filter.asc) base else base.reversed()
            }
    }
    val totalBytes = items.sumOf { it.sizeBytes }

    Scaffold(
        topBar = {
            TopAppBar(expandedHeight = 52.dp,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Attachments") },
                actions = {
                    IconButton(onClick = { view = when (view) { AttView.LIST -> AttView.GRID_L; AttView.GRID_L -> AttView.GRID_S; AttView.GRID_S -> AttView.LIST } }) {
                        Icon(
                            when (view) { AttView.LIST -> Icons.Filled.GridView; AttView.GRID_L -> Icons.Filled.Apps; AttView.GRID_S -> Icons.AutoMirrored.Filled.ViewList },
                            "Change view",
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            Text("SORT BY", Modifier.padding(14.dp, 8.dp, 14.dp, 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AttSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    trailingIcon = { if (s == filter.sort) Icon(Icons.Filled.Sort, null, modifier = Modifier.size(16.dp)) },
                                    onClick = { filter = filter.copy(sort = s); sortMenu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (filter.asc) "Ascending ↑" else "Descending ↓") },
                                onClick = { filter = filter.copy(asc = !filter.asc); sortMenu = false },
                            )
                        }
                    }
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(Icons.Filled.FilterList, "Filters", tint = if (filter.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Live search.
            AppTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search files or tasks") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
            )
            // Fast type-switch chips (the full filter set lives in the Filters sheet).
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttCat.entries.forEach { c ->
                    FilterChip(selected = filter.cat == c, onClick = { filter = filter.copy(cat = c) }, label = { Text(c.label) })
                }
            }
            Text("${items.size} files · ${humanSize(totalBytes)} · stored offline in your backup",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (filter.active || query.isNotBlank()) {
                        Text("No attachments match these filters", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        EmptyState(
                            emoji = "📎",
                            title = "No attachments yet",
                            body = "Files, photos and PDFs you attach to a task collect here — everything stays offline in your backup.",
                        )
                    }
                }
            } else when (view) {
                AttView.LIST -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(items, key = { it.id }) { a ->
                        AttachmentRow(vm, a, titleById[a.taskId], onOpenTask, onDelete = { pendingDelete = a })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    }
                }
                AttView.GRID_L, AttView.GRID_S -> {
                    val min = if (view == AttView.GRID_L) 116.dp else 84.dp
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(min),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        gridItems(items, key = { it.id }) { a ->
                            AttachmentTile(vm, a, titleById[a.taskId], large = view == AttView.GRID_L, onOpenTask, onDelete = { pendingDelete = a })
                        }
                    }
                }
            }
        }
    }

    if (filterOpen) AttachmentFilterSheet(filter, all, doneIds, now, onApply = { filter = it }, onDismiss = { filterOpen = false })

    pendingDelete?.let { a ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete attachment?") },
            text = { Text("“${a.fileName}” will be permanently removed from this task and your backups. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.removeAttachment(a.id); pendingDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AttachmentRow(vm: AppViewModel, a: AttachmentMeta, taskTitle: String?, onOpenTask: (String) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { vm.openAttachment(a.id, a.fileName, a.mime) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            val thumb = rememberThumb(vm, a)
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else {
                val (icon, tint) = glyphFor(a.mime, a.isImage)
                Box(Modifier.fillMaxSize().background(tint.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(humanSize(a.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· in ${taskTitle ?: "task"}",
                    Modifier.clickable { onOpenTask(a.taskId) },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { vm.openAttachment(a.id, a.fileName, a.mime) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AttachmentTile(vm: AppViewModel, a: AttachmentMeta, taskTitle: String?, large: Boolean, onOpenTask: (String) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clickable { vm.openAttachment(a.id, a.fileName, a.mime) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            val thumb = rememberThumb(vm, a)
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else {
                val (icon, tint) = glyphFor(a.mime, a.isImage)
                Box(Modifier.fillMaxSize().background(tint.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(if (large) 34.dp else 26.dp))
                }
            }
            // Corner overflow: open / go-to-task / delete.
            Box(Modifier.align(Alignment.TopEnd)) {
                Surface(shape = RoundedCornerShape(bottomStart = 10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.MoreVert, "More", modifier = Modifier.size(18.dp)) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openAttachment(a.id, a.fileName, a.mime) })
                    DropdownMenuItem(text = { Text("Go to task") }, onClick = { menu = false; onOpenTask(a.taskId) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Text(a.fileName, maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        if (large) Text("${humanSize(a.sizeBytes)} · ${taskTitle ?: "task"}", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(humanSize(a.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Loads a small image thumbnail off the main thread. Only for images under ~8 MB — bigger ones (and
 *  non-images) fall back to a coloured glyph, so a grid of many files never blows up memory. */
@Composable
private fun rememberThumb(vm: AppViewModel, a: AttachmentMeta): ImageBitmap? {
    if (!(a.isImage || a.mime.startsWith("image/")) || a.sizeBytes > 8L * 1024 * 1024) return null
    val bmp by produceState<ImageBitmap?>(null, a.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val b64 = vm.attachmentContent(a.id) ?: return@runCatching null
                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                val probe = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
                var sample = 1
                val target = 256
                while (probe.outWidth / (sample * 2) >= target && probe.outHeight / (sample * 2) >= target) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentFilterSheet(current: AttFilter, all: List<AttachmentMeta>, doneIds: Set<String>, now: Long, onApply: (AttFilter) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var f by remember { mutableStateOf(current) }
    // Live count of what the current draft would show, so the user sees the effect before applying.
    val count = remember(f, all) {
        all.count { a ->
            (f.includeDone || a.taskId !in doneIds) &&
                (f.cat == AttCat.ALL || categoryOf(a.mime, a.isImage) == f.cat) &&
                matchesSize(a.sizeBytes, f.size) && matchesTime(a.addedAt, f.time, now)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text("Filter attachments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FilterLabel("Type")
            ChipFlow { AttCat.entries.forEach { c -> FilterChip(selected = f.cat == c, onClick = { f = f.copy(cat = c) }, label = { Text(c.label) }) } }
            FilterLabel("Size")
            ChipFlow { SizeBucket.entries.forEach { s -> FilterChip(selected = f.size == s, onClick = { f = f.copy(size = s) }, label = { Text(s.label) }) } }
            FilterLabel("Added")
            ChipFlow { TimeBucket.entries.forEach { t -> FilterChip(selected = f.time == t, onClick = { f = f.copy(time = t) }, label = { Text(t.label) }) } }
            FilterLabel("Sort")
            ChipFlow {
                AttSort.entries.forEach { s -> FilterChip(selected = f.sort == s, onClick = { f = f.copy(sort = s) }, label = { Text(s.label) }) }
                FilterChip(selected = true, onClick = { f = f.copy(asc = !f.asc) }, label = { Text(if (f.asc) "Ascending ↑" else "Descending ↓") })
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Include completed / trashed tasks", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(checked = f.includeDone, onCheckedChange = { f = f.copy(includeDone = it) })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { f = AttFilter(sort = f.sort, asc = f.asc) }) { Text("Clear all") }
                Text("$count match", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onApply(f); onDismiss() }) { Text("Apply", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun FilterLabel(text: String) = Text(text, style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }

private fun matchesSize(bytes: Long, b: SizeBucket): Boolean = when (b) {
    SizeBucket.ANY -> true
    SizeBucket.TINY -> bytes < 100_000
    SizeBucket.SMALL -> bytes in 100_000 until 1_000_000
    SizeBucket.MED -> bytes in 1_000_000 until 10_000_000
    SizeBucket.BIG -> bytes >= 10_000_000
}

private fun matchesTime(addedAt: Long, b: TimeBucket, now: Long): Boolean {
    val day = 86_400_000L
    return when (b) {
        TimeBucket.ANY -> true
        TimeBucket.TODAY -> now - addedAt < day
        TimeBucket.WEEK -> now - addedAt < 7 * day
        TimeBucket.MONTH -> now - addedAt < 30 * day
        TimeBucket.YEAR -> now - addedAt < 365 * day
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun glyphFor(mime: String, isImage: Boolean): Pair<ImageVector, Color> = when (categoryOf(mime, isImage)) {
    AttCat.IMAGE -> Icons.Filled.Image to Color(0xFF12A594)
    AttCat.PDF -> Icons.Filled.PictureAsPdf to Color(0xFFE5484D)
    AttCat.SHEET -> Icons.Filled.TableChart to Color(0xFF0EA371)
    AttCat.AUDIO -> Icons.Filled.AudioFile to Color(0xFFB569F5)
    AttCat.VIDEO -> Icons.Filled.VideoFile to Color(0xFFF76B15)
    AttCat.ARCHIVE -> Icons.Filled.FolderZip to Color(0xFFF5A623)
    else -> Icons.Filled.Description to Color(0xFF3E7BFA)
}
