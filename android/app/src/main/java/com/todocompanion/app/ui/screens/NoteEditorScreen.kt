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
                        Surface(shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically)) {
                            Row(Modifier.clickable(onClickLabel = "Edit tags", role = androidx.compose.ui.semantics.Role.Button) { showTags = true }.padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("#${t.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Icon(Icons.Filled.Close, "Remove tag ${t.name}", modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClickLabel = "Remove tag", role = androidx.compose.ui.semantics.Role.Button) { vm.setNoteTags(noteId, (myTagIds - t.id).toList()) }.padding(2.dp))
                            }
                        }
                    }
                    // "＋" tag adder — labelled "Add tag" while the note has none, a compact "＋" once it has some.
                    Surface(shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.CenterVertically).clickable(onClickLabel = "Add tag", role = androidx.compose.ui.semantics.Role.Button) { showTags = true }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, "Add tag", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            if (myTagIds.isEmpty()) { Spacer(Modifier.width(2.dp)); Text("Add tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    // Notebook / folder pill — shows the container name when set, else "＋ Notebook/Folder". Tap picks one.
                    val hasContainer = if (useNotebooks) d.notebookId != null else d.folderId != null
                    Surface(shape = NotesTokens.Pill,
                        color = if (hasContainer) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.CenterVertically).clickable(onClickLabel = if (useNotebooks) "Choose notebook" else "Choose folder", role = androidx.compose.ui.semantics.Role.Button) { showContainer = true }) {
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
            add(PTile(Icons.Filled.Fullscreen, "Focus mode", focus) { menu = false; focus = !focus; vm.setNotesFocusMode(focus) })
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
                                Modifier.weight(1f).clip(NotesTokens.Card).clickable { t.onClick() }.padding(vertical = 10.dp),
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
                                // Default IconButton size (48dp) keeps these at the a11y minimum touch target.
                                IconButton(onClick = { renameNotebook = nb }) {
                                    Icon(Icons.Filled.Edit, "Rename notebook ${nb.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { deleteNotebookAsk = nb }) {
                                    Icon(Icons.Filled.Delete, "Delete notebook ${nb.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A compact context pill for the note header's meta row (day / meeting / task / reminder / sealed).
 *  A FlowRowScope extension so it can vertically-center within the wrapping meta row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.MetaPill(label: String, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = NotesTokens.Pill,
        color = cs.secondaryContainer.copy(alpha = .7f),
        modifier = Modifier.align(Alignment.CenterVertically)
            .let { if (onClick != null) it.clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button) { onClick() } else it },
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
        Modifier.fillMaxWidth().clip(NotesTokens.Pill).clickable(onClick = onEdit).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.Edit, "Edit $label", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
