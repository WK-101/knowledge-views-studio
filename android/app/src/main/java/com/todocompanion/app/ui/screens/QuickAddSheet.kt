package com.todocompanion.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Schedule
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.QuickAddOptions
import com.todocompanion.app.ui.components.DateReminderSheet
import com.todocompanion.app.ui.components.MiniCheck
import com.todocompanion.app.ui.components.formatDue
import com.todocompanion.app.ui.components.priorityColor

/**
 * TickTick-style quick capture: a borderless title + description, then one calm icon row that offers the
 * SAME first-class options as the editor — date/time/duration/repeat/reminder (the unified Date sheet),
 * priority, tags, contexts, the shared folders+lists selector, and attachments. No drag-handle pill, no
 * chip row: compact yet calm. The title runs through the NL parser.
 *
 * Two hosts share ONE body ([QuickAddBody]):
 *  • [QuickAddSheet]     — in-app, wrapped in a Material [ModalBottomSheet].
 *  • [QuickCapturePanel] — the widget / launcher-shortcut popup, rendered directly in a bottom Surface.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(vm: AppViewModel, initialDue: Long? = null, initialHasTime: Boolean = false, initialText: String = "", onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        QuickAddBody(vm, initialDue, initialHasTime, initialText, onDismiss)
    }
}

/**
 * R68 — the home-screen add-a-task popup that the Quick-add widget and the "Quick add" launcher shortcut
 * fire into (via QuickCaptureActivity), and the same panel used for shared/voice/deep-link captures.
 *
 * It renders the identical [QuickAddBody] but in a plain bottom-anchored [Surface] with its own scrim —
 * NOT a [ModalBottomSheet]. A ModalBottomSheet spins up a SECOND Dialog window; inside the transient,
 * translucent QuickCaptureActivity that second window is what crashed the widget/shortcut the instant it
 * opened. Painting the panel straight into the activity's own window is crash-proof and keeps the whole
 * app from ever coming forward. Tap the dimmed launcher behind it (or Back) to dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickCapturePanel(vm: AppViewModel, initialText: String = "", onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            // Tap-away on the scrim closes the popup (no ripple).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 2.dp,
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth()
                // Swallow taps on the card so they don't fall through to the scrim's dismiss.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            QuickAddBody(vm, initialText = initialText, onDismiss = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickAddBody(vm: AppViewModel, initialDue: Long? = null, initialHasTime: Boolean = false, initialText: String = "", onDismiss: () -> Unit) {
    val lists by vm.lists.collectAsState()
    val folders by vm.folders.collectAsState()
    val tags by vm.tags.collectAsState()
    val contexts by vm.contexts.collectAsState()
    val settings by vm.settings.collectAsState()

    // Title is held as a TextFieldValue so we know the caret position (needed for D3 backspace-clears-chip).
    // `text` is the read alias the rest of the body uses; setText(...) writes it and parks the caret at end.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initialText)) }
    val text = field.text
    fun setText(s: String) { field = androidx.compose.ui.text.input.TextFieldValue(s, androidx.compose.ui.text.TextRange(s.length)) }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(initialDue) }
    var hasTime by remember { mutableStateOf(initialHasTime) }
    var durationMin by remember { mutableStateOf<Int?>(null) }
    var rrule by remember { mutableStateOf<String?>(null) }
    var reminderOffset by remember { mutableStateOf<Int?>(null) }
    var priority by remember { mutableStateOf<PriorityLevel?>(null) }
    var listId by remember { mutableStateOf<String?>(null) }
    var folderId by remember { mutableStateOf<String?>(null) }
    var tagIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var ctxIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var attachments by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var startMillis by remember { mutableStateOf<Long?>(null) }
    var deadlineMillis by remember { mutableStateOf<Long?>(null) }
    // Wave B — reversible parse: "use plain text" keeps the words verbatim, applying no recognised tokens.
    var plainText by remember { mutableStateOf(false) }
    // Wave B (F3) — the capture reminder's anchor (due/start/deadline) and optional place, set in the sheet.
    var reminderAnchor by remember { mutableStateOf("due") }
    var reminderPlace by remember { mutableStateOf<String?>(null) }
    val templates by vm.templates.collectAsState()

    var showDue by remember { mutableStateOf(false) }
    var showPrio by remember { mutableStateOf(false) }
    var listPicker by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var ctxMenu by remember { mutableStateOf(false) }
    // Wave D (N2) — the bulk-paste review sheet: which pasted lines to actually create.
    var bulkConfirm by remember { mutableStateOf(false) }

    val focus = remember { FocusRequester() }

    val qaCtx = androidx.compose.ui.platform.LocalContext.current
    // R45 — attach via SystemPicker (classic Activity startActivityForResult; see util/SystemPickers.kt).
    // Voice capture (F3): dictate a task with the platform speech recognizer and append the result.
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) setText((text.trimEnd() + " " + spoken).trim())
    }
    fun startVoice() = runCatching {
        voiceLauncher.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your task")
        })
    }

    // The options common to a single submit and a bulk-paste submit (everything except the title).
    fun currentOptions() = QuickAddOptions(
        dueMillis = due, priority = priority, listId = listId, tagIds = tagIds,
        note = note.trim(), contextIds = ctxIds, folderId = folderId,
        rrule = rrule, durationMin = durationMin, attachmentUris = attachments,
        startMillis = startMillis, deadlineMillis = deadlineMillis, plainText = plainText,
        // Wave B (F3) — a proper relative/place reminder (re-arms with the dates, F1) instead of a fixed time.
        reminderOffsetMin = reminderOffset, reminderAnchor = reminderAnchor, reminderPlace = reminderPlace,
    )
    fun submit() {
        if (text.isNotBlank()) vm.submitQuickAdd(text, currentOptions())
        onDismiss()
    }

    // Recognised tokens (computed above the field so the D3 backspace handler can reference them).
    val capTok = remember(text) { com.todocompanion.app.domain.nlp.QuickTokens.parse(text, handleActivity = false) }
    val capParsed = remember(capTok.text) { com.todocompanion.app.domain.nlp.QuickAddParser.parse(capTok.text) }
    val capChips = if (plainText) emptyList() else capTok.sources + capParsed.sources
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
        // Title — borderless, with live token highlighting.
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
            if (text.isEmpty()) Text("What would you like to do?",
                color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            BasicTextField(
                value = field, onValueChange = { field = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                    // D3 — Backspace at the very end of the field, when the text ends with a recognised
                    // token, un-parses that whole chip instead of deleting one character. Caret-checked, so
                    // it only fires when you're deleting from the end (typically right after typing a token).
                    if (!plainText && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        ev.key == androidx.compose.ui.input.key.Key.Backspace &&
                        field.selection.collapsed && field.selection.start == field.text.length) {
                        val last = capChips.lastOrNull { it.raw.isNotBlank() && field.text.trimEnd().endsWith(it.raw) }
                        if (last != null) { setText(field.text.trimEnd().removeSuffix(last.raw).trimEnd()); return@onPreviewKeyEvent true }
                    }
                    false
                },
                textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                // Plain-text mode stops highlighting recognised words — nothing is being extracted.
                visualTransformation = if (plainText) androidx.compose.ui.text.input.VisualTransformation.None else QuickAddTransformation,
            )
        }
        // ---------- Bulk paste → many tasks (Wave B): a multi-line paste becomes one task per line ----------
        val lines = remember(text) { text.split('\n').map { it.trim() }.filter { it.isNotBlank() } }
        if (lines.size > 1) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(10.dp))
                    // N2 — review before creating, not fire-on-tap.
                    .clickable { bulkConfirm = true }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Review ${lines.size} lines as tasks…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        // ---------- Inline subtask grammar (Wave D/N3): a one-tap suggestion, not an automatic split ----------
        val subtreeSegs = remember(text, plainText) {
            if (plainText || text.contains('\n')) emptyList()
            else text.trim().split(Regex("\\s*>\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        }
        if (subtreeSegs.size >= 2) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { vm.submitQuickAdd(text, currentOptions().copy(splitSubtasks = true)); onDismiss() }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Break into “${subtreeSegs.first().take(20)}” + ${subtreeSegs.size - 1} subtask${if (subtreeSegs.size - 1 == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        // ---------- Templates at capture (Wave B): type a name to drop a saved template's whole subtree ----------
        val tplMatch = remember(text, templates) {
            val q = text.trim()
            if (q.length >= 2 && !q.contains('\n')) templates.firstOrNull { it.name.trim().equals(q, true) } ?: templates.firstOrNull { it.name.trim().startsWith(q, true) } else null
        }
        if (!plainText && lines.size <= 1 && tplMatch != null) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { vm.insertTemplateHere(tplMatch.id) {}; onDismiss() }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📄", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Text("Use template “${tplMatch.name}”", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        // ---------- Honest capture (P3): a confirm-chip row of what the title parser recognised ----------
        // Each chip is one recognised token; its ✕ removes that token from the title, so what you see
        // always matches what will be applied — the strip is never silent. In plain-text mode nothing is
        // parsed, so the row collapses to a single reversible "plain text" pill (Wave B). capChips is
        // computed above the title field (D3).
        if (plainText) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 10.dp, end = 2.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Aa  Plain text — kept as typed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Filled.Close, "Parse again", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp).size(16.dp).clip(CircleShape).clickable { plainText = false })
                }
            }
        } else if (capChips.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                    // P5 — the chip row grows/shrinks smoothly as tokens are recognised (chip-commit),
                    // unless the viewer has Reduce motion on.
                    .then(if (settings.reduceMotion) Modifier else Modifier.animateContentSize()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                capChips.forEach { tk ->
                    val c = captureChipColor(tk.type)
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = .14f)).padding(start = 8.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tk.label, style = MaterialTheme.typography.labelMedium, color = c)
                        Icon(Icons.Filled.Close, "Remove ${tk.label}", tint = c,
                            modifier = Modifier.padding(start = 2.dp).size(16.dp).clip(CircleShape)
                                .clickable { setText(text.replaceFirst(tk.raw, " ").replace(Regex("\\s{2,}"), " ").trim()) })
                    }
                }
                // Wave B — reversible parse: one tap reverts the whole parse and keeps the words verbatim
                // (Akiflow's ESC / To Do's backspace, made explicit). The ✕ on the pill re-enables parsing.
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { plainText = true }.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⤺ Plain text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // ---------- Trigger-char pickers: #tag / @context / ~list autocomplete while typing ----------
        val trig = remember(text) { if (plainText) null else Regex("([#@~])([\\p{L}0-9_-]*)$").find(text) }
        if (trig != null) {
            val sym = trig.groupValues[1]; val partial = trig.groupValues[2]
            val suggestions: List<String> = when (sym) {
                "#" -> tags.filter { it.name.startsWith(partial, ignoreCase = true) }.map { it.name }
                "@" -> contexts.filter { it.name.startsWith(partial, ignoreCase = true) }.map { it.name }
                else -> lists.filter { !it.archived && it.name.startsWith(partial, ignoreCase = true) }.map { it.name }
            }.filter { it.isNotBlank() }.distinct().take(8)
            if (suggestions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { name ->
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { setText(text.dropLast(partial.length) + name + " ") }) {
                            Text("$sym$name", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                }
            }
        }
        // Description — borderless, muted.
        Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
            if (note.isEmpty()) Text("Description",
                color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = note, onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }

        // Destination cue + (when set) the chosen date / recurrence — one calm, light line.
        val destView by vm.currentView.collectAsState()
        val destText = when {
            folderId != null -> "📁 " + (folders.firstOrNull { it.id == folderId }?.name ?: "Folder")
            listId != null -> lists.firstOrNull { it.id == listId }?.name ?: "List"
            destView is com.todocompanion.app.domain.view.ViewRef.FolderView ->
                "📁 " + (folders.firstOrNull { it.id == (destView as com.todocompanion.app.domain.view.ViewRef.FolderView).folderId }?.name ?: "Folder")
            destView is com.todocompanion.app.domain.view.ViewRef.ListView ->
                lists.firstOrNull { it.id == (destView as com.todocompanion.app.domain.view.ViewRef.ListView).listId }?.name ?: "List"
            else -> "Inbox"
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Adding to $destText", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (due != null) {
                Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text(formatDue(due!!) + (if (rrule != null) " ⟳" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Filled.Close, "Clear date", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 3.dp).size(14.dp).clip(CircleShape).clickable { due = null; hasTime = false; durationMin = null; rrule = null; reminderOffset = null })
            }
        }

        // One calm icon row — tools scroll if the screen is narrow; Send stays pinned right.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                IconTool(Icons.Filled.CalendarMonth, "Date, time, repeat & reminder", due != null || rrule != null) { showDue = true }
                // Opens the SAME PrioritySheet used by the row checkboxes and the editor (P4) — one
                // picker everywhere, instead of a blind cycle you had to tap through.
                IconTool(Icons.Filled.Flag, "Priority", priority != null && priority != PriorityLevel.NONE,
                    tint = priority?.takeIf { it != PriorityLevel.NONE }?.let { priorityColor(it) }) { showPrio = true }
                Box {
                    IconTool(Icons.Filled.Label, "Tags", tagIds.isNotEmpty()) { tagMenu = true }
                    DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                        if (tags.isEmpty()) DropdownMenuItem(text = { Text("No tags yet — type #tag in the title") }, onClick = { tagMenu = false })
                        tags.forEach { t ->
                            val on = t.id in tagIds
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Selected rows lead with the modern mark, not a raw "✓".
                                        if (on) { MiniCheck(); Spacer(Modifier.width(6.dp)) }
                                        Text("#" + t.name)
                                    }
                                },
                                onClick = { tagIds = if (on) tagIds - t.id else tagIds + t.id })
                        }
                    }
                }
                Box {
                    IconTool(Icons.Filled.Place, "Contexts", ctxIds.isNotEmpty()) { ctxMenu = true }
                    DropdownMenu(expanded = ctxMenu, onDismissRequest = { ctxMenu = false }) {
                        if (contexts.isEmpty()) DropdownMenuItem(text = { Text("No contexts yet — type @context in the title") }, onClick = { ctxMenu = false })
                        contexts.forEach { c ->
                            val on = c.id in ctxIds
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (on) { MiniCheck(); Spacer(Modifier.width(6.dp)) }
                                        Text("@" + c.name)
                                    }
                                },
                                onClick = { ctxIds = if (on) ctxIds - c.id else ctxIds + c.id })
                        }
                    }
                }
                IconTool(Icons.AutoMirrored.Filled.FormatListBulleted, "List or folder", listId != null || folderId != null) { listPicker = true }
                // The primary five tools (date, priority, tags, contexts, list) stay visible; the rest live
                // under one overflow so the row never crowds the Send button (5 + ⋯, P3).
                Box {
                    var moreMenu by remember { mutableStateOf(false) }
                    IconTool(Icons.Filled.MoreHoriz, "More options", attachments.isNotEmpty()) { moreMenu = true }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (attachments.isEmpty()) "Attach a file" else "Attach a file (${attachments.size})") },
                            leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
                            onClick = {
                                moreMenu = false
                                com.todocompanion.app.util.SystemPicker.openFiles(arrayOf("*/*"), onError = { android.widget.Toast.makeText(qaCtx, it, android.widget.Toast.LENGTH_LONG).show() }) { uris -> attachments = attachments + uris }
                            })
                        DropdownMenuItem(
                            text = { Text("Dictate task") },
                            leadingIcon = { Icon(Icons.Filled.Mic, null) },
                            onClick = { moreMenu = false; startVoice() })
                        // Moat — time-block from capture: drop the task onto today's time grid using its
                        // duration (default 30m). N8: find the first FREE 30-min-aligned slot from now that
                        // fits, scanning today's existing timed blocks, so two quick blocks don't overlap.
                        DropdownMenuItem(
                            text = { Text("Block on today") },
                            leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                            onClick = {
                                moreMenu = false
                                val z = java.time.ZoneId.systemDefault()
                                val dur = (durationMin ?: 30).toLong()
                                val now = java.time.ZonedDateTime.now(z).withSecond(0).withNano(0)
                                var slot = if (now.minute < 30) now.withMinute(30) else now.plusHours(1).withMinute(0)
                                val today = now.toLocalDate()
                                // Today's occupied windows: due tasks that carry a clock time (+ their duration).
                                val blocks = vm.tasks.value.mapNotNull { t ->
                                    val d = t.dueDate ?: return@mapNotNull null
                                    val zd = java.time.Instant.ofEpochMilli(d).atZone(z)
                                    if (zd.toLocalDate() != today || (zd.hour == 0 && zd.minute == 0)) null
                                    else { val s = d; s to (s + (t.durationMin ?: 30) * 60_000L) }
                                }
                                var guard = 0
                                while (guard++ < 48) {
                                    val s = slot.toInstant().toEpochMilli(); val e = s + dur * 60_000L
                                    if (blocks.none { s < it.second && e > it.first }) break
                                    slot = slot.plusMinutes(30)
                                }
                                due = slot.toInstant().toEpochMilli(); hasTime = true
                                if (durationMin == null) durationMin = 30
                            })
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (text.isBlank()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary).clickable { submit() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Send, "Add", tint = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }

    if (showDue) {
        val zone = java.time.ZoneId.systemDefault()
        val timed = due != null && hasTime && java.time.Instant.ofEpochMilli(due!!).atZone(zone).let { it.hour != 0 || it.minute != 0 }
        val startTimed = startMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).let { z -> z.hour != 0 || z.minute != 0 } } ?: false
        DateReminderSheet(
            initialDue = due, initialHasTime = timed, initialAllDay = due != null && !timed,
            initialDurationMin = durationMin, initialRrule = rrule, initialReminderOffsetMin = reminderOffset,
            onDismiss = { showDue = false },
            onConfirm = { c ->
                due = c.dueMillis; hasTime = c.hasTime; durationMin = c.durationMin; rrule = c.rrule; reminderOffset = c.reminderOffsetMin
                startMillis = c.startMillis; deadlineMillis = c.deadlineMillis
                reminderAnchor = c.reminderAnchor; reminderPlace = c.reminderPlace
                showDue = false
            },
            // P3 — capture surfaces the same start & deadline the editor's schedule sheet does.
            showStart = true, initialStart = startMillis, initialStartHasTime = startTimed,
            showDeadline = true, initialDeadline = deadlineMillis,
            // F3 — capture reminder gains the editor's anchors (due/start/deadline) and a place option.
            showReminderAnchors = true, initialReminderAnchor = reminderAnchor, initialReminderPlace = reminderPlace,
        )
    }
    if (showPrio) com.todocompanion.app.ui.components.PrioritySheet(
        current = priority ?: PriorityLevel.NONE,
        onPick = { priority = it; showPrio = false },
        onDismiss = { showPrio = false },
    )
    if (listPicker) MoveTargetDialog(
        folders = folders, lists = lists.filter { !it.archived },
        pinnedRefs = settings.pinnedRefs, onPinToggle = { vm.togglePinnedRef(it) },
        onPickList = { lid -> listId = lid; folderId = null; listPicker = false },
        onPickFolder = { fid -> folderId = fid; listId = null; listPicker = false },
        onDismiss = { listPicker = false },
    )
    // N2 — bulk-paste review: tick which pasted lines become tasks before creating them (with one Undo).
    if (bulkConfirm) {
        val bulkLines = remember(text) { text.split('\n').map { it.trim() }.filter { it.isNotBlank() } }
        val deselected = remember(text) { androidx.compose.runtime.mutableStateListOf<Int>() }
        val chosen = bulkLines.filterIndexed { i, _ -> i !in deselected }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { bulkConfirm = false },
            title = { Text("Add ${chosen.size} of ${bulkLines.size} lines") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    bulkLines.forEachIndexed { i, line ->
                        Row(Modifier.fillMaxWidth().clickable { if (i in deselected) deselected.remove(i) else deselected.add(i) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = i !in deselected, onCheckedChange = { on -> if (on) deselected.remove(i) else deselected.add(i) })
                            Spacer(Modifier.width(4.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { bulkConfirm = false; if (chosen.isNotEmpty()) { vm.addManyLines(chosen, currentOptions()); onDismiss() } }, enabled = chosen.isNotEmpty()) { Text("Add ${chosen.size}") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { bulkConfirm = false }) { Text("Cancel") } },
        )
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The accent for each recognised-token chip in the confirm row — mirrors the live title highlighting. */
private fun captureChipColor(t: com.todocompanion.app.domain.nlp.ChipType): Color = when (t) {
    com.todocompanion.app.domain.nlp.ChipType.DATE, com.todocompanion.app.domain.nlp.ChipType.TIME -> Color(0xFF2563EB)
    com.todocompanion.app.domain.nlp.ChipType.PRIORITY -> Color(0xFFEA580C)
    com.todocompanion.app.domain.nlp.ChipType.TAG -> Color(0xFF7C3AED)
    com.todocompanion.app.domain.nlp.ChipType.CONTEXT -> Color(0xFFDB2777)
    com.todocompanion.app.domain.nlp.ChipType.LIST -> Color(0xFF0D9488)
    com.todocompanion.app.domain.nlp.ChipType.REMINDER -> Color(0xFF0891B2)
    com.todocompanion.app.domain.nlp.ChipType.RECUR -> Color(0xFF4F46E5)
    com.todocompanion.app.domain.nlp.ChipType.ESTIMATE -> Color(0xFF0891B2)
    com.todocompanion.app.domain.nlp.ChipType.STAR -> Color(0xFFD97706)
}

/** A borderless icon button for the quick-add toolbar. Tinted when active. */
@Composable
private fun IconTool(icon: ImageVector, label: String, on: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val fg = tint ?: if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
    }
}
