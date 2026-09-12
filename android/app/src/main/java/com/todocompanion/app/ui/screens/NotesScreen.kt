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
    onOpenGraph: () -> Unit = {},
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

    // Wave E — the Life Graph opens as a top-level overlay hoisted to AppRoot (same pattern as the note
    // editor), so it covers the app chrome and shows a single header — not the notes top bar + its own.

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
                // Tight against the app bar above and the note list below — no dead band around the toolbar.
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
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
                        DropdownMenuItem(text = { Text("◉ Graph") }, onClick = { filterMenu = false; onOpenGraph() })
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
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
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
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
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


/** A compact dropdown "pill" for the Notes home toolbar — an optional leading icon, a label, and a
 *  dropdown chevron. Opens its menu on tap (the caller anchors a DropdownMenu next to it). */
@Composable
private fun ToolbarPill(label: String, leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
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
