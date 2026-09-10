package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.todocompanion.app.ui.components.EmojiGridPicker
import com.todocompanion.app.ui.components.EmptyState
import com.todocompanion.app.ui.components.MarkdownText
import kotlinx.coroutines.delay

/** Strip the most common Markdown marks so a card preview reads as plain prose. */
private fun plainPreview(md: String): String =
    md.lineSequence()
        .map { it.trim().trimStart('#', '>', '-', '*', '+', ' ', '`').trim() }
        .filter { it.isNotBlank() }
        .joinToString("  ")
        .replace(Regex("[*_`~]"), "")
        .take(160)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Notes home — grid or list, container-filtered, pinned first.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    vm: AppViewModel,
    onOpenNote: (String) -> Unit,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    searchOpen: Boolean = false,
) {
    val settings by vm.settings.collectAsState()
    val notes by vm.notes.collectAsState()
    val notebooks by vm.notebooks.collectAsState()
    val folders by vm.folders.collectAsState()

    val useNotebooks = settings.notesNotebookMode == "notebooks"
    val grid = settings.noteDefaultView != "list"

    var container by remember { mutableStateOf<String?>(null) }   // selected notebookId/folderId, null = All

    // Containers to offer as filter chips, per the user's chosen grouping mode.
    val containers: List<Pair<String, String>> =
        if (useNotebooks) notebooks.sortedBy { it.sortOrder }.map { it.id to (it.icon?.let { e -> "$e " } ?: "") + it.name }
        else folders.sortedBy { it.sortOrder }.map { it.id to (it.icon?.let { e -> "$e " } ?: "") + it.name }

    val filtered = notes
        .asSequence()
        .filter { n ->
            container == null || (if (useNotebooks) n.notebookId == container else n.folderId == container)
        }
        .filter { n ->
            query.isBlank() || n.title.contains(query, true) || n.body.contains(query, true)
        }
        .sortedWith(compareByDescending<NoteEntity> { it.pinned }.thenByDescending { it.updatedAt })
        .toList()

    // The app's shared top bar owns the title ("Notes"), the grid/list toggle and the search button
    // (wired in AppRoot) — so this screen renders content only, matching every other module's tab.
    Column(Modifier.fillMaxSize()) {
        if (searchOpen) {
            AppTextField(
                value = query, onValueChange = onQueryChange,
                placeholder = { Text("Search notes") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        run {
            if (containers.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = container == null, onClick = { container = null }, label = { Text("All") })
                    }
                    items(containers, key = { it.first }) { (id, name) ->
                        FilterChip(selected = container == id, onClick = { container = id }, label = { Text(name) })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (filtered.isEmpty()) {
                EmptyState(
                    emoji = "📝",
                    title = if (query.isNotBlank()) "No matching notes" else "No notes yet",
                    body = if (query.isNotBlank()) "Try a different search."
                    else "Tap + to write your first note. Notes support Markdown and can link to tasks and days.",
                )
            } else if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(168.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { n -> NoteCard(n, onOpen = { onOpenNote(n.id) }, onTogglePin = { vm.saveNote(n.copy(pinned = !n.pinned)) }) }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { n -> NoteCard(n, onOpen = { onOpenNote(n.id) }, onTogglePin = { vm.saveNote(n.copy(pinned = !n.pinned)) }) }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(n: NoteEntity, onOpen: () -> Unit, onTogglePin: () -> Unit) {
    val accent = n.colorArgb?.let { Color(it) }
    AppCard(onClick = onOpen, padding = 0.dp) {
        Row(Modifier.fillMaxWidth()) {
            if (accent != null) Box(Modifier.width(4.dp).height(if (n.body.isBlank()) 56.dp else 96.dp).background(accent))
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!n.coverEmoji.isNullOrBlank()) {
                        Text(n.coverEmoji!!, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        n.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (n.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (n.pinned) "Unpin" else "Pin",
                            modifier = Modifier.size(16.dp),
                            tint = if (n.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val preview = plainPreview(n.body)
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

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Note editor — Markdown body (viewer-until-edit), notebook/folder, colour, cover emoji, tags, pin.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    vm: AppViewModel,
    noteId: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings by vm.settings.collectAsState()
    val notes by vm.notes.collectAsState()
    val notebooks by vm.notebooks.collectAsState()
    val folders by vm.folders.collectAsState()
    val tags by vm.tags.collectAsState()
    val noteTagRefs by vm.noteTagRefs.collectAsState()

    val useNotebooks = settings.notesNotebookMode == "notebooks"
    val note = notes.firstOrNull { it.id == noteId }

    var draft by remember(noteId) { mutableStateOf<NoteEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(note?.id) { if (draft == null && note != null) draft = note }
    val d = draft
    if (d == null) { Box(Modifier.fillMaxSize()) {}; return }

    var preview by remember(noteId) { mutableStateOf(d.body.isNotBlank()) }
    var showEmoji by remember { mutableStateOf(false) }
    var showContainer by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun persist(n: NoteEntity) { draft = n; vm.saveNote(n) }
    // Debounced autosave for free-typing (title/body) so we don't hit the DB/FTS every keystroke.
    androidx.compose.runtime.LaunchedEffect(d.title, d.body) {
        delay(600)
        draft?.let { vm.saveNote(it) }
    }
    BackHandler { draft?.let { vm.saveNote(it) }; onBack() }

    val myTagIds = noteTagRefs.filter { it.noteId == noteId }.map { it.tagId }.toSet()
    val containerName = if (useNotebooks) notebooks.firstOrNull { it.id == d.notebookId }?.name
    else folders.firstOrNull { it.id == d.folderId }?.name

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { draft?.let { vm.saveNote(it) }; onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Note") },
                actions = {
                    IconButton(onClick = { persist(d.copy(pinned = !d.pinned)) }) {
                        Icon(
                            if (d.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (d.pinned) "Unpin" else "Pin",
                            tint = if (d.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Delete, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val boxCount = com.todocompanion.app.domain.NoteLinks.uncheckedCheckboxes(d.body).size
                            if (boxCount > 0) {
                                DropdownMenuItem(
                                    text = { Text("Extract $boxCount checkbox${if (boxCount == 1) "" else "es"} as tasks") },
                                    onClick = {
                                        menu = false
                                        vm.extractNoteCheckboxes(noteId) { n ->
                                            android.widget.Toast.makeText(ctx, "Added $n task${if (n == 1) "" else "s"} to Inbox", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("Move to Trash") }, onClick = { menu = false; vm.trashNote(noteId); onBack() })
                            DropdownMenuItem(text = { Text("Delete permanently") }, onClick = { menu = false; showDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            // Title
            AppTextField(
                value = d.title, onValueChange = { draft = d.copy(title = it) },
                placeholder = { Text("Title") }, singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            // Meta row: cover emoji · colour · notebook/folder
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { showEmoji = true },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(d.coverEmoji?.ifBlank { "🙂" } ?: "🙂", style = MaterialTheme.typography.titleMedium)
                    }
                }
                AppColorPicker(current = d.colorArgb, onPick = { persist(d.copy(colorArgb = it)) }, allowNone = true)
                FilterChip(
                    selected = containerName != null,
                    onClick = { showContainer = true },
                    label = { Text(containerName ?: if (useNotebooks) "Notebook" else "Folder") },
                )
            }
            // Woven context (Phase 2): what this note is bound to — the day, a meeting, or a task.
            val dayLabel = if (d.kind == "journal" && d.dayEpoch != null) runCatching {
                java.time.LocalDate.ofEpochDay(d.dayEpoch!!).format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
            }.getOrNull() else null
            if (dayLabel != null || d.linkedEventId != null || d.linkedTaskId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (dayLabel != null) FilterChip(selected = true, onClick = {}, label = { Text("🗓  $dayLabel") })
                    if (d.linkedEventId != null) FilterChip(selected = true, onClick = {}, label = { Text("📅  Meeting note") })
                    if (d.linkedTaskId != null) FilterChip(selected = true, onClick = { onOpenTask(d.linkedTaskId!!) }, label = { Text("🔗  Linked task") })
                }
            }
            // Body — viewer until edit
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (d.body.isNotBlank() && preview) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        MarkdownText(d.body, modifier = Modifier.fillMaxWidth().padding(end = 36.dp))
                    }
                } else {
                    AppTextField(
                        value = d.body, onValueChange = { draft = d.copy(body = it) },
                        placeholder = { Text("Write in Markdown…") },
                        modifier = Modifier.fillMaxWidth().padding(end = 36.dp),
                    )
                }
                if (d.body.isNotBlank()) {
                    IconButton(onClick = { preview = !preview }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                        if (preview) Icon(Icons.Outlined.Edit, "Edit", modifier = Modifier.size(18.dp))
                        else Icon(Icons.Outlined.Visibility, "Preview", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            // Phase 3 — [[wiki-links]] out (tap to open, or create if new) and backlinks in ("Linked from").
            // Title-based like Obsidian; backlinks computed on the fly from other notes' bodies.
            val outTitles = com.todocompanion.app.domain.NoteLinks.outgoingTitles(d.body)
            val existingTitles = remember(notes) { notes.filter { !it.trashed }.map { it.title.trim().lowercase() }.toHashSet() }
            val backlinks = notes.filter { it.id != noteId && !it.trashed && d.title.isNotBlank() && com.todocompanion.app.domain.NoteLinks.links(it.body, d.title) }
            if (outTitles.isNotEmpty()) {
                Text("Links", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(outTitles, key = { it }) { t ->
                        val exists = t.trim().lowercase() in existingTitles
                        FilterChip(
                            selected = false,
                            onClick = { draft?.let { vm.saveNote(it) }; vm.openOrCreateNoteByTitle(t) { id -> onOpenNote(id) } },
                            label = { Text((if (exists) "🔗  " else "＋  ") + t) },
                        )
                    }
                }
            }
            if (backlinks.isNotEmpty()) {
                Text("Linked from", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(backlinks, key = { it.id }) { b ->
                        FilterChip(selected = false, onClick = { onOpenNote(b.id) }, label = { Text(b.title.ifBlank { "Untitled" }) })
                    }
                }
            }
            // Tags
            if (tags.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(tags, key = { it.id }) { t ->
                        val on = t.id in myTagIds
                        FilterChip(
                            selected = on,
                            onClick = {
                                val next = if (on) myTagIds - t.id else myTagIds + t.id
                                vm.setNoteTags(noteId, next.toList())
                            },
                            label = { Text("#" + t.name) },
                        )
                    }
                }
            }
        }
    }

    if (showEmoji) {
        AlertDialog(
            onDismissRequest = { showEmoji = false },
            confirmButton = { TextButton(onClick = { showEmoji = false }) { Text("Done") } },
            title = { Text("Cover emoji") },
            text = { EmojiGridPicker(current = d.coverEmoji) { picked -> persist(d.copy(coverEmoji = picked)); showEmoji = false } },
        )
    }
    if (showContainer) {
        AlertDialog(
            onDismissRequest = { showContainer = false },
            confirmButton = { TextButton(onClick = { showContainer = false }) { Text("Done") } },
            title = { Text(if (useNotebooks) "Notebook" else "Folder") },
            text = {
                LazyColumn {
                    item {
                        DropdownRow("None", selected = (if (useNotebooks) d.notebookId else d.folderId) == null) {
                            persist(if (useNotebooks) d.copy(notebookId = null) else d.copy(folderId = null)); showContainer = false
                        }
                    }
                    if (useNotebooks) items(notebooks, key = { it.id }) { nb ->
                        DropdownRow((nb.icon?.let { "$it " } ?: "") + nb.name, selected = d.notebookId == nb.id) {
                            persist(d.copy(notebookId = nb.id)); showContainer = false
                        }
                    } else items(folders, key = { it.id }) { f ->
                        DropdownRow((f.icon?.let { "$it " } ?: "") + f.name, selected = d.folderId == f.id) {
                            persist(d.copy(folderId = f.id)); showContainer = false
                        }
                    }
                }
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            confirmButton = { TextButton(onClick = { showDelete = false; vm.deleteNote(noteId); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
            title = { Text("Delete note?") },
            text = { Text("This permanently removes the note and its attachments. This can't be undone.") },
        )
    }
}

@Composable
private fun DropdownRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
