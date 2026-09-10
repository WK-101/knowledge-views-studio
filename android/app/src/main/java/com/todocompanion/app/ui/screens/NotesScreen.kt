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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
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
import com.todocompanion.app.ui.components.ConfirmDialog
import com.todocompanion.app.ui.components.EmojiGridPicker
import com.todocompanion.app.ui.components.EmptyState
import com.todocompanion.app.ui.components.MarkdownText
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val smartViews by vm.smartViews.collectAsState()
    val noteTagRefs by vm.noteTagRefs.collectAsState()

    val useNotebooks = settings.notesNotebookMode == "notebooks"
    val grid = settings.noteDefaultView != "list"

    var container by remember { mutableStateOf<String?>(null) }   // selected notebookId/folderId, null = All
    var archiveView by remember { mutableStateOf(false) }         // Wave B — the Archive (third state) view
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.purgeExpiredNoteTrash() }  // lazy auto-empty-trash sweep
    // Wave D — Smart Views: the active predicate filter (null = none), its chip id/label, and dialog state.
    var activePredicate by remember { mutableStateOf<com.todocompanion.app.domain.NotePredicate?>(null) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var showBuilder by remember { mutableStateOf(false) }
    var deleteView by remember { mutableStateOf<com.todocompanion.app.data.entity.SmartViewEntity?>(null) }
    var showGraph by remember { mutableStateOf(false) }

    // Containers to offer as filter chips, per the user's chosen grouping mode.
    val containers: List<Pair<String, String>> =
        if (useNotebooks) notebooks.sortedBy { it.sortOrder }.map { it.id to (it.icon?.let { e -> "$e " } ?: "") + it.name }
        else folders.sortedBy { it.sortOrder }.map { it.id to (it.icon?.let { e -> "$e " } ?: "") + it.name }

    // Wave J (M4) — engine facts for cross-module smart-view conditions (linked-task status, etc.).
    val tasks by vm.tasks.collectAsState()
    val openTaskIds = remember(tasks) { tasks.asSequence().filter { !it.completed && !it.trashed && !it.abandoned }.map { it.id }.toSet() }
    val overdueTaskIds = remember(tasks) {
        val now = System.currentTimeMillis()
        tasks.asSequence().filter { !it.completed && !it.trashed && !it.abandoned && (it.dueDate ?: Long.MAX_VALUE) < now }.map { it.id }.toSet()
    }
    val filtered = notes
        .asSequence()
        .filter { n ->
            val p = activePredicate
            if (p != null) com.todocompanion.app.domain.NoteSmartViews.matches(
                p, com.todocompanion.app.domain.NoteSmartViews.Ctx(
                    n.pinned, n.favorite, n.archived, n.trashed, n.title, n.body, n.kind, n.updatedAt,
                    noteTagRefs.filter { it.noteId == n.id }.map { it.tagId }.toSet(), System.currentTimeMillis(),
                    hasReminder = n.reminderAt != null || n.reminderExtra.isNotBlank(),
                    hasOpenItems = com.todocompanion.app.domain.NoteLinks.uncheckedCheckboxes(n.body).isNotEmpty(),
                    linkedTaskId = n.linkedTaskId, linkedEventId = n.linkedEventId,
                    openTaskIds = openTaskIds, overdueTaskIds = overdueTaskIds,
                ),
            )
            else (if (archiveView) n.archived else !n.archived) &&
                (container == null || (if (useNotebooks) n.notebookId == container else n.folderId == container)) &&
                // Wave J (M8) — a note sealed to the future is hidden from browsing until its reveal date
                // (it's still findable via search, so it can be unsealed early).
                (n.sealedUntil == null || n.sealedUntil!! <= System.currentTimeMillis())
        }
        .filter { n ->
            query.isBlank() || n.title.contains(query, true) || n.body.contains(query, true)
        }
        .sortedWith(compareByDescending<NoteEntity> { it.pinned }.thenByDescending { it.updatedAt })
        .toList()

    // Wave E — the Life Graph opens as a full-screen overlay (early return keeps it simple, no nav change).
    if (showGraph) {
        NoteGraphScreen(vm, onOpenNote = { showGraph = false; onOpenNote(it) }, onClose = { showGraph = false })
        return
    }

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
            run {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(selected = container == null && !archiveView && activePredicate == null, onClick = { container = null; archiveView = false; activePredicate = null; activeLabel = null }, label = { Text("All") })
                    }
                    items(containers, key = { it.first }) { (id, name) ->
                        FilterChip(selected = container == id && !archiveView && activePredicate == null, onClick = { container = id; archiveView = false; activePredicate = null; activeLabel = null }, label = { Text(name) })
                    }
                    item {
                        FilterChip(selected = archiveView && activePredicate == null, onClick = { archiveView = true; container = null; activePredicate = null; activeLabel = null }, label = { Text("🗄 Archived") })
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Smart Views row: system views + saved views (delete via the trailing ✕) + builder.
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sys = listOf(
                        "★ Favorites" to com.todocompanion.app.domain.NoteSmartViews.FAVORITES,
                        "📌 Pinned" to com.todocompanion.app.domain.NoteSmartViews.PINNED,
                        "🏷 Untagged" to com.todocompanion.app.domain.NoteSmartViews.UNTAGGED,
                    )
                    items(sys, key = { it.first }) { (lbl, pred) ->
                        FilterChip(
                            selected = activeLabel == lbl,
                            onClick = {
                                if (activeLabel == lbl) { activePredicate = null; activeLabel = null }
                                else { activePredicate = pred; activeLabel = lbl; container = null; archiveView = false }
                            },
                            label = { Text(lbl) },
                        )
                    }
                    items(smartViews, key = { it.id }) { v ->
                        val decoded = remember(v.predicateJson) { com.todocompanion.app.domain.NoteSmartViews.decode(v.predicateJson) }
                        FilterChip(
                            selected = activeLabel == v.id,
                            onClick = {
                                if (activeLabel == v.id) { activePredicate = null; activeLabel = null }
                                else if (decoded != null) { activePredicate = decoded; activeLabel = v.id; container = null; archiveView = false }
                            },
                            label = { Text((v.icon?.let { "$it " } ?: "🔎 ") + v.title) },
                            trailingIcon = { Icon(Icons.Filled.Delete, "Delete view", modifier = Modifier.size(16.dp).clickable { deleteView = v }) },
                        )
                    }
                    item {
                        FilterChip(selected = false, onClick = { showBuilder = true }, label = { Text("＋ Smart View") })
                    }
                    item {
                        FilterChip(selected = false, onClick = { showGraph = true }, label = { Text("◉ Graph") })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (showBuilder) SmartViewBuilderDialog(
                onSave = { t, pred -> vm.saveSmartView(null, t, null, pred); showBuilder = false },
                onDismiss = { showBuilder = false },
            )
            deleteView?.let { v ->
                ConfirmDialog(
                    title = "Delete Smart View?",
                    body = "\"${v.title}\" will be removed. Your notes are untouched.",
                    confirmLabel = "Delete",
                    onConfirm = { vm.deleteSmartView(v.id); if (activeLabel == v.id) { activePredicate = null; activeLabel = null }; deleteView = null },
                    onDismiss = { deleteView = null },
                )
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
                    items(filtered, key = { it.id }) { n -> NoteCard(n, Modifier.animateItem(), onOpen = { onOpenNote(n.id) }, onTogglePin = { vm.saveNote(n.copy(pinned = !n.pinned)) }) }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { n -> NoteCard(n, Modifier.animateItem(), onOpen = { onOpenNote(n.id) }, onTogglePin = { vm.saveNote(n.copy(pinned = !n.pinned)) }) }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(n: NoteEntity, modifier: Modifier = Modifier, onOpen: () -> Unit, onTogglePin: () -> Unit) {
    val accent = n.colorArgb?.let { Color(it) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    AppCard(modifier = modifier, onClick = onOpen, padding = 0.dp) {
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
                    if (n.favorite) {
                        Icon(Icons.Filled.Star, "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onTogglePin() }, modifier = Modifier.size(28.dp)) {
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
    val revisions by vm.observeNoteRevisions(noteId).collectAsState(initial = emptyList())
    val links by vm.observeNoteLinks(noteId).collectAsState(initial = emptyList())

    val useNotebooks = settings.notesNotebookMode == "notebooks"
    val note = notes.firstOrNull { it.id == noteId }

    var draft by remember(noteId) { mutableStateOf<NoteEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(note?.id) { if (draft == null && note != null) draft = note }
    val d = draft
    if (d == null) { Box(Modifier.fillMaxSize()) {}; return }

    // Wave O — a sealed note gets screenshot / recents-thumbnail protection while open, regardless of the
    // app-wide secure-screen setting (restored to that setting on leave).
    com.todocompanion.app.ui.components.SecureFlagWhile(active = d.sealedUntil != null, globalOn = settings.secureScreen)
    var preview by remember(noteId) { mutableStateOf(d.body.isNotBlank()) }
    var showEmoji by remember { mutableStateOf(false) }
    var showContainer by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showHistory by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSeal by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun persist(n: NoteEntity) { draft = n; vm.saveNote(n) }
    // Debounced autosave for free-typing (title/body) so we don't hit the DB/FTS every keystroke.
    androidx.compose.runtime.LaunchedEffect(d.title, d.body) {
        delay(600)
        draft?.let { vm.saveNote(it) }
    }
    // Wave P (N1) — in read mode, expand {{today:agenda}} / {{tasks:…}} / {{note:…}} against live data.
    var expandedBody by remember(noteId) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(noteId, d.body, preview, d.readonly) {
        expandedBody = if ((preview || d.readonly) && com.todocompanion.app.util.NoteTransclusion.hasTokens(d.body))
            vm.expandNoteTransclusion(d.body) else null
    }
    BackHandler { draft?.let { vm.closeNoteEditor(it) }; onBack() }

    val myTagIds = noteTagRefs.filter { it.noteId == noteId }.map { it.tagId }.toSet()
    val containerName = if (useNotebooks) notebooks.firstOrNull { it.id == d.notebookId }?.name
    else folders.firstOrNull { it.id == d.folderId }?.name

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { draft?.let { vm.closeNoteEditor(it) }; onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Note") },
                actions = {
                    IconButton(onClick = { persist(d.copy(favorite = !d.favorite)) }) {
                        Icon(
                            if (d.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (d.favorite) "Unfavorite" else "Favorite",
                            tint = if (d.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            DropdownMenuItem(text = { Text(if (d.readonly) "Allow editing" else "Make read-only") }, onClick = { val wasRo = d.readonly; menu = false; persist(d.copy(readonly = !wasRo)); if (!wasRo) preview = true })
                            DropdownMenuItem(text = { Text("Outline") }, onClick = { menu = false; showOutline = true })
                            DropdownMenuItem(text = { Text(if (d.reminderAt != null) "⏰ Reminder set — change…" else "⏰ Remind me…") }, onClick = { menu = false; showReminder = true })
                            DropdownMenuItem(text = { Text("Version history") }, onClick = { menu = false; showHistory = true })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; draft?.let { vm.closeNoteEditor(it) }; vm.duplicateNote(noteId) { id -> onOpenNote(id) } })
                            DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; vm.archiveNote(noteId); onBack() })
                            DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; showAbout = true })
                            DropdownMenuItem(text = { Text("Export…") }, onClick = { menu = false; showExport = true })
                            if (d.sealedUntil != null && d.sealedUntil!! > System.currentTimeMillis())
                                DropdownMenuItem(text = { Text("🔒 Unseal") }, onClick = { menu = false; vm.unsealNote(noteId); draft = d.copy(sealedUntil = null, reminderAt = null) })
                            else
                                DropdownMenuItem(text = { Text("🔒 Seal to the future…") }, onClick = { menu = false; showSeal = true })
                            if (d.kind == "journal" && d.dayEpoch != null) {
                                DropdownMenuItem(text = { Text("⟳ Insert today's digest") }, onClick = {
                                    menu = false
                                    scope.launch { val md = vm.dayDigestMarkdown(d.dayEpoch!!); persist(d.copy(body = md + "\n" + d.body)) }
                                })
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
                if (d.readonly) FilterChip(selected = true, onClick = { persist(d.copy(readonly = false)) }, label = { Text("🔒 Read-only") })
            }
            // Woven context (Phase 2): what this note is bound to — the day, a meeting, or a task.
            val dayLabel = if (d.kind == "journal" && d.dayEpoch != null) runCatching {
                java.time.LocalDate.ofEpochDay(d.dayEpoch!!).format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
            }.getOrNull() else null
            // Wave F — a glanceable chip for the note's own reminder (tap to change/clear).
            val reminderLabel = d.reminderAt?.let { at ->
                runCatching {
                    java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM · h:mm a"))
                }.getOrNull()
            }
            val sealedLabel = d.sealedUntil?.takeIf { it > System.currentTimeMillis() }?.let { at ->
                runCatching {
                    java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
                }.getOrNull()
            }
            if (dayLabel != null || d.linkedEventId != null || d.linkedTaskId != null || reminderLabel != null || sealedLabel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (dayLabel != null) FilterChip(selected = true, onClick = {}, label = { Text("🗓  $dayLabel") })
                    if (d.linkedEventId != null) FilterChip(selected = true, onClick = {}, label = { Text("📅  Meeting note") })
                    if (d.linkedTaskId != null) FilterChip(selected = true, onClick = { onOpenTask(d.linkedTaskId!!) }, label = { Text("🔗  Linked task") })
                    if (reminderLabel != null) FilterChip(selected = true, onClick = { showReminder = true }, label = { Text("⏰  $reminderLabel" + if (d.reminderRrule != null) "  ↻" else "") })
                    if (sealedLabel != null) FilterChip(selected = true, onClick = {}, label = { Text("🔒  Sealed until $sealedLabel") })
                }
            }
            // Body — viewer until edit. Preview checkboxes are tappable and round-trip to the Markdown
            // source; editing goes through NoteBodyEditor (toolbar · smart lists · undo/redo).
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (d.body.isNotBlank() && (preview || d.readonly)) {
                    // Wave L — notes with math ($…$ / $$…$$), Mermaid diagrams, or inline images render in
                    // the offline rich WebView (KaTeX/Mermaid/Prism, bundled). Plain notes keep the native
                    // renderer, which has tappable checkboxes and [[wiki-link]] taps the WebView can't offer.
                    // Wave P (N1) — render the transclusion-expanded body when present (read-only projection).
                    val shown = expandedBody ?: d.body
                    val rich = com.todocompanion.app.util.NoteRichRenderer.hasMath(shown) ||
                        com.todocompanion.app.util.NoteRichRenderer.hasMermaid(shown) || shown.contains("![")
                    if (rich) {
                        var richImgs by remember(noteId) { mutableStateOf<Map<String, String>>(emptyMap()) }
                        androidx.compose.runtime.LaunchedEffect(noteId, shown) {
                            richImgs = if (shown.contains("![")) vm.noteImageMap(noteId) else emptyMap()
                        }
                        com.todocompanion.app.ui.components.RichNoteView(
                            shown, richImgs, Modifier.fillMaxSize().padding(end = 36.dp),
                        )
                    } else androidx.compose.foundation.text.selection.SelectionContainer {
                        MarkdownText(
                            shown,
                            modifier = Modifier.fillMaxWidth().padding(end = 36.dp),
                            // Checkbox line-toggle maps to the raw body, so disable it on an expanded projection.
                            onToggleCheckbox = if (d.readonly || expandedBody != null) null else { line -> persist(d.copy(body = com.todocompanion.app.domain.NoteEditing.toggleCheckboxAtLine(d.body, line))) },
                        )
                    }
                } else {
                    NoteBodyEditor(
                        value = d.body, onValueChange = { draft = d.copy(body = it) },
                        modifier = Modifier.fillMaxWidth().padding(end = 36.dp),
                        readOnly = d.readonly,
                        noteTitles = notes.filter { it.id != noteId && !it.trashed && it.title.isNotBlank() }.map { it.title },
                        tagNames = tags.map { it.name },
                    )
                }
                if (d.body.isNotBlank() && !d.readonly) {
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
                // Cross-module (Wave C): each [[link]] is resolved (materialized on save) to a note / task /
                // habit / event; the chip shows a type glyph and a task chip opens the task.
                val linkByTitle = remember(links) { links.associateBy { it.targetTitle.trim().lowercase() } }
                Text("Links", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(outTitles, key = { it }) { t ->
                        val row = linkByTitle[t.trim().lowercase()]
                        val exists = t.trim().lowercase() in existingTitles
                        val glyph = when (row?.targetType) {
                            "task" -> "✅  "; "habit" -> "🔁  "; "event" -> "📅  "
                            "note" -> if (row.targetId.isNotBlank()) "🔗  " else "＋  "
                            else -> if (exists) "🔗  " else "＋  "
                        }
                        FilterChip(
                            selected = false,
                            onClick = {
                                draft?.let { vm.closeNoteEditor(it) }
                                if (row?.targetType == "task" && row.targetId.isNotBlank()) onOpenTask(row.targetId)
                                else vm.openOrCreateNoteByTitle(t) { id -> onOpenNote(id) }
                            },
                            label = { Text(glyph + t) },
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
            // Wave E — unlinked mentions: existing note/task/habit/event titles present in the body but not
            // yet [[linked]]. One tap wraps the first occurrence into a wiki-link (Roam's feature, cross-module).
            var mentions by remember(noteId) { mutableStateOf<List<String>>(emptyList()) }
            androidx.compose.runtime.LaunchedEffect(d.body) { delay(400); mentions = runCatching { vm.unlinkedMentions(d.body, noteId) }.getOrDefault(emptyList()) }
            if (mentions.isNotEmpty()) {
                Text("Mentions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mentions, key = { it }) { m ->
                        FilterChip(selected = false, onClick = { persist(d.copy(body = com.todocompanion.app.domain.NoteEditing.linkMention(d.body, m))) }, label = { Text("＋ [[$m]]") })
                    }
                }
            }
            // Wave E — "On this day": earlier daily notes that share this day-of-month (temporal recall).
            if (d.kind == "journal" && d.dayEpoch != null) {
                val me = remember(d.dayEpoch) { runCatching { java.time.LocalDate.ofEpochDay(d.dayEpoch!!) }.getOrNull() }
                val onThisDay = remember(notes, d.dayEpoch) {
                    if (me == null) emptyList() else notes.filter {
                        it.id != noteId && it.kind == "journal" && !it.trashed && it.dayEpoch != null &&
                            runCatching { java.time.LocalDate.ofEpochDay(it.dayEpoch!!) }.getOrNull()?.let { dt -> dt.dayOfMonth == me.dayOfMonth && dt != me } == true
                    }.sortedByDescending { it.dayEpoch }
                }
                if (onThisDay.isNotEmpty()) {
                    Text("On this day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(onThisDay, key = { it.id }) { o ->
                            val lbl = runCatching { java.time.LocalDate.ofEpochDay(o.dayEpoch!!).format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")) }.getOrDefault("—")
                            FilterChip(selected = false, onClick = { onOpenNote(o.id) }, label = { Text("🗓 $lbl") })
                        }
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
    if (showExport) AlertDialog(
        onDismissRequest = { showExport = false },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } },
        title = { Text("Export note") },
        text = {
            Column {
                com.todocompanion.app.util.NoteExport.Format.entries.forEach { fmt ->
                    Text(fmt.label, Modifier.fillMaxWidth().clickable {
                        showExport = false
                        if (fmt == com.todocompanion.app.util.NoteExport.Format.PDF)
                            com.todocompanion.app.util.NoteExport.printPdf(ctx, d)
                        else vm.exportNote(noteId, fmt)
                    }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
    )
    if (showSeal) {
        val nowMs = System.currentTimeMillis()
        val presets = listOf(
            "In a week" to nowMs + 7L * 86_400_000L,
            "In a month" to nowMs + 30L * 86_400_000L,
            "In 3 months" to nowMs + 90L * 86_400_000L,
            "In a year" to nowMs + 365L * 86_400_000L,
        )
        AlertDialog(
            onDismissRequest = { showSeal = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSeal = false }) { Text("Cancel") } },
            title = { Text("Seal to the future") },
            text = {
                Column {
                    Text("Hide this note until the date you choose — then a reminder brings it back.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp))
                    presets.forEach { (label, at) ->
                        Text(label, Modifier.fillMaxWidth().clickable {
                            showSeal = false; vm.sealNote(noteId, at); draft = d.copy(sealedUntil = at, reminderAt = at); onBack()
                        }.padding(vertical = 11.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
        )
    }
    if (showAbout) NoteAboutDialog(d, onDismiss = { showAbout = false })
    if (showOutline) NoteOutlineDialog(d.body, onDismiss = { showOutline = false })
    if (showReminder) NoteReminderDialog(
        current = d.reminderAt,
        currentRrule = d.reminderRrule,
        currentExtra = com.todocompanion.app.reminders.AlarmScheduler.parseExtraReminders(d.reminderExtra),
        currentKeep = d.reminderKeep,
        onApply = { at, rrule, extra, keep ->
            vm.setNoteReminder(noteId, at, rrule, extra, keep)
            val extraCsv = extra.filter { it > System.currentTimeMillis() }.sorted().joinToString(",")
            draft = d.copy(reminderAt = at, reminderRrule = rrule?.ifBlank { null }, reminderExtra = extraCsv, reminderKeep = keep)
            showReminder = false
        },
        onDismiss = { showReminder = false },
    )
    if (showHistory) NoteVersionHistoryDialog(
        revisions = revisions,
        onRestore = { r -> vm.restoreNoteRevision(noteId, r.title, r.body); draft = d.copy(title = r.title, body = r.body); showHistory = false },
        onDismiss = { showHistory = false },
    )
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
