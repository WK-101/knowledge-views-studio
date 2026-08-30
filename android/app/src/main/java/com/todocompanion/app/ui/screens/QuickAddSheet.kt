package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.QuickAddOptions
import com.todocompanion.app.ui.components.DateReminderSheet
import com.todocompanion.app.ui.components.formatDue
import com.todocompanion.app.ui.components.priorityColor

/**
 * TickTick-style quick capture: a borderless title + description, then one calm icon row that now
 * offers the SAME first-class options as the editor — date/time/duration/repeat/reminder (the unified
 * Date sheet), priority, tags, contexts, the shared folders+lists selector, and attachments. No
 * drag-handle pill, no chip row: compact yet calm. The title still runs through the NL parser.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(vm: AppViewModel, initialDue: Long? = null, initialHasTime: Boolean = false, initialText: String = "", onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lists by vm.lists.collectAsState()
    val folders by vm.folders.collectAsState()
    val tags by vm.tags.collectAsState()
    val contexts by vm.contexts.collectAsState()
    val settings by vm.settings.collectAsState()

    var text by remember { mutableStateOf(initialText) }
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

    var showDue by remember { mutableStateOf(false) }
    var listPicker by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var ctxMenu by remember { mutableStateOf(false) }

    val focus = remember { FocusRequester() }

    val qaCtx = androidx.compose.ui.platform.LocalContext.current
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachments = attachments + uri
    }
    // Voice capture (F3): dictate a task with the platform speech recognizer and append the result.
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) text = (text.trimEnd() + " " + spoken).trim()
    }
    fun startVoice() = runCatching {
        voiceLauncher.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your task")
        })
    }

    fun submit() {
        if (text.isNotBlank()) {
            // The date sheet's relative reminder → an absolute time (due − offset), the common case.
            val reminderAt = reminderOffset?.let { off -> due?.let { it - off * 60_000L } }
            vm.submitQuickAdd(text, QuickAddOptions(
                dueMillis = due, hasTime = hasTime, priority = priority, listId = listId, tagIds = tagIds,
                reminderMillis = reminderAt, note = note.trim(), contextIds = ctxIds, folderId = folderId,
                rrule = rrule, durationMin = durationMin, attachmentUris = attachments,
            ))
        }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
            // Title — borderless, with live token highlighting.
            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
                if (text.isEmpty()) Text("What would you like to do?",
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    visualTransformation = QuickAddTransformation,
                )
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
                    // Tap to cycle priority (High → Medium → Low → None) — no popup over Send.
                    IconTool(Icons.Filled.Flag, "Priority", priority != null && priority != PriorityLevel.NONE,
                        tint = priority?.takeIf { it != PriorityLevel.NONE }?.let { priorityColor(it) }) {
                        priority = when (priority) {
                            null, PriorityLevel.NONE -> PriorityLevel.HIGH
                            PriorityLevel.HIGH -> PriorityLevel.MEDIUM
                            PriorityLevel.MEDIUM -> PriorityLevel.LOW
                            PriorityLevel.LOW -> PriorityLevel.NONE
                        }
                    }
                    Box {
                        IconTool(Icons.Filled.Label, "Tags", tagIds.isNotEmpty()) { tagMenu = true }
                        DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                            if (tags.isEmpty()) DropdownMenuItem(text = { Text("No tags yet — type #tag in the title") }, onClick = { tagMenu = false })
                            tags.forEach { t ->
                                DropdownMenuItem(text = { Text((if (t.id in tagIds) "✓ " else "") + "#" + t.name) },
                                    onClick = { tagIds = if (t.id in tagIds) tagIds - t.id else tagIds + t.id })
                            }
                        }
                    }
                    Box {
                        IconTool(Icons.Filled.Place, "Contexts", ctxIds.isNotEmpty()) { ctxMenu = true }
                        DropdownMenu(expanded = ctxMenu, onDismissRequest = { ctxMenu = false }) {
                            if (contexts.isEmpty()) DropdownMenuItem(text = { Text("No contexts yet — type @context in the title") }, onClick = { ctxMenu = false })
                            contexts.forEach { c ->
                                DropdownMenuItem(text = { Text((if (c.id in ctxIds) "✓ " else "") + "@" + c.name) },
                                    onClick = { ctxIds = if (c.id in ctxIds) ctxIds - c.id else ctxIds + c.id })
                            }
                        }
                    }
                    IconTool(Icons.AutoMirrored.Filled.FormatListBulleted, "List or folder", listId != null || folderId != null) { listPicker = true }
                    IconTool(Icons.Filled.AttachFile, "Attach a file", attachments.isNotEmpty()) {
                        // Guard against a device with no picker (would otherwise crash); the task editor's
                        // "Browse device" is the reliable fallback for attaching there instead.
                        try { attachLauncher.launch(arrayOf("*/*")) } catch (e: Exception) {
                            android.widget.Toast.makeText(qaCtx, "No file picker here — open the task and use “Browse device” to attach.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    IconTool(Icons.Filled.Mic, "Dictate task", false) { startVoice() }
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (text.isBlank()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary).clickable { submit() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Send, "Add", tint = if (text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showDue) {
        val zone = java.time.ZoneId.systemDefault()
        val timed = due != null && hasTime && java.time.Instant.ofEpochMilli(due!!).atZone(zone).let { it.hour != 0 || it.minute != 0 }
        DateReminderSheet(
            initialDue = due, initialHasTime = timed, initialAllDay = due != null && !timed,
            initialDurationMin = durationMin, initialRrule = rrule, initialReminderOffsetMin = reminderOffset,
            onDismiss = { showDue = false },
            onConfirm = { c ->
                due = c.dueMillis; hasTime = c.hasTime; durationMin = c.durationMin; rrule = c.rrule; reminderOffset = c.reminderOffsetMin
                showDue = false
            },
        )
    }
    if (listPicker) MoveTargetDialog(
        folders = folders, lists = lists.filter { !it.archived },
        pinnedRefs = settings.pinnedRefs, onPinToggle = { vm.togglePinnedRef(it) },
        onPickList = { lid -> listId = lid; folderId = null; listPicker = false },
        onPickFolder = { fid -> folderId = fid; listId = null; listPicker = false },
        onDismiss = { listPicker = false },
    )

    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A borderless icon button for the quick-add toolbar. Tinted when active. */
@Composable
private fun IconTool(icon: ImageVector, label: String, on: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val fg = tint ?: if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(40.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
    }
}
