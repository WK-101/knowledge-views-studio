package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.NoteEntity
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.AppColorPicker
import com.todocompanion.app.ui.components.AppTextField
import com.todocompanion.app.ui.components.ConfirmDialog
import com.todocompanion.app.ui.components.EmojiGridPicker
import com.todocompanion.app.ui.components.EmptyState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Notes home — the standalone view composables (trash · board · calendar · card · toolbar pill) and the
// card-preview helper, split out of NotesScreen.kt (P6-G) so each file stays a focused unit.

/** Strip the most common Markdown marks so a card preview reads as plain prose. */

internal fun plainPreview(md: String): String =
    md.lineSequence()
        .map { it.trim().trimStart('#', '>', '-', '*', '+', ' ', '`').trim() }
        .filter { it.isNotBlank() }
        .joinToString("  ")
        .replace(Regex("[*_`~]"), "")
        .take(160)

/** The Trash — trashed notes awaiting restore or permanent deletion. A banner explains auto-empty; each
 *  row opens a Restore / Delete-forever choice. */
@Composable
internal fun NotesTrashView(
    trashed: List<NoteEntity>,
    retentionDays: Int,
    onOpen: (NoteEntity) -> Unit,
    onEmpty: () -> Unit,
) {
    val df = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                if (retentionDays > 0) "Notes here are deleted after $retentionDays days." else "Notes stay here until you delete them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (trashed.isNotEmpty()) TextButton(onClick = onEmpty) { Text("Empty", color = MaterialTheme.colorScheme.error) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        if (trashed.isEmpty()) {
            EmptyState(emoji = "🗑", title = "Trash is empty", body = "Notes you move to Trash appear here, where you can restore them or delete them for good.")
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
                items(trashed, key = { it.id }) { n ->
                    Surface(onClick = { onOpen(n) }, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().animateItem()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((n.coverEmoji?.ifBlank { null }?.let { "$it " } ?: "") + n.title.ifBlank { "(untitled)" },
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Edited ${df.format(java.util.Date(n.updatedAt))}",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Wave S — Board view: a shelf per notebook/folder (mobile-friendly kanban), each a horizontal row of cards. */
@Composable
internal fun NotesBoardView(
    notes: List<NoteEntity>,
    containers: List<Pair<String, String>>,
    useNotebooks: Boolean,
    onOpen: (String) -> Unit,
    onTogglePin: (NoteEntity) -> Unit,
) {
    val byContainer = remember(notes) { notes.groupBy { if (useNotebooks) it.notebookId else it.folderId } }
    val groups = remember(byContainer, containers) {
        buildList {
            containers.forEach { (id, name) -> byContainer[id]?.let { add(name to it) } }
            byContainer[null]?.let { add((if (useNotebooks) "Unsorted" else "Unfiled") to it) }
        }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        items(groups) { (name, groupNotes) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text("${groupNotes.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(groupNotes, key = { it.id }) { n ->
                        Box(Modifier.width(210.dp)) { NoteCard(n, onOpen = { onOpen(n.id) }, onTogglePin = { onTogglePin(n) }) }
                    }
                }
            }
        }
    }
}

/** Wave S — Calendar view: notes grouped by month (a journal note's dayEpoch, else its updated date). */
@Composable
internal fun NotesCalendarView(notes: List<NoteEntity>, onOpen: (String) -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    fun ms(n: NoteEntity): Long = n.dayEpoch?.let { java.time.LocalDate.ofEpochDay(it).atStartOfDay(zone).toInstant().toEpochMilli() } ?: n.updatedAt
    val groups = remember(notes) {
        notes.sortedByDescending { ms(it) }
            .groupBy { java.time.Instant.ofEpochMilli(ms(it)).atZone(zone).let { z -> z.year * 100 + z.monthValue } }
            .toList().sortedByDescending { it.first }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
        groups.forEach { (ym, monthNotes) ->
            item(key = "m$ym") {
                val label = runCatching { java.time.YearMonth.of(ym / 100, ym % 100).format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")) }.getOrDefault("")
                Text(label, Modifier.padding(top = 10.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            items(monthNotes, key = { it.id }) { n ->
                val day = runCatching { java.time.Instant.ofEpochMilli(ms(n)).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE d")) }.getOrDefault("")
                Surface(onClick = { onOpen(n.id) }, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day, Modifier.width(54.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((n.coverEmoji?.ifBlank { null }?.let { "$it " } ?: "") + n.title.ifBlank { "(untitled)" },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteCard(
    n: NoteEntity,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val accent = n.colorArgb?.let { Color(it) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val cardColor = if (selected) MaterialTheme.colorScheme.primaryContainer
    else com.todocompanion.app.ui.components.appCardColor()
    val clickMod = modifier.combinedClickable(
        onClick = onOpen,
        onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongPress() },
    )
    AppCard(modifier = clickMod, onClick = null, padding = 0.dp, color = cardColor) {
        Row(Modifier.fillMaxWidth()) {
            if (accent != null) Box(Modifier.width(4.dp).height(if (n.body.isBlank()) 56.dp else 96.dp).background(accent))
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (selected) "Selected" else "Not selected",
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else if (!n.coverEmoji.isNullOrBlank()) {
                        Text(n.coverEmoji!!, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        n.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (n.favorite) {
                        Icon(Icons.Filled.Star, "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (!selecting) {
                        // 28dp visual, but a 48dp touch target (minimumInteractiveComponentSize) for a11y.
                        IconButton(onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onTogglePin() }, modifier = Modifier.minimumInteractiveComponentSize().size(28.dp)) {
                            Icon(
                                if (n.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (n.pinned) "Unpin" else "Pin",
                                modifier = Modifier.size(16.dp),
                                tint = if (n.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (n.pinned) {
                        Icon(Icons.Filled.PushPin, "Pinned", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                // Memoized per note (keyed on id+updatedAt) so the Markdown-stripping regex runs once per
                // edit, not on every recomposition/scroll of a visible card.
                val preview = remember(n.id, n.updatedAt) { plainPreview(n.body) }
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preview, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


/** A compact dropdown "pill" for the Notes home toolbar — an optional leading icon, a label, and a
 *  dropdown chevron. Opens its menu on tap (the caller anchors a DropdownMenu next to it). */
@Composable
internal fun ToolbarPill(label: String, leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = NotesTokens.Pill, color = cs.surfaceVariant.copy(alpha = .6f), onClick = onClick) {
        Row(Modifier.padding(start = if (leadingIcon != null) 8.dp else 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) { Icon(leadingIcon, null, Modifier.size(17.dp), tint = cs.onSurfaceVariant); Spacer(Modifier.width(5.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DropdownRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

