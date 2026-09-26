package com.wkhan.hexis.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.wkhan.hexis.data.entity.NoteEntity
import com.wkhan.hexis.ui.AppViewModel
import com.wkhan.hexis.ui.components.AppCard
import com.wkhan.hexis.ui.components.AppColorPicker
import com.wkhan.hexis.ui.components.AppTextField
import com.wkhan.hexis.ui.components.ConfirmDialog
import com.wkhan.hexis.ui.components.EmojiGridPicker
import com.wkhan.hexis.ui.components.EmptyState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


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
    onOpenGarden: () -> Unit = {},
    onOpenRecall: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val smartViews by vm.smartViews.collectAsStateWithLifecycle()
    val noteTagRefs by vm.noteTagRefs.collectAsStateWithLifecycle()
    val noteContextRefs by vm.noteContextRefs.collectAsStateWithLifecycle()
    val allTags by vm.tags.collectAsStateWithLifecycle()
    val allContexts by vm.contexts.collectAsStateWithLifecycle()
    val trashed by vm.trashedNotes.collectAsStateWithLifecycle()

    val useNotebooks = settings.notesNotebookMode == "notebooks"
    val grid = settings.noteDefaultView != "list"

    var container by remember { mutableStateOf<String?>(null) }   // selected notebookId/folderId, null = All
    var archiveView by remember { mutableStateOf(false) }         // Wave B — the Archive (third state) view
    var trashView by remember { mutableStateOf(false) }           // the Trash — trashed notes, restore / delete forever
    var trashAction by remember { mutableStateOf<NoteEntity?>(null) }  // tapped trashed note → restore/delete sheet
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.purgeExpiredNoteTrash() }  // lazy auto-empty-trash sweep
    // Wave D — Smart Views: the active predicate filter (null = none), its chip id/label, and dialog state.
    var activePredicate by remember { mutableStateOf<com.wkhan.hexis.domain.NotePredicate?>(null) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var showBuilder by remember { mutableStateOf(false) }
    var deleteView by remember { mutableStateOf<com.wkhan.hexis.data.entity.SmartViewEntity?>(null) }
    var showWrapped by remember { mutableStateOf(false) }   // Wave V — Notes Wrapped recap
    var showAsk by remember { mutableStateOf(false) }       // L9 — Ask your notes (offline retrieval)
    var showNow by remember { mutableStateOf(false) }       // L10 — Right note, right now (context)
    var courierUri by remember { mutableStateOf<String?>(null) }   // Wave 3 — a picked encrypted-note file awaiting its passphrase
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
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val openTaskIds = remember(tasks) { tasks.asSequence().filter { !it.completed && !it.trashed && !it.abandoned }.map { it.id }.toSet() }
    val overdueTaskIds = remember(tasks) {
        val now = System.currentTimeMillis()
        tasks.asSequence().filter { !it.completed && !it.trashed && !it.abandoned && (it.dueDate ?: Long.MAX_VALUE) < now }.map { it.id }.toSet()
    }
    // Tag ids grouped per note, computed once per tag-ref change — the predicate below needs a note's tag
    // set, and doing `noteTagRefs.filter { it.noteId == n.id }` inside the loop was O(N·refs) every pass.
    val refsByNote = remember(noteTagRefs) { noteTagRefs.groupBy { it.noteId }.mapValues { e -> e.value.mapTo(HashSet()) { it.tagId } } }
    // L1 — context ids grouped per note, so Smart Views can filter on @context the same cheap way as tags.
    val ctxByNote = remember(noteContextRefs) { noteContextRefs.groupBy { it.noteId }.mapValues { e -> e.value.mapTo(HashSet()) { it.contextId } } }
    // The whole browse list — predicate match + search + sort — is memoized on its real inputs so it is not
    // rebuilt (and re-scanned per note) on every recomposition / search keystroke.
    val filtered = remember(notes, activePredicate, archiveView, container, useNotebooks, query, settings.notesSort, refsByNote, ctxByNote, openTaskIds, overdueTaskIds) {
        val now = System.currentTimeMillis()
        notes
        .asSequence()
        // Base browse guards — apply to EVERY browse surface, Smart Views included (a Smart View is
        // browsing, not an exception). (1) A note sealed to the future stays hidden until its reveal date
        // — it's still findable via search so it can be unsealed early, but it must never surface (title +
        // preview) in Favorites / Pinned / Untagged / a custom view. (2) The archive/active split too:
        // archived notes show only in the Archive filter, never mixed into a predicate view.
        .filter { n ->
            (n.sealedUntil == null || n.sealedUntil!! <= now) &&
                (if (archiveView) n.archived else !n.archived)
        }
        .filter { n ->
            val p = activePredicate
            if (p != null) com.wkhan.hexis.domain.NoteSmartViews.matches(
                p, com.wkhan.hexis.domain.NoteSmartViews.Ctx(
                    n.pinned, n.favorite, n.archived, n.trashed, n.title, n.body, n.kind, n.updatedAt,
                    refsByNote[n.id] ?: emptySet(), now,
                    hasReminder = n.reminderAt != null || n.reminderExtra.isNotBlank(),
                    hasOpenItems = n.hasOpen,   // P7 — materialized column, no per-note regex in the filter loop
                    linkedTaskId = n.linkedTaskId, linkedEventId = n.linkedEventId,
                    openTaskIds = openTaskIds, overdueTaskIds = overdueTaskIds,
                    // L1 — structure & date context for the new predicates.
                    notebookId = n.notebookId, folderId = n.folderId, colorArgb = n.colorArgb,
                    contextIds = ctxByNote[n.id] ?: emptySet(), createdAt = n.createdAt,
                ),
            )
            else container == null || (if (useNotebooks) n.notebookId == container else n.folderId == container)
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
                    IconButton(onClick = { batchMove = true }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move") }
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
            // Persisted like sort — the Cards/Board/Calendar family survives navigation (P6-D), and the
            // app-bar grid/list toggle is hidden unless this is "cards" (where it's the only thing it means).
            val viewMode = settings.notesViewMode
            var viewMenu by remember { mutableStateOf(false) }
            var filterMenu by remember { mutableStateOf(false) }
            var toolsMenu by remember { mutableStateOf(false) }   // the note-feature surfaces, split out of the filter list
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
                                // Multi-select only exists in Cards; leaving it would strand the selection
                                // bar over Board/Calendar items you can't toggle. Clear it on switch away.
                                onClick = { if (id != "cards") selection = emptySet(); vm.setNotesViewMode(id); viewMenu = false },
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
                            "★ Favorites" to com.wkhan.hexis.domain.NoteSmartViews.FAVORITES,
                            "📌 Pinned" to com.wkhan.hexis.domain.NoteSmartViews.PINNED,
                            "🏷 Untagged" to com.wkhan.hexis.domain.NoteSmartViews.UNTAGGED,
                        ).forEach { (lbl, pred) ->
                            DropdownMenuItem(
                                text = { Text(lbl) },
                                trailingIcon = { if (activeLabel == lbl) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = checkP) },
                                onClick = { activePredicate = pred; activeLabel = lbl; container = null; archiveView = false; trashView = false; filterMenu = false },
                            )
                        }
                        smartViews.forEach { v ->
                            val decoded = remember(v.predicateJson) { com.wkhan.hexis.domain.NoteSmartViews.decode(v.predicateJson) }
                            DropdownMenuItem(
                                text = { Text((v.icon?.let { "$it " } ?: "🔎 ") + v.title) },
                                trailingIcon = { IconButton(onClick = { deleteView = v; filterMenu = false }) { Icon(Icons.Filled.Delete, "Delete view", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                onClick = { if (decoded != null) { activePredicate = decoded; activeLabel = v.id; container = null; archiveView = false; trashView = false }; filterMenu = false },
                            )
                        }
                        // This popup stays note-ORGANIZATION only (filters + saved views). The note-feature
                        // surfaces live in their own "Note tools" popup (the ✨ button) so neither list runs long.
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("＋ Smart View") }, onClick = { filterMenu = false; showBuilder = true })
                    }
                }
                // Note tools — the feature surfaces (Journal, Life graph, Note garden, Recall, Wrapped, Ask,
                // Relevant now, Publish, Receive) as a separate popup, split out of the filter list.
                Box {
                    IconButton(onClick = { toolsMenu = true }) { Icon(Icons.Filled.Dashboard, "Note tools") }
                    DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                        DropdownMenuItem(text = { Text("🗓 Journal (daily · weekly · monthly · yearly)") }, onClick = { toolsMenu = false; onOpenJournal() })
                        DropdownMenuItem(text = { Text("◉ Life graph") }, onClick = { toolsMenu = false; onOpenGraph() })
                        DropdownMenuItem(text = { Text("🌱 Note garden") }, onClick = { toolsMenu = false; onOpenGarden() })
                        val dueCards by vm.recallDueCount.collectAsStateWithLifecycle()
                        LaunchedEffect(Unit) { vm.refreshRecall() }
                        DropdownMenuItem(text = { Text("🎴 Recall" + if (dueCards > 0) "  ·  $dueCards due" else "") }, onClick = { toolsMenu = false; onOpenRecall() })
                        DropdownMenuItem(text = { Text("✨ Wrapped") }, onClick = { toolsMenu = false; showWrapped = true })
                        DropdownMenuItem(text = { Text("🔎 Ask your notes") }, onClick = { toolsMenu = false; showAsk = true })
                        DropdownMenuItem(text = { Text("📍 Relevant now") }, onClick = { toolsMenu = false; showNow = true })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("🌐 Publish site (offline)") }, onClick = {
                            toolsMenu = false
                            com.wkhan.hexis.util.SystemPicker.openTree { uri -> vm.publishSite(uri.toString()) }
                        })
                        DropdownMenuItem(text = { Text("🔐 Receive encrypted note") }, onClick = {
                            toolsMenu = false
                            com.wkhan.hexis.util.SystemPicker.openFile(com.wkhan.hexis.util.PickTypes.ANY) { uri -> courierUri = uri.toString() }
                        })
                    }
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
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
                useNotebooks = useNotebooks,
                containers = containers,
                tags = allTags.map { it.id to it.name },
                contexts = allContexts.map { it.id to it.name },
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
                    com.wkhan.hexis.domain.NoteWrapped.compute(
                        notes.filter { !it.trashed }.map { com.wkhan.hexis.domain.NoteWrapped.In(it.id, it.title, it.body, it.createdAt, it.kind, it.dayEpoch) },
                        java.time.Year.now().value,
                    )
                }
                NoteWrappedDialog(stats, onDismiss = { showWrapped = false })
            }
            if (showAsk) {
                AskNotesDialog(vm = vm, onOpen = onOpenNote, onDismiss = { showAsk = false })
            }
            if (showNow) {
                RightNowDialog(vm = vm, onOpen = onOpenNote, onDismiss = { showNow = false })
            }
            // Wave 3 · Encrypted Note Courier — the passphrase prompt for a picked encrypted-note file.
            courierUri?.let { uri ->
                CourierPassphraseDialog(
                    title = "Open encrypted note", confirmLabel = "Decrypt",
                    message = "Enter the passphrase the sender shared with you. It's used only on this device to decrypt the note.",
                    onConfirm = { pass -> vm.receiveEncryptedNote(uri, pass) { onOpenNote(it) }; courierUri = null },
                    onDismiss = { courierUri = null },
                )
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
