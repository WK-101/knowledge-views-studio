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
    val trashed by vm.trashedNotes.collectAsState()

    val useNotebooks = settings.notesNotebookMode == "notebooks"
    val grid = settings.noteDefaultView != "list"

    var container by remember { mutableStateOf<String?>(null) }   // selected notebookId/folderId, null = All
    var archiveView by remember { mutableStateOf(false) }         // Wave B — the Archive (third state) view
    var trashView by remember { mutableStateOf(false) }           // the Trash — trashed notes, restore / delete forever
    var trashAction by remember { mutableStateOf<NoteEntity?>(null) }  // tapped trashed note → restore/delete sheet
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.purgeExpiredNoteTrash() }  // lazy auto-empty-trash sweep
    // Wave D — Smart Views: the active predicate filter (null = none), its chip id/label, and dialog state.
    var activePredicate by remember { mutableStateOf<com.todocompanion.app.domain.NotePredicate?>(null) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var showBuilder by remember { mutableStateOf(false) }
    var deleteView by remember { mutableStateOf<com.todocompanion.app.data.entity.SmartViewEntity?>(null) }
    var showGraph by remember { mutableStateOf(false) }
    var showWrapped by remember { mutableStateOf(false) }   // Wave V — Notes Wrapped recap
    // Multi-select (NotesNook-style) + sort.
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMenu by remember { mutableStateOf(false) }
    var batchMove by remember { mutableStateOf(false) }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

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
    // Tag ids grouped per note, computed once per tag-ref change — the predicate below needs a note's tag
    // set, and doing `noteTagRefs.filter { it.noteId == n.id }` inside the loop was O(N·refs) every pass.
    val refsByNote = remember(noteTagRefs) { noteTagRefs.groupBy { it.noteId }.mapValues { e -> e.value.mapTo(HashSet()) { it.tagId } } }
    // The whole browse list — predicate match + search + sort — is memoized on its real inputs so it is not
    // rebuilt (and re-scanned per note) on every recomposition / search keystroke.
    val filtered = remember(notes, activePredicate, archiveView, container, useNotebooks, query, settings.notesSort, refsByNote, openTaskIds, overdueTaskIds) {
        val now = System.currentTimeMillis()
        notes
        .asSequence()
        .filter { n ->
            val p = activePredicate
            if (p != null) com.todocompanion.app.domain.NoteSmartViews.matches(
                p, com.todocompanion.app.domain.NoteSmartViews.Ctx(
                    n.pinned, n.favorite, n.archived, n.trashed, n.title, n.body, n.kind, n.updatedAt,
                    refsByNote[n.id] ?: emptySet(), now,
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
                (n.sealedUntil == null || n.sealedUntil!! <= now)
        }
        .filter { n ->
            query.isBlank() || n.title.contains(query, true) || n.body.contains(query, true)
        }
        .sortedWith(
            // Pinned always float to the top; the rest follow the chosen sort order.
            compareByDescending<NoteEntity> { it.pinned }.then(
                when (settings.notesSort) {
                    "created" -> compareByDescending { it.createdAt }
                    "titleAsc" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.ifBlank { "￿" } }
                    "titleDesc" -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title.ifBlank { "" } }
                    else -> compareByDescending { it.updatedAt }
                }
            )
        )
        .toList()
    }

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
        // Multi-select action bar (shown while notes are selected). Batch actions apply to the whole set.
        if (selection.isNotEmpty()) {
            val chosen = filtered.filter { it.id in selection }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    Text("${selection.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.weight(1f))
                    // Select all currently-shown.
                    TextButton(onClick = { selection = filtered.map { it.id }.toSet() }) { Text("All") }
                    val anyUnpinned = chosen.any { !it.pinned }
                    IconButton(onClick = { chosen.forEach { vm.saveNote(it.copy(pinned = anyUnpinned)) }; selection = emptySet() }) {
                        Icon(if (anyUnpinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (anyUnpinned) "Pin" else "Unpin")
                    }
                    val anyUnfav = chosen.any { !it.favorite }
                    IconButton(onClick = { chosen.forEach { vm.saveNote(it.copy(favorite = anyUnfav)) }; selection = emptySet() }) {
                        Icon(if (anyUnfav) Icons.Filled.Star else Icons.Outlined.StarOutline, if (anyUnfav) "Favorite" else "Unfavorite")
                    }
                    IconButton(onClick = { batchMove = true }) { Icon(Icons.Filled.DriveFileMove, "Move") }
                    IconButton(onClick = { chosen.forEach { vm.archiveNote(it.id) }; selection = emptySet() }) { Icon(Icons.Filled.Archive, "Archive") }
                    IconButton(onClick = { chosen.forEach { vm.trashNote(it.id) }; selection = emptySet() }) { Icon(Icons.Filled.Delete, "Move to Trash") }
                }
            }
        }
        // Move-selected dialog: assign every selected note to a notebook/folder (or none).
        if (batchMove) {
            val chosen = filtered.filter { it.id in selection }
            AlertDialog(
                onDismissRequest = { batchMove = false },
                confirmButton = { TextButton(onClick = { batchMove = false }) { Text("Done") } },
                title = { Text(if (useNotebooks) "Move to notebook" else "Move to folder") },
                text = {
                    LazyColumn {
                        item {
                            DropdownRow("None", selected = false) {
                                chosen.forEach { vm.saveNote(if (useNotebooks) it.copy(notebookId = null) else it.copy(folderId = null)) }
                                batchMove = false; selection = emptySet()
                            }
                        }
                        if (useNotebooks) items(notebooks, key = { it.id }) { nb ->
                            DropdownRow((nb.icon?.let { "$it " } ?: "") + nb.name, selected = false) {
                                chosen.forEach { vm.saveNote(it.copy(notebookId = nb.id)) }
                                batchMove = false; selection = emptySet()
                            }
                        } else items(folders, key = { it.id }) { f ->
                            DropdownRow((f.icon?.let { "$it " } ?: "") + f.name, selected = false) {
                                chosen.forEach { vm.saveNote(it.copy(folderId = f.id)) }
                                batchMove = false; selection = emptySet()
                            }
                        }
                    }
                },
            )
        }
        run {
            // NotesNook-style top toolbar: a View selector and a Views/Filter selector as compact dropdown
            // pills (Cards/Board/Calendar; All / containers / Archived / Favorites / Pinned / Untagged /
            // saved Smart Views / Graph / Wrapped), plus Sort — replacing the old sprawling chip rows.
            var viewMode by remember { mutableStateOf("cards") }
            var viewMenu by remember { mutableStateOf(false) }
            var filterMenu by remember { mutableStateOf(false) }
            val viewLabel = when (viewMode) { "board" -> "▤ Board"; "calendar" -> "🗓 Calendar"; else -> "▦ Cards" }
            val activeFilterLabel = when {
                trashView -> "🗑 Trash"
                activeLabel != null -> smartViews.firstOrNull { it.id == activeLabel }?.let { (it.icon?.plus(" ") ?: "🔎 ") + it.title } ?: activeLabel!!
                archiveView -> "🗄 Archived"
                container != null -> containers.firstOrNull { it.first == container }?.second ?: "All notes"
                else -> "All notes"
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ToolbarPill(viewLabel) { viewMenu = true }
                    DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                        listOf("cards" to "▦ Cards", "board" to "▤ Board", "calendar" to "🗓 Calendar").forEach { (id, lbl) ->
                            DropdownMenuItem(
                                text = { Text(lbl) },
                                trailingIcon = { if (viewMode == id) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                                onClick = { viewMode = id; viewMenu = false },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f, fill = false)) {
                    ToolbarPill(activeFilterLabel, leadingIcon = Icons.Filled.FilterList) { filterMenu = true }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        val checkP = MaterialTheme.colorScheme.primary
                        DropdownMenuItem(
                            text = { Text("All notes") },
                            trailingIcon = { if (container == null && !archiveView && !trashView && activePredicate == null) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = checkP) },
                            onClick = { container = null; archiveView = false; trashView = false; activePredicate = null; activeLabel = null; filterMenu = false },
                        )
                        containers.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                trailingIcon = { if (container == id && !archiveView && !trashView && activePredicate == null) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = checkP) },
                                onClick = { container = id; archiveView = false; trashView = false; activePredicate = null; activeLabel = null; filterMenu = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("🗄 Archived") },
                            trailingIcon = { if (archiveView && activePredicate == null) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = checkP) },
                            onClick = { archiveView = true; container = null; trashView = false; activePredicate = null; activeLabel = null; filterMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("🗑 Trash" + if (trashed.isNotEmpty()) "  ·  ${trashed.size}" else "") },
                            trailingIcon = { if (trashView) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = checkP) },
                            onClick = { trashView = true; archiveView = false; container = null; activePredicate = null; activeLabel = null; filterMenu = false },
                        )
                        HorizontalDivider()
                        listOf(
                            "★ Favorites" to com.todocompanion.app.domain.NoteSmartViews.FAVORITES,
                            "📌 Pinned" to com.todocompanion.app.domain.NoteSmartViews.PINNED,
                            "🏷 Untagged" to com.todocompanion.app.domain.NoteSmartViews.UNTAGGED,
                        ).forEach { (lbl, pred) ->
                            DropdownMenuItem(
                                text = { Text(lbl) },
                                trailingIcon = { if (activeLabel == lbl) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = checkP) },
                                onClick = { activePredicate = pred; activeLabel = lbl; container = null; archiveView = false; trashView = false; filterMenu = false },
                            )
                        }
                        smartViews.forEach { v ->
                            val decoded = remember(v.predicateJson) { com.todocompanion.app.domain.NoteSmartViews.decode(v.predicateJson) }
                            DropdownMenuItem(
                                text = { Text((v.icon?.let { "$it " } ?: "🔎 ") + v.title) },
                                trailingIcon = { Icon(Icons.Filled.Delete, "Delete view", Modifier.size(18.dp).clickable { deleteView = v; filterMenu = false }, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { if (decoded != null) { activePredicate = decoded; activeLabel = v.id; container = null; archiveView = false; trashView = false }; filterMenu = false },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("＋ Smart View") }, onClick = { filterMenu = false; showBuilder = true })
                        DropdownMenuItem(text = { Text("◉ Graph") }, onClick = { filterMenu = false; showGraph = true })
                        DropdownMenuItem(text = { Text("✨ Wrapped") }, onClick = { filterMenu = false; showWrapped = true })
                    }
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf("updated" to "Last edited", "created" to "Date created", "titleAsc" to "Title A–Z", "titleDesc" to "Title Z–A").forEach { (id, lbl) ->
                            DropdownMenuItem(
                                text = { Text(lbl) },
                                trailingIcon = { if (settings.notesSort == id) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                                onClick = { vm.setNotesSort(id); sortMenu = false },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
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

            if (showWrapped) {
                val stats = remember(notes) {
                    com.todocompanion.app.domain.NoteWrapped.compute(
                        notes.filter { !it.trashed }.map { com.todocompanion.app.domain.NoteWrapped.In(it.id, it.title, it.body, it.createdAt, it.kind, it.dayEpoch) },
                        java.time.Year.now().value,
                    )
                }
                NoteWrappedDialog(stats, onDismiss = { showWrapped = false })
            }
            // A trashed note tapped → restore it or delete it forever.
            trashAction?.let { n ->
                AlertDialog(
                    onDismissRequest = { trashAction = null },
                    confirmButton = { TextButton(onClick = { vm.restoreNoteFromTrash(n.id); trashAction = null }) { Text("Restore") } },
                    dismissButton = { TextButton(onClick = { vm.deleteNote(n.id); trashAction = null }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) } },
                    title = { Text(n.title.ifBlank { "Untitled note" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    text = { Text("Restore this note to your notes, or delete it permanently? Deleting can't be undone.") },
                )
            }
            if (confirmEmptyTrash) {
                AlertDialog(
                    onDismissRequest = { confirmEmptyTrash = false },
                    confirmButton = { TextButton(onClick = { vm.emptyNoteTrash(); confirmEmptyTrash = false }) { Text("Empty Trash", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("Cancel") } },
                    title = { Text("Empty Trash?") },
                    text = { Text("Permanently delete all ${trashed.size} note${if (trashed.size == 1) "" else "s"} in the Trash. This can't be undone.") },
                )
            }
            if (trashView) {
                NotesTrashView(
                    trashed = trashed.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) },
                    retentionDays = settings.notesTrashRetentionDays,
                    onOpen = { trashAction = it },
                    onEmpty = { if (trashed.isNotEmpty()) confirmEmptyTrash = true },
                )
            } else if (filtered.isEmpty()) {
                EmptyState(
                    emoji = "📝",
                    title = if (query.isNotBlank()) "No matching notes" else "No notes yet",
                    body = if (query.isNotBlank()) "Try a different search."
                    else "Tap + to write your first note. Notes support Markdown and can link to tasks and days.",
                )
            } else when (viewMode) {
                "board" -> NotesBoardView(filtered, containers, useNotebooks, onOpen = onOpenNote, onTogglePin = { n -> vm.saveNote(n.copy(pinned = !n.pinned)) })
                "calendar" -> NotesCalendarView(filtered, onOpen = onOpenNote)
                else -> if (grid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(168.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { n ->
                            NoteCard(
                                n, Modifier.animateItem(),
                                selected = n.id in selection, selecting = selection.isNotEmpty(),
                                onOpen = { if (selection.isNotEmpty()) selection = if (n.id in selection) selection - n.id else selection + n.id else onOpenNote(n.id) },
                                onTogglePin = { vm.saveNote(n.copy(pinned = !n.pinned)) },
                                onLongPress = { selection = selection + n.id },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { n ->
                            NoteCard(
                                n, Modifier.animateItem(),
                                selected = n.id in selection, selecting = selection.isNotEmpty(),
                                onOpen = { if (selection.isNotEmpty()) selection = if (n.id in selection) selection - n.id else selection + n.id else onOpenNote(n.id) },
                                onTogglePin = { vm.saveNote(n.copy(pinned = !n.pinned)) },
                                onLongPress = { selection = selection + n.id },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The Trash — trashed notes awaiting restore or permanent deletion. A banner explains auto-empty; each
 *  row opens a Restore / Delete-forever choice. */
@Composable
private fun NotesTrashView(
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
private fun NotesBoardView(
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
private fun NotesCalendarView(notes: List<NoteEntity>, onOpen: (String) -> Unit) {
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
private fun NoteCard(
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
                        IconButton(onClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onTogglePin() }, modifier = Modifier.size(28.dp)) {
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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Wave Q — the reading experience: typography + reading theme (read view / WebView) and inline
    // live-styling in the editor, all from Settings.
    val noteType = remember(settings.notesFont, settings.notesFontScale, settings.notesLineHeight, settings.notesMeasure) {
        com.todocompanion.app.domain.NoteAppearance.NoteType(settings.notesFont, settings.notesFontScale, settings.notesLineHeight, settings.notesMeasure)
    }

    var draft by remember(noteId) { mutableStateOf<NoteEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(note?.id) { if (draft == null && note != null) draft = note }
    val d = draft
    if (d == null) { Box(Modifier.fillMaxSize()) {}; return }

    // Wave O — a sealed note gets screenshot / recents-thumbnail protection while open, regardless of the
    // app-wide secure-screen setting (restored to that setting on leave).
    com.todocompanion.app.ui.components.SecureFlagWhile(active = d.sealedUntil != null, globalOn = settings.secureScreen)
    // Edit-first (NotesNook-style): the note is always the editor surface (a read-only note shows a
    // read-only editor). The fully-rendered view — math, diagrams, tables — is a clean full-screen
    // overlay reached from the ⋮ menu ("Reading view"), never an in-place swap (which used to crash).
    var showReading by remember(noteId) { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var showContainer by remember { mutableStateOf(false) }
    var showNewNotebook by remember { mutableStateOf(false) }
    var renameNotebook by remember { mutableStateOf<com.todocompanion.app.data.entity.NotebookEntity?>(null) }  // notebook being renamed
    var deleteNotebookAsk by remember { mutableStateOf<com.todocompanion.app.data.entity.NotebookEntity?>(null) }  // notebook pending delete-confirm
    var showDelete by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showHistory by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSeal by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Wave Q — focus (immersive) mode: hide the meta/context chrome so it's just the words.
    var focus by remember { mutableStateOf(settings.notesFocusMode) }
    var showReorder by remember { mutableStateOf(false) }   // Wave R — reorder sections
    var editDate by remember { mutableStateOf<String?>(null) }  // NotesNook-style editable "created"/"updated"
    var showProps by remember { mutableStateOf(false) }     // Wave S — frontmatter properties
    var showRelated by remember { mutableStateOf(false) }   // Wave T — related notes
    var showTemplate by remember { mutableStateOf(false) }  // Wave U — cross-module templates

    fun persist(n: NoteEntity) { draft = n; vm.saveNote(n) }
    // Debounced autosave for free-typing (title/body) so we don't hit the DB/FTS every keystroke.
    androidx.compose.runtime.LaunchedEffect(d.title, d.body) {
        delay(600)
        draft?.let { vm.saveNote(it) }
    }
    // Wave P (N1) — in the reading overlay, expand {{today:agenda}} / {{tasks:…}} / {{note:…}} live.
    var expandedBody by remember(noteId) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(noteId, d.body, showReading) {
        expandedBody = if (showReading && com.todocompanion.app.util.NoteTransclusion.hasTokens(d.body))
            vm.expandNoteTransclusion(d.body) else null
    }
    // Back closes the reading view first (if open), else leaves the editor.
    BackHandler { if (showReading) showReading = false else { draft?.let { vm.closeNoteEditor(it) }; onBack() } }

    val myTagIds = noteTagRefs.filter { it.noteId == noteId }.map { it.tagId }.toSet()
    val containerName = if (useNotebooks) notebooks.firstOrNull { it.id == d.notebookId }?.name
    else folders.firstOrNull { it.id == d.folderId }?.name

    Scaffold(
        topBar = {
            if (showReading) {
                // Reading view — same single Scaffold, top bar + content swap on `showReading`. No Dialog
                // and no early-return that would force-remove the open properties sheet (which crashed).
                TopAppBar(
                    expandedHeight = 52.dp,
                    navigationIcon = { IconButton(onClick = { showReading = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to editor") } },
                    title = { Text(d.title.ifBlank { "Reading view" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            } else TopAppBar(
                expandedHeight = 52.dp,   // match every other screen's top bar height
                navigationIcon = { IconButton(onClick = { draft?.let { vm.closeNoteEditor(it) }; onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(containerName ?: "Note", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    // Properties open a bottom sheet (NotesNook-style), not a dropdown.
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Note properties") }
                },
            )
        },
    ) { padding ->
        if (showReading) {
            val shownBody = remember(expandedBody, d.body) { com.todocompanion.app.domain.NoteProperties.strip(expandedBody ?: d.body) }
            if (shownBody.isBlank()) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing to preview yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Full fidelity: the offline rich WebView renders math (KaTeX), diagrams (Mermaid),
                // syntax-highlighted code (Prism), callouts and local image attachments — all from bundled
                // assets, no network. (The inline editor keeps the Compose-native live-styling.)
                val imageMap by androidx.compose.runtime.produceState(emptyMap<String, String>(), noteId, shownBody) {
                    value = runCatching { vm.noteImageMap(noteId) }.getOrDefault(emptyMap())
                }
                com.todocompanion.app.ui.components.RichNoteView(
                    markdown = shownBody,
                    images = imageMap,
                    readingThemeId = settings.notesReadingTheme,
                    type = noteType,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
          // Header content (meta, title, tags, context) is inset 16dp; the body + bottom bar go
          // edge-to-edge so the formatting bar fills the screen width like NotesNook.
          Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Live word count, the note's woven-context pills (day / meeting / task / reminder / sealed) and
            // its tags — ALL in one compact wrapping row above the title (NotesNook-style). Tags show as
            // chips (tap to edit, ✕ removes); the "＋" adds. No separate tag/context rows → a tight header.
            val wordCount = remember(d.body) {
                com.todocompanion.app.domain.NoteProperties.strip(d.body).trim()
                    .split(Regex("\\s+")).count { it.isNotBlank() }
            }
            val dayLabel = if (d.kind == "journal" && d.dayEpoch != null) runCatching {
                java.time.LocalDate.ofEpochDay(d.dayEpoch!!).format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM"))
            }.getOrNull() else null
            val reminderLabel = d.reminderAt?.let { at -> runCatching {
                java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM · h:mm a")) }.getOrNull() }
            val sealedLabel = d.sealedUntil?.takeIf { it > System.currentTimeMillis() }?.let { at -> runCatching {
                java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrNull() }
            if (!focus) {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("$wordCount ${if (wordCount == 1) "word" else "words"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    if (d.readonly) MetaPill("🔒 Read-only") { persist(d.copy(readonly = false)) }
                    if (dayLabel != null) MetaPill("🗓 $dayLabel")
                    if (d.linkedEventId != null) MetaPill("📅 Meeting")
                    if (d.linkedTaskId != null) MetaPill("🔗 Task") { onOpenTask(d.linkedTaskId!!) }
                    if (reminderLabel != null) MetaPill("⏰ $reminderLabel" + if (d.reminderRrule != null) " ↻" else "") { showReminder = true }
                    if (sealedLabel != null) MetaPill("🔒 Sealed until $sealedLabel")
                    tags.filter { it.id in myTagIds }.forEach { t ->
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically)) {
                            Row(Modifier.clickable { showTags = true }.padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("#${t.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Icon(Icons.Filled.Close, "Remove tag", modifier = Modifier.size(14.dp).padding(start = 2.dp).clickable { vm.setNoteTags(noteId, (myTagIds - t.id).toList()) })
                            }
                        }
                    }
                    // "＋" tag adder — labelled "Add tag" while the note has none, a compact "＋" once it has some.
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.CenterVertically).clickable { showTags = true }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, "Add tag", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            if (myTagIds.isEmpty()) { Spacer(Modifier.width(2.dp)); Text("Add tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    // Notebook / folder pill — shows the container name when set, else "＋ Notebook/Folder". Tap picks one.
                    val hasContainer = if (useNotebooks) d.notebookId != null else d.folderId != null
                    Surface(shape = RoundedCornerShape(8.dp),
                        color = if (hasContainer) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.CenterVertically).clickable { showContainer = true }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Book, null, modifier = Modifier.size(14.dp),
                                tint = if (hasContainer) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(3.dp))
                            Text(
                                containerName ?: (if (useNotebooks) "Notebook" else "Folder"),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hasContainer) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else Spacer(Modifier.height(2.dp))
            // Title row — a borderless title; the cover emoji shows inline-left ONLY when one is set.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!d.coverEmoji.isNullOrBlank()) {
                    Surface(
                        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(38.dp).clip(CircleShape).clickable { showEmoji = true },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(d.coverEmoji!!, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // Borderless, tight title (BasicTextField — no Material internal padding, so title and
                // body sit close together like NotesNook).
                androidx.compose.foundation.text.BasicTextField(
                    value = d.title, onValueChange = { draft = d.copy(title = it) },
                    singleLine = true,
                    // Read-only note → the title is locked too (previously only the body honoured readonly,
                    // so a "read only" note could still have its title edited).
                    readOnly = d.readonly,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).padding(top = 2.dp, bottom = 1.dp),
                    decorationBox = { inner ->
                        if (d.title.isEmpty()) Text("Note title", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    },
                )
            }
          }
            // Body — always the editor (NotesNook-style), edge-to-edge. Inline live-styling renders
            // bold/italic/headings/code as you type; the fully-rendered view (math/diagrams/tables) is
            // the "Reading view" from the properties sheet. The body text pads itself 16dp; the bottom
            // formatting bar fills the full width.
            // Autocomplete corpora depend only on the note set — not on what you're typing — so memoize them
            // instead of re-scanning every note on every keystroke.
            val noteTitles = remember(notes, noteId) { notes.filter { it.id != noteId && !it.trashed && it.title.isNotBlank() }.map { it.title } }
            val tagNames = remember(tags) { tags.map { it.name } }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                NoteBodyEditor(
                    value = d.body, onValueChange = { draft = d.copy(body = it) },
                    modifier = Modifier.fillMaxSize(),
                    readOnly = d.readonly,
                    noteTitles = noteTitles,
                    tagNames = tagNames,
                    liveStyle = settings.notesLiveStyle, type = noteType,
                    onFontScaleChange = { vm.setNotesFontScale(it) },
                )
            }
            // Phase 3 — [[wiki-links]] out (tap to open, or create if new) and backlinks in ("Linked from").
            // Title-based like Obsidian; backlinks computed on the fly from other notes' bodies. Both are
            // memoized: out-links re-parse only when THIS body changes; backlinks re-scan the corpus only when
            // the note set or this note's title changes — never on every body keystroke (was an O(N·len) scan).
            val outTitles = remember(d.body) { com.todocompanion.app.domain.NoteLinks.outgoingTitles(d.body) }
            val existingTitles = remember(notes) { notes.filter { !it.trashed }.map { it.title.trim().lowercase() }.toHashSet() }
            val backlinks = remember(notes, noteId, d.title) {
                if (d.title.isBlank()) emptyList()
                else notes.filter { it.id != noteId && !it.trashed && com.todocompanion.app.domain.NoteLinks.links(it.body, d.title) }
            }
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
            Spacer(Modifier.height(8.dp))
        }
    }

    // Properties sheet (NotesNook-style) — replaces the overflow dropdown. Header + timestamps +
    // tag/colour chips + a grid of every note action we support.
    if (menu) {
        val df = remember { java.text.SimpleDateFormat("d MMM yyyy · h:mm a", java.util.Locale.getDefault()) }
        val boxCount = com.todocompanion.app.domain.NoteLinks.uncheckedCheckboxes(d.body).size
        val sealed = d.sealedUntil != null && d.sealedUntil!! > System.currentTimeMillis()
        data class PTile(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val active: Boolean = false, val danger: Boolean = false, val onClick: () -> Unit)
        val tiles = buildList {
            add(PTile(if (d.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Pin", d.pinned) { persist(d.copy(pinned = !d.pinned)) })
            add(PTile(if (d.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline, "Favorite", d.favorite) { persist(d.copy(favorite = !d.favorite)) })
            add(PTile(Icons.Filled.MenuBook, "Reading view") { menu = false; showReading = true })
            add(PTile(if (d.readonly) Icons.Filled.Edit else Icons.Filled.EditOff, if (d.readonly) "Allow editing" else "Read only", d.readonly) { persist(d.copy(readonly = !d.readonly)) })
            add(PTile(Icons.Filled.Fullscreen, "Focus mode", focus) { menu = false; focus = !focus })
            add(PTile(Icons.Filled.Book, if (useNotebooks) "Notebook" else "Folder") { menu = false; showContainer = true })
            add(PTile(Icons.Filled.EmojiEmotions, if (d.coverEmoji.isNullOrBlank()) "Cover emoji" else "Change emoji") { menu = false; showEmoji = true })
            add(PTile(Icons.Filled.Alarm, "Remind me", d.reminderAt != null) { menu = false; showReminder = true })
            add(PTile(Icons.Filled.History, "History") { menu = false; showHistory = true })
            add(PTile(Icons.Filled.Link, "Related") { menu = false; showRelated = true })
            add(PTile(Icons.Filled.FormatListBulleted, "Outline") { menu = false; showOutline = true })
            add(PTile(Icons.Filled.Info, "Note info") { menu = false; showAbout = true })
            add(PTile(Icons.Filled.SwapVert, "Reorder") { menu = false; showReorder = true })
            add(PTile(Icons.Filled.Tune, "Properties") { menu = false; showProps = true })
            add(PTile(Icons.Filled.Dashboard, "Template") { menu = false; showTemplate = true })
            if (boxCount > 0) add(PTile(Icons.Filled.CheckBox, "Extract tasks") {
                menu = false
                vm.extractNoteCheckboxes(noteId) { n -> android.widget.Toast.makeText(ctx, "Added $n task${if (n == 1) "" else "s"} to Inbox", android.widget.Toast.LENGTH_SHORT).show() }
            })
            add(PTile(Icons.Filled.ContentCopy, "Duplicate") { menu = false; draft?.let { vm.closeNoteEditor(it) }; vm.duplicateNote(noteId) { id -> onOpenNote(id) } })
            add(PTile(Icons.Filled.FileDownload, "Export") { menu = false; showExport = true })
            if (d.kind == "journal" && d.dayEpoch != null) add(PTile(Icons.Filled.Autorenew, "Insert digest") {
                menu = false; scope.launch { val md = vm.dayDigestMarkdown(d.dayEpoch!!); persist(d.copy(body = md + "\n" + d.body)) }
            })
            add(PTile(if (sealed) Icons.Filled.LockOpen else Icons.Filled.Lock, if (sealed) "Unseal" else "Seal") {
                if (sealed) { vm.unsealNote(noteId); draft = d.copy(sealedUntil = null, reminderAt = null) } else { menu = false; showSeal = true }
            })
            add(PTile(Icons.Filled.Archive, "Archive") { menu = false; vm.archiveNote(noteId); onBack() })
            add(PTile(Icons.Filled.Delete, "Move to trash", danger = true) { menu = false; vm.trashNote(noteId); onBack() })
            add(PTile(Icons.Filled.DeleteForever, "Delete", danger = true) { menu = false; showDelete = true })
        }
        ModalBottomSheet(onDismissRequest = { menu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = null) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { menu = false; showReading = true }) { Icon(Icons.Filled.OpenInFull, "Open reading view", modifier = Modifier.size(20.dp)) }
                }
                HorizontalDivider()
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                    DateEditRow("Created", df.format(java.util.Date(d.createdAt))) { editDate = "created" }
                    DateEditRow("Last edited", df.format(java.util.Date(d.updatedAt))) { editDate = "updated" }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { menu = false; showTags = true },
                        label = { Text(if (myTagIds.isEmpty()) "Add tag" else "${myTagIds.size} tag${if (myTagIds.size == 1) "" else "s"}") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) },
                    )
                    com.todocompanion.app.ui.components.AppColorPicker(current = d.colorArgb, onPick = { persist(d.copy(colorArgb = it)) }, allowNone = true)
                    Text(if (d.colorArgb == null) "Add colour" else "Colour", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                tiles.chunked(4).forEach { rowTiles ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        rowTiles.forEach { t ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { t.onClick() }.padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    t.icon, t.label, modifier = Modifier.size(24.dp),
                                    tint = when { t.danger -> MaterialTheme.colorScheme.error; t.active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    t.label, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = if (t.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        repeat(4 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    // NotesNook-style editable timestamps: tap the pencil on Created / Last edited to set the date & time.
    if (editDate != null) {
        val field = editDate!!
        com.todocompanion.app.ui.components.DateTimePickerDialog(
            initial = if (field == "created") d.createdAt else d.updatedAt,
            onDismiss = { editDate = null },
            onConfirm = { millis ->
                persist(if (field == "created") d.copy(createdAt = millis) else d.copy(updatedAt = millis))
                editDate = null
            },
        )
    }

    if (showEmoji) {
        AlertDialog(
            onDismissRequest = { showEmoji = false },
            confirmButton = { TextButton(onClick = { showEmoji = false }) { Text("Done") } },
            dismissButton = {
                if (!d.coverEmoji.isNullOrBlank())
                    TextButton(onClick = { persist(d.copy(coverEmoji = null)); showEmoji = false }) { Text("Remove") }
            },
            title = { Text("Cover emoji") },
            text = { EmojiGridPicker(current = d.coverEmoji) { picked -> persist(d.copy(coverEmoji = picked)); showEmoji = false } },
        )
    }
    // Tags dialog (opened from the "＋ Add tag" corner): toggle existing tags or create a new one.
    if (showTags) {
        var newTag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTags = false },
            confirmButton = { TextButton(onClick = { showTags = false }) { Text("Done") } },
            title = { Text("Tags") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = newTag, onValueChange = { newTag = it },
                            singleLine = true, placeholder = { Text("New tag") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = newTag.isNotBlank(),
                            onClick = { val name = newTag.trim(); newTag = ""; if (name.isNotBlank()) vm.createAndAssignNoteTag(noteId, name, myTagIds.toList()) },
                        ) { Text("Add") }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(tags, key = { it.id }) { t ->
                            val on = t.id in myTagIds
                            DropdownRow("#${t.name}", selected = on) {
                                vm.setNoteTags(noteId, (if (on) myTagIds - t.id else myTagIds + t.id).toList())
                            }
                        }
                    }
                }
            },
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
                    if (useNotebooks) {
                        items(notebooks, key = { it.id }) { nb ->
                            // Select on tap, plus rename / delete affordances (previously a notebook could be
                            // created but never edited or removed).
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (nb.icon?.let { "$it " } ?: "") + nb.name,
                                    modifier = Modifier.weight(1f)
                                        .clickable { persist(d.copy(notebookId = nb.id)); showContainer = false }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    color = if (d.notebookId == nb.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                IconButton(onClick = { renameNotebook = nb }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.Edit, "Rename notebook", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { deleteNotebookAsk = nb }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.Delete, "Delete notebook", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            DropdownRow("＋  New notebook…", selected = false) { showContainer = false; showNewNotebook = true }
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
    if (showNewNotebook) {
        var nbName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewNotebook = false },
            confirmButton = {
                TextButton(
                    enabled = nbName.isNotBlank(),
                    onClick = {
                        val name = nbName
                        showNewNotebook = false
                        vm.createNotebook(name) { id -> persist(d.copy(notebookId = id)) }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewNotebook = false }) { Text("Cancel") } },
            title = { Text("New notebook") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = nbName, onValueChange = { nbName = it },
                    singleLine = true, placeholder = { Text("Notebook name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
    renameNotebook?.let { nb ->
        var newName by remember(nb.id) { mutableStateOf(nb.name) }
        AlertDialog(
            onDismissRequest = { renameNotebook = null },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    vm.saveNotebook(nb.id, newName.trim(), nb.icon, nb.colorArgb); renameNotebook = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameNotebook = null }) { Text("Cancel") } },
            title = { Text("Rename notebook") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    singleLine = true, placeholder = { Text("Notebook name") }, modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
    deleteNotebookAsk?.let { nb ->
        val count = notes.count { it.notebookId == nb.id && !it.trashed }
        AlertDialog(
            onDismissRequest = { deleteNotebookAsk = null },
            confirmButton = {
                TextButton(onClick = {
                    // If the open note lives in this notebook, drop its link locally so the pill updates at once.
                    if (d.notebookId == nb.id) persist(d.copy(notebookId = null))
                    vm.deleteNotebook(nb.id); deleteNotebookAsk = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteNotebookAsk = null }) { Text("Cancel") } },
            title = { Text("Delete notebook?") },
            text = {
                Text(
                    if (count > 0) "\"${nb.name}\" and its label will be removed. The $count note${if (count == 1) "" else "s"} inside stay — they just move to no notebook."
                    else "\"${nb.name}\" will be removed. It has no notes."
                )
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
    if (showReorder) SectionReorderDialog(d.body, onApply = { persist(d.copy(body = it)); showReorder = false }, onDismiss = { showReorder = false })
    if (showProps) NotePropertiesDialog(d.body, onApply = { persist(d.copy(body = it)); showProps = false }, onDismiss = { showProps = false })
    if (showRelated) {
        val hits = remember(noteId, notes) {
            com.todocompanion.app.domain.NoteRelated.related(noteId, notes.filter { !it.trashed }.map { com.todocompanion.app.domain.NoteRelated.Doc(it.id, it.title, it.body) })
        }
        RelatedNotesDialog(hits, onOpen = { showRelated = false; onOpenNote(it) }, onDismiss = { showRelated = false })
    }
    if (showTemplate) NoteTemplateDialog(onPick = { t ->
        val (ti, b) = com.todocompanion.app.domain.NoteTemplates.apply(t)
        // Fresh note → adopt the whole scaffold; existing note → append body only (skip its frontmatter).
        val newBody = if (d.body.isBlank()) b else d.body.trimEnd() + "\n\n" + com.todocompanion.app.domain.NoteProperties.strip(b)
        persist(d.copy(title = if (d.title.isBlank()) ti else d.title, body = newBody)); showTemplate = false
    }, onDismiss = { showTemplate = false })
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

/** A compact dropdown "pill" for the Notes home toolbar — an optional leading icon, a label, and a
 *  dropdown chevron. Opens its menu on tap (the caller anchors a DropdownMenu next to it). */
@Composable
private fun ToolbarPill(label: String, leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp), color = cs.surfaceVariant.copy(alpha = .6f), onClick = onClick) {
        Row(Modifier.padding(start = if (leadingIcon != null) 8.dp else 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) { Icon(leadingIcon, null, Modifier.size(17.dp), tint = cs.onSurfaceVariant); Spacer(Modifier.width(5.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        }
    }
}

/** A compact context pill for the note header's meta row (day / meeting / task / reminder / sealed).
 *  A FlowRowScope extension so it can vertically-center within the wrapping meta row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.MetaPill(label: String, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cs.secondaryContainer.copy(alpha = .7f),
        modifier = Modifier.align(Alignment.CenterVertically).let { if (onClick != null) it.clickable { onClick() } else it },
    ) {
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** A "Created / Last edited" row in the properties sheet with a pencil to edit the timestamp (NotesNook parity). */
@Composable
private fun DateEditRow(label: String, value: String, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onEdit).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.Edit, "Edit $label", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
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
