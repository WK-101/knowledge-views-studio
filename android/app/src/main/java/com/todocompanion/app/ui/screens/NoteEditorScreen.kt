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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Note editor — Markdown body (viewer-until-edit), notebook/folder, colour, cover emoji, tags, pin.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
/**
 * The note editor's ⋮-menu dialogs, modelled as one-at-a-time state. Reading view and focus mode are
 * *modes* (they replace or restyle the whole editor), so they stay their own booleans; everything that
 * is a Dialog is a value here. Collapsing 15 booleans into one nullable state removes the whole class of
 * “two sheets open at once” bugs and makes dismissal a single `sheet = null`.
 */
private enum class NoteSheet { Tags, Contexts, Emoji, Container, NewNotebook, Delete, About, History, Outline, Reminder, Export, Seal, Reorder, Props, Related, Template }

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
    val contexts by vm.contexts.collectAsState()
    val noteContextRefs by vm.noteContextRefs.collectAsState()
    val revisions by vm.observeNoteRevisions(noteId).collectAsState(initial = emptyList())
    val links by vm.observeNoteLinks(noteId).collectAsState(initial = emptyList())

    val useNotebooks = settings.notesNotebookMode == "notebooks"
    val note = notes.firstOrNull { it.id == noteId }
    // Wave Q — the reading experience: typography + reading theme (read view / WebView) and inline
    // live-styling in the editor, all from Settings.
    val noteType = remember(settings.notesFont, settings.notesFontScale, settings.notesLineHeight, settings.notesMeasure) {
        com.todocompanion.app.domain.NoteAppearance.NoteType(settings.notesFont, settings.notesFontScale, settings.notesLineHeight, settings.notesMeasure)
    }

    // L11 — Vault: a vaulted note's stored body is ciphertext; decrypt it for editing once unlocked, and
    // re-key the draft the moment the session unlocks so we never render or edit the raw envelope.
    val vaultUnlocked by vm.vaultUnlocked.collectAsState()
    var vaultDialog by remember(noteId) { mutableStateOf(false) }
    var draft by remember(noteId) { mutableStateOf<NoteEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(note?.id, vaultUnlocked) {
        val nn = note ?: return@LaunchedEffect
        if (draft == null) draft = if (nn.vault && vaultUnlocked) nn.copy(body = vm.decryptNoteBody(nn)) else nn
        else if (nn.vault && vaultUnlocked && com.todocompanion.app.domain.NoteVault.isLocked(draft!!.body)) draft = nn.copy(body = vm.decryptNoteBody(nn))
    }
    // L5 — Shared Checkboxes (pull): on open, bring bound "- [ ] [[Task]]" lines into line with the live
    // task state, so completing a task elsewhere shows here. Runs once per note open, before the draft edits.
    androidx.compose.runtime.LaunchedEffect(noteId) { vm.reconcileNoteCheckboxes(noteId) }
    // L11 — a locked vault note stays sealed until the session is unlocked: never show the ciphertext body.
    if (note?.vault == true && !vaultUnlocked) {
        VaultLockedPane(vm = vm, title = note.title, onBack = onBack)
        return
    }
    val d = draft
    if (d == null) { Box(Modifier.fillMaxSize()) {}; return }
    // L11 — set up or unlock the Vault, then mark this note vaulted on success.
    if (vaultDialog) VaultUnlockDialog(vm = vm, onReady = { draft = draft?.copy(vault = true); vaultDialog = false }, onDismiss = { vaultDialog = false })

    // Wave O + SEC — a sealed OR vault note gets screenshot / recents-thumbnail protection while open,
    // regardless of the app-wide secure-screen setting (restored to that setting on leave). A vault note is
    // only rendered here once unlocked, so its decrypted body is on screen — never let that be captured.
    com.todocompanion.app.ui.components.SecureFlagWhile(active = d.sealedUntil != null || d.vault, globalOn = settings.secureScreen)
    // Edit-first (NotesNook-style): the note is always the editor surface (a read-only note shows a
    // read-only editor). The fully-rendered view — math, diagrams, tables — is a clean full-screen
    // overlay reached from the ⋮ menu ("Reading view"), never an in-place swap (which used to crash).
    var showReading by remember(noteId) { mutableStateOf(false) }
    // L12 — split preview: keep editing on top while a live rich render (math/diagrams/tables) tracks below.
    var showSplit by remember(noteId) { mutableStateOf(false) }
    // While the soft keyboard is up we collapse the split to a single full-height editor — otherwise the
    // top edit pane shrinks to a sliver and the bottom preview hides behind the keyboard. Editing then
    // looks exactly like the normal single view; the split returns the instant the keyboard closes. The
    // toggle (showSplit) itself is preserved, so it's remembered while you type.
    // L14 — handwriting/ink pad, opened from the editor's insert-block sheet.
    var showInk by remember(noteId) { mutableStateOf(false) }
    // Wave 2 — Evergreen review cadence + Writing sprint state.
    var showReview by remember(noteId) { mutableStateOf(false) }
    var showPrivacy by remember(noteId) { mutableStateOf(false) }   // Privacy Governance Dial
    var showSprintStart by remember(noteId) { mutableStateOf(false) }
    var showAddThread by remember(noteId) { mutableStateOf(false) }  // Threads — Maps of Content
    var showCourier by remember(noteId) { mutableStateOf(false) }    // Wave 3 · Encrypted Note Courier
    var sprintStart by remember(noteId) { mutableStateOf<Long?>(null) }   // sprint begin millis; null = idle
    var sprintStartWords by remember(noteId) { androidx.compose.runtime.mutableIntStateOf(0) }
    var sprintNow by remember(noteId) { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(sprintStart) {
        while (sprintStart != null) { sprintNow = System.currentTimeMillis(); delay(1000) }
    }
    // One modal dialog open at a time — a single nullable [NoteSheet] replaces the old fan of 15
    // per-dialog booleans, so two dialogs can never show at once and `sheet = null` dismisses any.
    var sheet by remember(noteId) { mutableStateOf<NoteSheet?>(null) }
    var renameNotebook by remember { mutableStateOf<com.todocompanion.app.data.entity.NotebookEntity?>(null) }  // notebook being renamed
    var deleteNotebookAsk by remember { mutableStateOf<com.todocompanion.app.data.entity.NotebookEntity?>(null) }  // notebook pending delete-confirm
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    // Wave Q — focus (immersive) mode: hide the meta/context chrome so it's just the words.
    var focus by remember { mutableStateOf(settings.notesFocusMode) }
    var editDate by remember { mutableStateOf<String?>(null) }  // NotesNook-style editable "created"/"updated"

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
            vm.expandNoteTransclusion(d.body, noteId) else null
    }
    // L12 — the split preview's rendered source, debounced so live typing doesn't reload the WebView on
    // every keystroke. Seeded from the current body so opening the split shows content immediately.
    var splitBody by remember(noteId) { mutableStateOf(com.todocompanion.app.domain.NoteProperties.strip(d.body)) }
    androidx.compose.runtime.LaunchedEffect(d.body, showSplit) {
        if (!showSplit) return@LaunchedEffect
        delay(450)
        val expanded = if (com.todocompanion.app.util.NoteTransclusion.hasTokens(d.body))
            vm.expandNoteTransclusion(d.body, noteId) else d.body
        splitBody = com.todocompanion.app.domain.NoteProperties.strip(expanded)
    }
    // Back closes the reading view first (if open), else leaves the editor.
    BackHandler { if (showReading) showReading = false else { draft?.let { vm.closeNoteEditor(it) }; onBack() } }

    val myTagIds = noteTagRefs.filter { it.noteId == noteId }.map { it.tagId }.toSet()
    val myContextIds = noteContextRefs.filter { it.noteId == noteId }.map { it.contextId }.toSet()
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
                    // L12 — split preview: edit on top, live rich render below. Highlighted while active.
                    IconButton(onClick = { showSplit = !showSplit }) {
                        Icon(
                            Icons.Filled.VerticalSplit,
                            contentDescription = if (showSplit) "Hide split preview" else "Split preview",
                            tint = if (showSplit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    if (reminderLabel != null) MetaPill("⏰ $reminderLabel" + if (d.reminderRrule != null) " ↻" else "") { sheet = NoteSheet.Reminder }
                    if (sealedLabel != null) MetaPill("🔒 Reveals $sealedLabel")
                    if (d.vault) MetaPill("🔐 Vault")
                    if (d.reviewEvery > 0) MetaPill("♻️ ${com.todocompanion.app.domain.NoteReview.label(d.reviewEvery)}") { showReview = true }
                    if (d.linkedHabitId != null) MetaPill("🔁 Habit journal")
                    if (d.linkedGoalId != null) MetaPill("🎯 Goal journal")
                    tags.filter { it.id in myTagIds }.forEach { t ->
                        Surface(shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically)) {
                            Row(Modifier.clickable(onClickLabel = "Edit tags", role = androidx.compose.ui.semantics.Role.Button) { sheet = NoteSheet.Tags }
                                // A11y: expose "Remove" as a first-class custom action on the chip, so TalkBack
                                // users can drop the tag without having to hit the compact inline ✕ target.
                                .semantics { customActions = listOf(CustomAccessibilityAction("Remove tag ${t.name}") { vm.setNoteTags(noteId, (myTagIds - t.id).toList()); true }) }
                                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("#${t.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Icon(Icons.Filled.Close, "Remove tag ${t.name}", modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClickLabel = "Remove tag", role = androidx.compose.ui.semantics.Role.Button) { vm.setNoteTags(noteId, (myTagIds - t.id).toList()) }.padding(2.dp))
                            }
                        }
                    }
                    // "＋" tag adder — labelled "Add tag" while the note has none, a compact "＋" once it has some.
                    Surface(shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.CenterVertically).clickable(onClickLabel = "Add tag", role = androidx.compose.ui.semantics.Role.Button) { sheet = NoteSheet.Tags }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, "Add tag", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            if (myTagIds.isEmpty()) { Spacer(Modifier.width(2.dp)); Text("Add tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    // Context chips (@context) — the same first-class picker tasks have, so a note can carry
                    // its where/with-what just like a task. Only shown once assigned; add via the "@" chip.
                    contexts.filter { it.id in myContextIds }.forEach { c ->
                        Surface(shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically)) {
                            Row(Modifier.clickable(onClickLabel = "Edit contexts", role = androidx.compose.ui.semantics.Role.Button) { sheet = NoteSheet.Contexts }
                                .semantics { customActions = listOf(CustomAccessibilityAction("Remove context ${c.name}") { vm.setNoteContexts(noteId, (myContextIds - c.id).toList()); true }) }
                                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("@${c.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Icon(Icons.Filled.Close, "Remove context ${c.name}", modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClickLabel = "Remove context", role = androidx.compose.ui.semantics.Role.Button) { vm.setNoteContexts(noteId, (myContextIds - c.id).toList()) }.padding(2.dp))
                            }
                        }
                    }
                    Surface(shape = NotesTokens.Pill, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.CenterVertically).clickable(onClickLabel = "Add context", role = androidx.compose.ui.semantics.Role.Button) { sheet = NoteSheet.Contexts }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, "Add context", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            if (myContextIds.isEmpty()) { Spacer(Modifier.width(2.dp)); Text("Context", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    // Notebook / folder pill — shows the container name when set, else "＋ Notebook/Folder". Tap picks one.
                    val hasContainer = if (useNotebooks) d.notebookId != null else d.folderId != null
                    Surface(shape = NotesTokens.Pill,
                        color = if (hasContainer) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.CenterVertically).clickable(onClickLabel = if (useNotebooks) "Choose notebook" else "Choose folder", role = androidx.compose.ui.semantics.Role.Button) { sheet = NoteSheet.Container }) {
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
                        modifier = Modifier.size(38.dp).clip(CircleShape).clickable { sheet = NoteSheet.Emoji },
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
            // Wave 2 · Writing Sprints — the live sprint bar (words written vs goal + elapsed), when running.
            if (sprintStart != null) {
                val bodyWords = remember(d.body) {
                    com.todocompanion.app.domain.NoteProperties.strip(d.body).trim().split(Regex("\\s+")).count { it.isNotBlank() }
                }
                val written = (bodyWords - sprintStartWords).coerceAtLeast(0)
                SprintBar(
                    elapsedSec = ((sprintNow - sprintStart!!).coerceAtLeast(0)) / 1000,
                    words = written, goal = d.wordGoal,
                    onStop = {
                        val start = sprintStart!!; sprintStart = null
                        vm.logNoteSprint(noteId, start, System.currentTimeMillis())
                        if (d.wordGoal in 1..written) android.widget.Toast.makeText(ctx, "🎉 Sprint done — $written words!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }
            // Wave 2 · Threads — a prev/next bar for every thread (Map of Content) this note belongs to.
            if (d.kind != com.todocompanion.app.domain.NoteThreads.KIND && d.title.isNotBlank()) {
                val threadPositions = remember(notes, d.title, noteId) { vm.noteThreadPositions(d.title) }
                threadPositions.forEach { pos ->
                    ThreadBar(pos,
                        onOpenPrev = { pos.prevTitle?.let { t -> draft?.let { n -> vm.closeNoteEditor(n) }; vm.openNoteByTitle(t) { id -> onOpenNote(id) } } },
                        onOpenNext = { pos.nextTitle?.let { t -> draft?.let { n -> vm.closeNoteEditor(n) }; vm.openNoteByTitle(t) { id -> onOpenNote(id) } } },
                        onOpenThread = { draft?.let { n -> vm.closeNoteEditor(n) }; onOpenNote(pos.threadId) })
                }
            }
            // L12 — split preview. ONE stable editor pane (never destroyed/recreated), with the live rich
            // render appended BELOW it only while split is on and the keyboard is down. Keeping the editor a
            // single call site is what fixes the earlier bugs — swapping between a "split" and a "single"
            // editor dropped the editor's focus and text and made tapping it flash the keyboard in a loop.
            // When the keyboard comes up the preview simply disappears and the editor grows to full height.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surface)) {
                val previewVisible = showSplit && !WindowInsets.isImeVisible
                // R108-diag — temporary trace so a lingering black-flash can be pinpointed from logcat.
                androidx.compose.runtime.LaunchedEffect(previewVisible) { android.util.Log.d("KairoSplitDiag", "previewVisible=$previewVisible imeVisible=${!previewVisible && showSplit}") }
                // DECOUPLED HEIGHTS — the crux of the split fix. The preview is a WebView, and instantiating one
                // blocks the UI thread (Chromium init). Earlier the editor shared the height via weight(), so
                // mounting the WebView forced the editor to be re-measured in the SAME (blocked) frame → the top
                // pane appeared blank/delayed. Here the EDITOR gets an EXPLICIT fixed height (full, or half when
                // split); the preview takes the rest. Because the editor's height is a constant dp, mounting or
                // swapping the WebView below can never re-measure or repaint the editor — it stays put.
                val editorH = if (previewVisible) (maxHeight - 1.dp) / 2 else maxHeight
                // …and even the editor's own one re-measure (full→half) happens a frame BEFORE the WebView
                // mounts: the bottom shows instant plain text first, upgrading to the rich WebView a tick later.
                var previewReady by remember(noteId) { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(previewVisible) {
                    previewReady = false
                    if (previewVisible) { delay(48); previewReady = true }
                }
                Column(Modifier.fillMaxSize()) {
                    // Opaque editor surface: even if the window briefly re-composites when the preview
                    // WebView attaches, the editor paints its own background so it can never show black.
                    Box(Modifier.fillMaxWidth().height(editorH).background(MaterialTheme.colorScheme.surface)) {
                        NoteBodyEditor(
                            value = d.body, onValueChange = { draft = d.copy(body = it) },
                            modifier = Modifier.fillMaxSize(),
                            readOnly = d.readonly,
                            noteTitles = noteTitles,
                            tagNames = tagNames,
                            liveStyle = settings.notesLiveStyle, type = noteType,
                            onFontScaleChange = { vm.setNotesFontScale(it) },
                            onInk = { showInk = true },
                            resetKey = noteId,
                        )
                    }
                    if (previewVisible) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val splitImages by androidx.compose.runtime.produceState(emptyMap<String, String>(), noteId, splitBody) {
                            value = runCatching { vm.noteImageMap(noteId) }.getOrDefault(emptyMap())
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            when {
                                splitBody.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Preview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Instant plain text until the WebView is mounted a tick later.
                                !previewReady -> Text(
                                    splitBody, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> com.todocompanion.app.ui.components.RichNoteView(
                                    markdown = splitBody,
                                    images = splitImages,
                                    readingThemeId = settings.notesReadingTheme,
                                    type = noteType,
                                    modifier = Modifier.fillMaxSize(),
                                    // R108 — software-composited so mounting it next to the live editor can't
                                    // trigger a window surface transition that blacks the editor out.
                                    softwareLayer = true,
                                )
                            }
                        }
                    }
                }
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
            // Wave 3 · Outcome Ledger — what this note's links actually moved (tasks done, streaks, time).
            if (outTitles.isNotEmpty()) {
                val outcome by androidx.compose.runtime.produceState(
                    com.todocompanion.app.domain.NoteOutcome.Rollup(), noteId, links) {
                    value = runCatching { vm.noteOutcome(noteId) }.getOrDefault(com.todocompanion.app.domain.NoteOutcome.Rollup())
                }
                OutcomeLedgerCard(outcome)
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
            add(PTile(Icons.AutoMirrored.Filled.MenuBook, "Reading view") { menu = false; showReading = true })
            add(PTile(if (d.readonly) Icons.Filled.Edit else Icons.Filled.EditOff, if (d.readonly) "Allow editing" else "Read only", d.readonly) { persist(d.copy(readonly = !d.readonly)) })
            add(PTile(Icons.Filled.Fullscreen, "Focus mode", focus) { menu = false; focus = !focus; vm.setNotesFocusMode(focus) })
            add(PTile(Icons.Filled.Book, if (useNotebooks) "Notebook" else "Folder") { menu = false; sheet = NoteSheet.Container })
            add(PTile(Icons.Filled.EmojiEmotions, if (d.coverEmoji.isNullOrBlank()) "Cover emoji" else "Change emoji") { menu = false; sheet = NoteSheet.Emoji })
            add(PTile(Icons.Filled.Alarm, "Remind me", d.reminderAt != null) { menu = false; sheet = NoteSheet.Reminder })
            add(PTile(Icons.Filled.History, "History") { menu = false; sheet = NoteSheet.History })
            add(PTile(Icons.Filled.Link, "Related") { menu = false; sheet = NoteSheet.Related })
            add(PTile(Icons.AutoMirrored.Filled.FormatListBulleted, "Outline") { menu = false; sheet = NoteSheet.Outline })
            add(PTile(Icons.Filled.Info, "Note info") { menu = false; sheet = NoteSheet.About })
            add(PTile(Icons.Filled.SwapVert, "Reorder") { menu = false; sheet = NoteSheet.Reorder })
            add(PTile(Icons.Filled.Tune, "Properties") { menu = false; sheet = NoteSheet.Props })
            add(PTile(Icons.Filled.Dashboard, "Template") { menu = false; sheet = NoteSheet.Template })
            if (boxCount > 0) add(PTile(Icons.Filled.CheckBox, "Extract tasks") {
                menu = false
                vm.extractNoteCheckboxes(noteId) { n -> android.widget.Toast.makeText(ctx, "Added $n task${if (n == 1) "" else "s"} to Inbox", android.widget.Toast.LENGTH_SHORT).show() }
            })
            add(PTile(Icons.Filled.ContentCopy, "Duplicate") { menu = false; draft?.let { vm.closeNoteEditor(it) }; vm.duplicateNote(noteId) { id -> onOpenNote(id) } })
            add(PTile(Icons.Filled.FileDownload, "Export") { menu = false; sheet = NoteSheet.Export })
            if (d.kind == "journal" && d.dayEpoch != null) add(PTile(Icons.Filled.Autorenew, "Insert digest") {
                menu = false; scope.launch { val md = vm.dayDigestMarkdown(d.dayEpoch!!); persist(d.copy(body = md + "\n" + d.body)) }
            })
            // Wave 2 · Meeting Mode — a meeting note pulls its event's agenda block; timer via the reading
            // bar; action items via "Extract tasks" above.
            if (d.kind == "meeting" && d.linkedEventId != null) add(PTile(Icons.Filled.Groups, "Insert meeting agenda") {
                menu = false; scope.launch { val md = vm.eventAgendaMarkdown(d.linkedEventId!!); if (md.isNotBlank()) persist(d.copy(body = if (d.body.isBlank()) md else md + "\n" + d.body)) }
            })
            // Wave 2 · Evergreen Resurfacing — a spaced-review cadence that brings this note back deliberately.
            add(PTile(Icons.Filled.Loop, if (d.reviewEvery > 0) "Review · ${com.todocompanion.app.domain.NoteReview.label(d.reviewEvery)}" else "Resurface", d.reviewEvery > 0) { menu = false; showReview = true })
            // Wave 2 · Writing Sprints — a timed word-goal sprint, logged as tracked time on this note.
            if (sprintStart == null) add(PTile(Icons.Filled.Timer, "Writing sprint") { menu = false; showSprintStart = true })
            // Wave 2 · Threads (Maps of Content) — add this note into an ordered reading thread.
            if (d.kind != com.todocompanion.app.domain.NoteThreads.KIND) add(PTile(Icons.AutoMirrored.Filled.List, "Add to thread") { menu = false; showAddThread = true })
            // Wave 3 · Encrypted Note Courier — hand this note to someone end-to-end, no server.
            add(PTile(Icons.Filled.Lock, "Send encrypted") { menu = false; showCourier = true })
            // Wave 2 · Privacy Governance Dial — per-note exclude-from-backup/export/index + notebook auto-vault.
            add(PTile(Icons.Filled.Lock, "Privacy", d.noBackup || d.noExport || d.noIndex) { menu = false; showPrivacy = true })
            add(PTile(if (sealed) Icons.Filled.LockOpen else Icons.Filled.Lock, if (sealed) "Unschedule reveal" else "Schedule reveal") {
                if (sealed) { vm.unsealNote(noteId); draft = d.copy(sealedUntil = null, reminderAt = null) } else { menu = false; sheet = NoteSheet.Seal }
            })
            // L11 — Vault: real encryption at rest (portable, passphrase-derived), distinct from the
            // time-lock "Schedule reveal". Enabling needs the vault set up + unlocked this session.
            add(PTile(if (d.vault) Icons.Filled.LockOpen else Icons.Filled.Lock, if (d.vault) "Remove from Vault" else "Move to Vault") {
                menu = false
                when {
                    d.vault -> draft = d.copy(vault = false)                 // body already decrypted in the editor
                    vm.vaultConfigured() && vaultUnlocked -> draft = d.copy(vault = true)
                    else -> vaultDialog = true                               // set up / unlock, then flag on success
                }
            })
            // Toggle, not one-way: an archived note opened from the Archive filter must be able to come
            // back out — matching how lists/folders/countdowns expose Unarchive.
            add(PTile(if (d.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                if (d.archived) "Unarchive" else "Archive") { menu = false; vm.archiveNote(noteId, !d.archived); onBack() })
            add(PTile(Icons.Filled.Delete, "Move to trash", danger = true) { menu = false; vm.trashNote(noteId); onBack() })
            add(PTile(Icons.Filled.DeleteForever, "Delete", danger = true) { menu = false; sheet = NoteSheet.Delete })
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
                        onClick = { menu = false; sheet = NoteSheet.Tags },
                        label = { Text(if (myTagIds.isEmpty()) "Add tag" else "${myTagIds.size} tag${if (myTagIds.size == 1) "" else "s"}") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)) },
                    )
                    com.todocompanion.app.ui.components.AppColorPicker(current = d.colorArgb, onPick = { persist(d.copy(colorArgb = it)) }, allowNone = true)
                    Text(if (d.colorArgb == null) "Add colour" else "Colour", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                // One reusable 4-up grid renderer for a set of tiles.
                @Composable
                fun tileGrid(items: List<PTile>) = items.chunked(4).forEach { rowTiles ->
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
                // Destructive actions (Move to trash · Delete) are pulled into their own labelled group so
                // they can't be mis-tapped among the everyday actions. Archive stays above — it's reversible.
                val (danger, safe) = tiles.partition { it.danger }
                tileGrid(safe)
                if (danger.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Danger zone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 20.dp, bottom = 2.dp))
                    tileGrid(danger)
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

    if (sheet == NoteSheet.Emoji) {
        AlertDialog(
            onDismissRequest = { sheet = null },
            confirmButton = { TextButton(onClick = { sheet = null }) { Text("Done") } },
            dismissButton = {
                if (!d.coverEmoji.isNullOrBlank())
                    TextButton(onClick = { persist(d.copy(coverEmoji = null)); sheet = null }) { Text("Remove") }
            },
            title = { Text("Cover emoji") },
            text = { EmojiGridPicker(current = d.coverEmoji) { picked -> persist(d.copy(coverEmoji = picked)); sheet = null } },
        )
    }
    // Tags dialog (opened from the "＋ Add tag" corner): toggle existing tags or create a new one.
    if (sheet == NoteSheet.Tags) {
        var newTag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { sheet = null },
            confirmButton = { TextButton(onClick = { sheet = null }) { Text("Done") } },
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
    // Contexts dialog — mirrors Tags exactly (toggle existing or create-and-assign), so a note's @contexts
    // are managed the same way as a task's.
    if (sheet == NoteSheet.Contexts) {
        var newCtx by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { sheet = null },
            confirmButton = { TextButton(onClick = { sheet = null }) { Text("Done") } },
            title = { Text("Contexts") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = newCtx, onValueChange = { newCtx = it },
                            singleLine = true, placeholder = { Text("New context") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = newCtx.isNotBlank(),
                            onClick = { val name = newCtx.trim(); newCtx = ""; if (name.isNotBlank()) vm.createAndAssignNoteContext(noteId, name, myContextIds.toList()) },
                        ) { Text("Add") }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(contexts, key = { it.id }) { c ->
                            val on = c.id in myContextIds
                            DropdownRow("@${c.name}", selected = on) {
                                vm.setNoteContexts(noteId, (if (on) myContextIds - c.id else myContextIds + c.id).toList())
                            }
                        }
                    }
                }
            },
        )
    }
    if (sheet == NoteSheet.Container) {
        AlertDialog(
            onDismissRequest = { sheet = null },
            confirmButton = { TextButton(onClick = { sheet = null }) { Text("Done") } },
            title = { Text(if (useNotebooks) "Notebook" else "Folder") },
            text = {
                LazyColumn {
                    item {
                        DropdownRow("None", selected = (if (useNotebooks) d.notebookId else d.folderId) == null) {
                            persist(if (useNotebooks) d.copy(notebookId = null) else d.copy(folderId = null)); sheet = null
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
                                        .clickable { persist(d.copy(notebookId = nb.id)); sheet = null }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    color = if (d.notebookId == nb.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                // Default IconButton size (48dp) keeps these at the a11y minimum touch target.
                                // Close the container sheet first so the rename/delete dialog doesn't stack on it.
                                IconButton(onClick = { sheet = null; renameNotebook = nb }) {
                                    Icon(Icons.Filled.Edit, "Rename notebook ${nb.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { sheet = null; deleteNotebookAsk = nb }) {
                                    Icon(Icons.Filled.Delete, "Delete notebook ${nb.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            DropdownRow("＋  New notebook…", selected = false) { sheet = NoteSheet.NewNotebook }
                        }
                    } else items(folders, key = { it.id }) { f ->
                        DropdownRow((f.icon?.let { "$it " } ?: "") + f.name, selected = d.folderId == f.id) {
                            persist(d.copy(folderId = f.id)); sheet = null
                        }
                    }
                }
            },
        )
    }
    if (sheet == NoteSheet.NewNotebook) {
        var nbName by remember { mutableStateOf("") }
        var nbIcon by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { sheet = null },
            confirmButton = {
                TextButton(
                    enabled = nbName.isNotBlank(),
                    onClick = {
                        val name = nbName
                        sheet = null
                        vm.createNotebook(name, nbIcon) { id -> persist(d.copy(notebookId = id)) }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Cancel") } },
            title = { Text("New notebook") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = nbName, onValueChange = { nbName = it },
                        singleLine = true, placeholder = { Text("Notebook name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Icon (optional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Same emoji grid the rest of the app uses (cover emoji, folders, habits…).
                    EmojiGridPicker(current = nbIcon) { nbIcon = it }
                }
            },
        )
    }
    renameNotebook?.let { nb ->
        var newName by remember(nb.id) { mutableStateOf(nb.name) }
        var newIcon by remember(nb.id) { mutableStateOf(nb.icon) }
        AlertDialog(
            onDismissRequest = { renameNotebook = null },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    vm.saveNotebook(nb.id, newName.trim(), newIcon, nb.colorArgb); renameNotebook = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameNotebook = null }) { Text("Cancel") } },
            title = { Text("Edit notebook") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        singleLine = true, placeholder = { Text("Notebook name") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Icon (optional)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EmojiGridPicker(current = newIcon) { newIcon = it }
                }
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
    if (sheet == NoteSheet.Export) AlertDialog(
        onDismissRequest = { sheet = null },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { sheet = null }) { Text("Cancel") } },
        title = { Text("Export note") },
        text = {
            Column {
                com.todocompanion.app.util.NoteExport.Format.entries.forEach { fmt ->
                    Text(fmt.label, Modifier.fillMaxWidth().clickable {
                        sheet = null
                        if (fmt == com.todocompanion.app.util.NoteExport.Format.PDF)
                            // Expand live tokens + the recap marker first, so the printed PDF matches the
                            // reading view instead of showing raw {{…}} / recap placeholders.
                            scope.launch { com.todocompanion.app.util.NoteExport.printPdf(ctx, d.copy(body = vm.expandNoteForExport(d))) }
                        else vm.exportNote(noteId, fmt)
                    }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
    )
    if (sheet == NoteSheet.Seal) {
        val nowMs = System.currentTimeMillis()
        val presets = listOf(
            "In a week" to nowMs + 7L * 86_400_000L,
            "In a month" to nowMs + 30L * 86_400_000L,
            "In 3 months" to nowMs + 90L * 86_400_000L,
            "In a year" to nowMs + 365L * 86_400_000L,
        )
        AlertDialog(
            onDismissRequest = { sheet = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Cancel") } },
            title = { Text("Seal to the future") },
            text = {
                Column {
                    Text("Hide this note until the date you choose — then a reminder brings it back.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp))
                    presets.forEach { (label, at) ->
                        Text(label, Modifier.fillMaxWidth().clickable {
                            sheet = null; vm.sealNote(noteId, at); draft = d.copy(sealedUntil = at, reminderAt = at); onBack()
                        }.padding(vertical = 11.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
        )
    }
    if (sheet == NoteSheet.About) NoteAboutDialog(d, onDismiss = { sheet = null })
    // L14 — handwriting: rasterise the drawing to a note image attachment and append its Markdown ref.
    if (showInk) InkPadDialog(
        onSave = { png, caption -> vm.addInkToNote(noteId, png, caption) { ref -> if (ref != null) persist(d.copy(body = if (d.body.isBlank()) ref else d.body.trimEnd() + "\n\n" + ref)) }; showInk = false },
        onDismiss = { showInk = false },
    )
    // Wave 2 — Evergreen review cadence + Writing sprint start.
    if (showReview) ReviewCadenceDialog(current = d.reviewEvery, onPick = { days -> vm.setNoteReview(noteId, days); draft = d.copy(reviewEvery = days, lastReviewedAt = if (days > 0 && d.lastReviewedAt == 0L) System.currentTimeMillis() else d.lastReviewedAt); showReview = false }, onDismiss = { showReview = false })
    if (showSprintStart) SprintStartDialog(
        currentGoal = d.wordGoal,
        onStart = { goal ->
            persist(d.copy(wordGoal = goal))
            sprintStartWords = com.todocompanion.app.domain.NoteProperties.strip(d.body).trim().split(Regex("\\s+")).count { it.isNotBlank() }
            sprintNow = System.currentTimeMillis(); sprintStart = System.currentTimeMillis(); showSprintStart = false
        },
        onDismiss = { showSprintStart = false },
    )
    // Wave 2 · Threads — add this note to an existing/new thread (Map of Content).
    if (showAddThread) {
        val memberTitle = d.title.ifBlank { "Untitled" }
        AddToThreadDialog(
            threads = remember(notes, showAddThread) { vm.threadList() }, suggestTitle = memberTitle,
            onAdd = { tid -> persist(d); vm.addNoteToThread(tid, memberTitle); showAddThread = false
                android.widget.Toast.makeText(ctx, "Added to thread", android.widget.Toast.LENGTH_SHORT).show() },
            onCreate = { title -> persist(d); showAddThread = false
                draft?.let { n -> vm.closeNoteEditor(n) }; vm.createThread(title, memberTitle) { id -> onOpenNote(id) } },
            onDismiss = { showAddThread = false },
        )
    }
    // Wave 3 · Encrypted Note Courier — passphrase prompt, then share the encrypted file.
    if (showCourier) {
        CourierPassphraseDialog(
            title = "Send this note encrypted", confirmLabel = "Encrypt & share",
            message = "The note is wrapped in a passphrase-encrypted file (AES-GCM). Share it over any channel; the recipient opens it with the same passphrase. Nothing is uploaded.",
            onConfirm = { pass -> vm.sendNoteEncrypted(noteId, pass); showCourier = false },
            onDismiss = { showCourier = false },
        )
    }
    if (showPrivacy) {
        val nb = if (useNotebooks) notebooks.firstOrNull { it.id == d.notebookId } else null
        NotePrivacyDialog(
            noBackup = d.noBackup, noExport = d.noExport, noIndex = d.noIndex,
            notebookAutoVault = nb?.autoVault,
            onChange = { b, e, i -> persist(d.copy(noBackup = b, noExport = e, noIndex = i)) },
            onNotebookAutoVault = { on -> nb?.let { vm.setNotebookAutoVault(it.id, on) } },
            onDismiss = { showPrivacy = false },
        )
    }
    if (sheet == NoteSheet.Outline) NoteOutlineDialog(d.body, onDismiss = { sheet = null })
    if (sheet == NoteSheet.Reorder) SectionReorderDialog(d.body, onApply = { persist(d.copy(body = it)); sheet = null }, onDismiss = { sheet = null })
    if (sheet == NoteSheet.Props) NotePropertiesDialog(d.body, onApply = { persist(d.copy(body = it)); sheet = null }, onDismiss = { sheet = null })
    if (sheet == NoteSheet.Related) {
        val hits = remember(noteId, notes) {
            com.todocompanion.app.domain.NoteRelated.related(noteId, notes.filter { !it.trashed }.map { com.todocompanion.app.domain.NoteRelated.Doc(it.id, it.title, it.body) })
        }
        RelatedNotesDialog(hits, onOpen = { sheet = null; onOpenNote(it) }, onDismiss = { sheet = null })
    }
    if (sheet == NoteSheet.Template) {
        val customTemplates = remember(settings.notesTemplatesJson) { com.todocompanion.app.domain.NoteTemplates.parseCustom(settings.notesTemplatesJson) }
        NoteTemplateDialog(
            custom = customTemplates,
            onPick = { t ->
                val (ti, b) = com.todocompanion.app.domain.NoteTemplates.apply(t)
                // Fresh note → adopt the whole scaffold; existing note → append body only (skip its frontmatter).
                val newBody = if (d.body.isBlank()) b else d.body.trimEnd() + "\n\n" + com.todocompanion.app.domain.NoteProperties.strip(b)
                persist(d.copy(title = if (d.title.isBlank()) ti else d.title, body = newBody)); sheet = null
            },
            onSaveCurrent = { name, emoji -> vm.saveNoteTemplate(name, emoji, d.body) },
            onDelete = { vm.deleteNoteTemplate(it) },
            onDismiss = { sheet = null },
        )
    }
    if (sheet == NoteSheet.Reminder) NoteReminderDialog(
        current = d.reminderAt,
        currentRrule = d.reminderRrule,
        currentExtra = com.todocompanion.app.reminders.AlarmScheduler.parseExtraReminders(d.reminderExtra),
        currentKeep = d.reminderKeep,
        onApply = { at, rrule, extra, keep ->
            vm.setNoteReminder(noteId, at, rrule, extra, keep)
            val extraCsv = extra.filter { it > System.currentTimeMillis() }.sorted().joinToString(",")
            draft = d.copy(reminderAt = at, reminderRrule = rrule?.ifBlank { null }, reminderExtra = extraCsv, reminderKeep = keep)
            sheet = null
        },
        onDismiss = { sheet = null },
    )
    if (sheet == NoteSheet.History) NoteVersionHistoryDialog(
        revisions = revisions,
        onRestore = { r -> vm.restoreNoteRevision(noteId, r.title, r.body); draft = d.copy(title = r.title, body = r.body); sheet = null },
        onDismiss = { sheet = null },
    )
    if (sheet == NoteSheet.Delete) {
        AlertDialog(
            onDismissRequest = { sheet = null },
            confirmButton = { TextButton(onClick = { sheet = null; vm.deleteNote(noteId); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { sheet = null }) { Text("Cancel") } },
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
