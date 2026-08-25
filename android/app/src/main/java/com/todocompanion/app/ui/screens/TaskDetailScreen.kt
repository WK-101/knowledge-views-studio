package com.todocompanion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Segment
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.todocompanion.app.ui.components.priorityColor
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import com.todocompanion.app.data.entity.ReminderEntity
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.AppCard
import com.todocompanion.app.ui.components.CardLabel
import com.todocompanion.app.ui.components.DateTimePickerDialog
import com.todocompanion.app.ui.components.formatDue
import com.todocompanion.app.ui.components.formatDueSpan
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(vm: AppViewModel, taskId: String, onBack: () -> Unit, onJustStart: ((String) -> Unit)? = null) {
    val loaded by vm.observeTask(taskId).collectAsState(initial = null)
    var draft by remember(taskId) { mutableStateOf<TaskEntity?>(null) }
    if (draft == null && loaded != null) draft = loaded

    val settings by vm.settings.collectAsState()
    val allTags by vm.tags.collectAsState()
    val allContexts by vm.contexts.collectAsState()
    val allFlags by vm.flags.collectAsState()
    val ttRefs by vm.taskTags.collectAsState()
    val tcRefs by vm.taskContexts.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val checklist by vm.checklist.collectAsState()
    val lists by vm.lists.collectAsState()
    val folders by vm.folders.collectAsState()
    val activityLog by remember(taskId) { vm.taskActivity(taskId) }.collectAsState(initial = emptyList())
    val allDeps by vm.dependencies.collectAsState()
    val allTasks by vm.tasks.collectAsState()

    var showDue by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showDeadline by remember { mutableStateOf(false) }
    var showDuration by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showLocationReminder by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var newContext by remember { mutableStateOf("") }
    var newCheck by remember { mutableStateOf("") }
    var listMenu by remember { mutableStateOf(false) }
    var prioMenu by remember { mutableStateOf(false) }
    var flagMenu by remember { mutableStateOf(false) }
    var showScore by remember { mutableStateOf(false) }
    var showBlockPicker by remember { mutableStateOf(false) }
    var saveTemplate by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var notePreview by remember(taskId) { mutableStateOf(true) }

    // Staged editing: edits mutate the local draft only and are persisted on Save — never on Back.
    var savedSnapshot by remember(taskId) { mutableStateOf<TaskEntity?>(null) }
    if (savedSnapshot == null && loaded != null) savedSnapshot = loaded
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = draft != null && savedSnapshot != null && draft != savedSnapshot

    fun update(block: (TaskEntity) -> TaskEntity) {
        val d = draft ?: return; draft = block(d)
    }
    fun commit() { draft?.let { vm.save(it) }; savedSnapshot = draft; onBack() }
    fun attemptBack() { if (dirty) confirmDiscard = true else onBack() }

    BackHandler { attemptBack() }

    val task = draft
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Task") },
            navigationIcon = { IconButton(onClick = { attemptBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { update { it.copy(star = !it.star) } }) {
                    Icon(if (task?.star == true) Icons.Filled.Star else Icons.Filled.StarBorder, "Star")
                }
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (task?.pinned == true) "Unpin" else "Pin to top") }, onClick = { update { it.copy(pinned = !it.pinned) }; menu = false })
                    DropdownMenuItem(text = { Text(if (task?.isNote == true) "Convert to task" else "Convert to note") }, onClick = { update { it.copy(isNote = !it.isNote) }; menu = false })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { task?.let { vm.duplicateTask(it) }; menu = false; onBack() })
                    DropdownMenuItem(text = { Text("Save as template") }, onClick = { menu = false; saveTemplate = true })
                    DropdownMenuItem(text = { Text("History…") }, leadingIcon = { Icon(Icons.Filled.History, null, modifier = Modifier.size(18.dp)) }, onClick = { menu = false; showHistory = true })
                    if (!task?.rrule.isNullOrBlank()) DropdownMenuItem(text = { Text("Skip this occurrence") }, onClick = { task?.let { vm.skipOccurrence(it) }; menu = false; onBack() })
                    if (!task?.rrule.isNullOrBlank()) DropdownMenuItem(text = { Text("Edit only this occurrence") }, onClick = { task?.let { vm.detachOccurrence(it) { newId -> } }; menu = false })
                    DropdownMenuItem(text = { Text(if (task?.abandoned == true) "Undo won't do" else "Won't do") }, onClick = { update { it.copy(abandoned = !it.abandoned) }; menu = false })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { task?.let { vm.trash(it) }; menu = false; onBack() })
                }
                // Edits are staged; Save persists them (Back offers to discard unsaved changes).
                TextButton(onClick = { commit() }, enabled = dirty) { Text("Save", fontWeight = FontWeight.SemiBold) }
            },
        )
    }) { padding ->
        if (task == null) { Column(Modifier.padding(padding).fillMaxSize()) {}; return@Scaffold }
        val plevel = PriorityLevel.from(task.importance, task.urgency)
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Borderless title with an inline completion checkbox (TickTick-style).
            Row(verticalAlignment = Alignment.Top) {
                com.todocompanion.app.ui.components.PriorityCheckbox(task.completed, plevel, onCheckedChange = {
                    update { it.copy(completed = !it.completed, completedAt = if (!it.completed) System.currentTimeMillis() else null) }
                }, onSetLevel = { lvl -> update { it.copy(importance = lvl.importance, urgency = lvl.urgency) } })
                Spacer(Modifier.width(2.dp))
                BorderlessField(
                    task.title, { v -> update { it.copy(title = v) } }, "Task title",
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                    strikethrough = task.completed,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (task.note.isNotBlank()) {
                    IconButton(onClick = { notePreview = !notePreview }, modifier = Modifier.size(32.dp)) {
                        if (notePreview) Icon(Icons.Outlined.Edit, "Edit notes", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Icon(Icons.Outlined.Visibility, "Preview notes", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (task.note.isNotBlank() && notePreview) {
                // View-only: the note renders as formatted text and only the Edit button (above)
                // switches to editing — tapping the body no longer flips it into an editor.
                com.todocompanion.app.ui.components.MarkdownText(
                    task.note,
                    modifier = Modifier.fillMaxWidth().padding(start = 42.dp, end = 4.dp, bottom = 4.dp),
                )
            } else {
                BorderlessField(
                    task.note, { v -> update { it.copy(note = v) } }, "Notes — Markdown supported",
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().padding(start = 42.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

            // ---------- Progress (compact, unboxed) ----------
            val (doneW, totalW, doneN, totalN) = remember(allTasks, task.id) { projectRollup(task.id, allTasks) }
            val hasChildren = allTasks.any { it.parentId == task.id && !it.trashed }
            if (totalN > 0) {
                val pct = if (totalW > 0) (doneW / totalW) else 0.0
                Column(Modifier.padding(horizontal = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Progress", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(pct * 100).toInt()}% · $doneN of $totalN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.LinearProgressIndicator(progress = { pct.toFloat() }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            } else if ((task.progressPct ?: 0) > 0) {
                Column(Modifier.padding(horizontal = 6.dp)) {
                    var p by remember(task.id, task.progressPct) { mutableStateOf((task.progressPct ?: 0).toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Progress", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${p.toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Slider(value = p, onValueChange = { p = it }, onValueChangeFinished = { update { it.copy(progressPct = p.toInt().takeIf { v -> v > 0 }) } }, valueRange = 0f..100f, steps = 19)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            }

            // "Just start" (C2): lower the barrier — jump straight into a focus session on this task.
            if (onJustStart != null && !task.completed && !task.abandoned) {
                FilledTonalButton(
                    onClick = { onJustStart(task.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Just start — focus now")
                }
                Spacer(Modifier.height(4.dp))
            }

            // ---------- Compact property rows ----------
            val level = PriorityLevel.from(task.importance, task.urgency)
            val zone = java.time.ZoneId.systemDefault()
            val dueOverdue = task.dueDate?.let { it < System.currentTimeMillis() && !task.completed } == true

            PropRow(Icons.Filled.Event, "Date", task.dueDate?.let { formatDueSpan(it, task.durationMin) } ?: "No date",
                valueColor = if (dueOverdue) MaterialTheme.colorScheme.error else if (task.dueDate != null) MaterialTheme.colorScheme.primary else null,
                onClear = if (task.dueDate != null) ({ update { it.copy(dueDate = null, durationMin = null) } }) else null) { showDue = true }
            if (task.dueDate != null || task.startDate != null) {
                PropRow(Icons.Filled.PlayArrow, "Starts", task.startDate?.let { formatDue(it) } ?: "Not set", indent = true,
                    onClear = if (task.startDate != null) ({ update { it.copy(startDate = null) } }) else null) { showStart = true }
                Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("All day", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(checked = task.isAllDay, onCheckedChange = { on -> update { it.copy(isAllDay = on) } })
                }
                MenuRow("Show early", task.leadTimeMin?.let { "${it / 1440}d early" } ?: "Default",
                    listOf<Pair<Int?, String>>(null to "Default (7 days)", 1 to "1 day early", 3 to "3 days early", 7 to "1 week early", 14 to "2 weeks early")) { d -> update { it.copy(leadTimeMin = d?.let { n -> n * 1440 }) } }
                val timed = task.dueDate != null && !task.isAllDay && java.time.Instant.ofEpochMilli(task.dueDate!!).atZone(zone).let { it.hour != 0 || it.minute != 0 }
                if (timed) PropRow(Icons.Filled.Schedule, "Duration", task.durationMin?.let { fmtDuration(it) } ?: "Not set", indent = true,
                    onClear = if (task.durationMin != null) ({ update { it.copy(durationMin = null) } }) else null) { showDuration = true }
            }

            // ---------- Priority & list (core, always shown) ----------
            Box {
                PropRow(Icons.Filled.Flag, "Priority", level.label, valueColor = priorityColor(level)) { prioMenu = true }
                DropdownMenu(expanded = prioMenu, onDismissRequest = { prioMenu = false }) {
                    PriorityLevel.entries.forEach { lvl ->
                        DropdownMenuItem(text = { Text(lvl.label) }, leadingIcon = { Icon(Icons.Filled.Flag, null, tint = priorityColor(lvl), modifier = Modifier.size(18.dp)) },
                            onClick = { update { it.copy(importance = lvl.importance, urgency = lvl.urgency) }; prioMenu = false })
                    }
                }
            }
            if (settings.advancedPriority) {
                Dial("Importance", task.importance) { v -> update { it.copy(importance = v) } }
                Dial("Urgency", task.urgency) { v -> update { it.copy(urgency = v) } }
            }
            // Legible Do-Next score — the thing MLO never shows.
            if (settings.priorityComputed) {
                TextButton(onClick = { showScore = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 40.dp, end = 8.dp, top = 0.dp, bottom = 0.dp)) {
                    Text("Why this priority?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Box {
                // Folder-direct tasks (empty listId) show the folder they live in until moved to a list.
                val where = task.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name?.let { "📁 $it" } }
                    ?: lists.firstOrNull { it.id == task.listId }?.name ?: "Inbox"
                PropRow(Icons.AutoMirrored.Filled.FormatListBulleted, "List", where) { listMenu = true }
                DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                    lists.filter { !it.archived }.forEach { l ->
                        DropdownMenuItem(text = { Text(l.name) }, onClick = { update { it.copy(listId = l.id, folderId = null) }; listMenu = false })
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))

            // ---------- Optional fields — progressive disclosure (#114) ----------
            // Order + per-field visibility come from Settings → Task editor. A field that already
            // holds a value is always shown (its tier is ignored) so nothing you've set can hide.
            val deadlinePassed = task.deadlineDate?.let { it < System.currentTimeMillis() && !task.completed } == true
            val myReminders = reminders.filter { it.taskId == task.id }
            val myCheck = checklist.filter { it.taskId == task.id }.sortedBy { it.sortOrder }
            val attFlow = remember(task.id) { vm.attachmentMeta(task.id) }
            val attachments by attFlow.collectAsState(initial = emptyList())
            val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.addAttachment(task.id, uri) }
            val assignedTags = ttRefs.filter { it.taskId == task.id }.map { it.tagId }.toSet()
            val assignedCtx = tcRefs.filter { it.taskId == task.id }.map { it.contextId }.toSet()
            val myDeps = allDeps.filter { it.taskId == task.id }
            fun hasFieldValue(f: com.todocompanion.app.domain.EditorField): Boolean = when (f) {
                com.todocompanion.app.domain.EditorField.REPEAT -> !task.rrule.isNullOrBlank()
                com.todocompanion.app.domain.EditorField.REMINDERS -> myReminders.isNotEmpty()
                com.todocompanion.app.domain.EditorField.CHECKLIST -> myCheck.isNotEmpty()
                com.todocompanion.app.domain.EditorField.DEADLINE -> task.deadlineDate != null
                com.todocompanion.app.domain.EditorField.ENERGY -> task.energy != null
                com.todocompanion.app.domain.EditorField.FLAG -> task.flagId != null
                com.todocompanion.app.domain.EditorField.ATTACHMENTS -> attachments.isNotEmpty()
                com.todocompanion.app.domain.EditorField.TAGS -> assignedTags.isNotEmpty() || assignedCtx.isNotEmpty()
                com.todocompanion.app.domain.EditorField.BLOCKED -> myDeps.isNotEmpty()
                com.todocompanion.app.domain.EditorField.ACTIVITY -> activityLog.isNotEmpty()
                com.todocompanion.app.domain.EditorField.ADVANCED -> task.estimateMin != null || task.isGoal || task.isProject || task.reviewEveryDays != null || (task.progressPct ?: 0) > 0
            }
            val orderedFields = settings.editorFieldsOrdered()
            var moreExpanded by remember(task.id) { mutableStateOf(false) }
            val anyCollapsed = orderedFields.any { settings.editorTier(it) == com.todocompanion.app.domain.AppSettings.TIER_MORE && !hasFieldValue(it) }
            orderedFields.forEach { f ->
                val tier = settings.editorTier(f)
                val visible = tier == com.todocompanion.app.domain.AppSettings.TIER_ALWAYS || hasFieldValue(f) || (tier == com.todocompanion.app.domain.AppSettings.TIER_MORE && moreExpanded)
                if (!visible) return@forEach
                when (f) {
                    com.todocompanion.app.domain.EditorField.DEADLINE ->
                        // A hard deadline is distinct from the work/plan date: the true drop-dead moment;
                        // the priority engine pulls harder as it nears (see PriorityEngine.dateTerm).
                        PropRow(Icons.Filled.Bolt, "Deadline", task.deadlineDate?.let { formatDue(it) } ?: "None",
                            valueColor = if (deadlinePassed) MaterialTheme.colorScheme.error else if (task.deadlineDate != null) MaterialTheme.colorScheme.tertiary else null,
                            onClear = if (task.deadlineDate != null) ({ update { it.copy(deadlineDate = null) } }) else null) { showDeadline = true }
                    com.todocompanion.app.domain.EditorField.ENERGY ->
                        // Energy tag — surfaced by the "right now" filter so you can match tasks to how you feel.
                        MenuRow("Energy", when (task.energy) { 1 -> "Low"; 2 -> "Medium"; 3 -> "High"; else -> "Any" },
                            listOf<Pair<Int?, String>>(null to "Any", 1 to "Low", 2 to "Medium", 3 to "High")) { e -> update { it.copy(energy = e) } }
                    com.todocompanion.app.domain.EditorField.FLAG ->
                        Box {
                            PropRow(Icons.Filled.Flag, "Flag", allFlags.firstOrNull { it.id == task.flagId }?.name ?: "None", valueColor = task.flagColorArgb?.let { Color(it) }) { flagMenu = true }
                            DropdownMenu(expanded = flagMenu, onDismissRequest = { flagMenu = false }) {
                                DropdownMenuItem(text = { Text("None") }, onClick = { update { it.copy(flagId = null, flagColorArgb = null) }; flagMenu = false })
                                allFlags.forEach { fl ->
                                    DropdownMenuItem(text = { Text(fl.name) }, leadingIcon = { Icon(com.todocompanion.app.ui.components.FlagIcons.vector(fl.icon), null, tint = Color(fl.colorArgb), modifier = Modifier.size(18.dp)) },
                                        onClick = { update { it.copy(flagId = fl.id, flagColorArgb = fl.colorArgb) }; flagMenu = false })
                                }
                            }
                        }
                    com.todocompanion.app.domain.EditorField.REPEAT ->
                        RepeatRow(task.rrule, hasChildren) { rule -> update { it.copy(rrule = rule) } }

                    com.todocompanion.app.domain.EditorField.REMINDERS ->
                     DetailSection("Reminders", if (myReminders.isEmpty()) null else "${myReminders.size}", myReminders.isNotEmpty()) {
                myReminders.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(reminderLabel(r), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { vm.setReminderAnnoying(r, task, !r.annoying) }) {
                            Icon(if (r.annoying) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone, "Persistent alarm", tint = if (r.annoying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.deleteReminder(r, task) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                Box {
                    var addMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { addMenu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add reminder") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Pick a time…") }, onClick = { addMenu = false; showReminder = true })
                        DropdownMenuItem(
                            text = { Text("At a place…") },
                            leadingIcon = { Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(18.dp)) },
                            onClick = { addMenu = false; showLocationReminder = true })
                        if (task.dueDate != null) {
                            HorizontalDivider()
                            listOf(0 to "When due", 10 to "10 min before", 30 to "30 min before", 60 to "1 hour before", 1440 to "1 day before").forEach { (off, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { vm.addRelativeReminder(task, "relativeToDue", off); addMenu = false })
                            }
                        }
                        if (task.startDate != null) {
                            HorizontalDivider()
                            listOf(0 to "When it starts", 60 to "1 hour before start", 1440 to "1 day before start").forEach { (off, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { vm.addRelativeReminder(task, "relativeToStart", off); addMenu = false })
                            }
                        }
                    }
                }
            }

                    com.todocompanion.app.domain.EditorField.CHECKLIST ->
                     DetailSection("Checklist", if (myCheck.isEmpty()) null else "${myCheck.count { it.checked }}/${myCheck.size}", myCheck.isNotEmpty()) {
                myCheck.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.checked, onCheckedChange = { vm.toggleChecklist(item) })
                        Text(item.text, Modifier.weight(1f), color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { vm.deleteChecklistItem(item.id) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                AddInline(newCheck, { newCheck = it }, "Add checklist item") { if (it.isNotBlank()) { vm.addChecklistItem(task.id, it.trim()); newCheck = "" } }
                // Break down (C2): paste several lines at once → one step per line.
                var showBreakdown by remember { mutableStateOf(false) }
                TextButton(onClick = { showBreakdown = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(Icons.Filled.Segment, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Break into steps")
                }
                if (showBreakdown) {
                    var bulk by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showBreakdown = false },
                        confirmButton = { TextButton(onClick = { vm.addChecklistItems(task.id, bulk.lines()); showBreakdown = false }) { Text("Add steps") } },
                        dismissButton = { TextButton(onClick = { showBreakdown = false }) { Text("Cancel") } },
                        title = { Text("Break into steps") },
                        text = {
                            Column {
                                Text("One step per line.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(bulk, { bulk = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = { Text("Draft outline\nGather sources\nWrite first pass\nEdit") })
                            }
                        },
                    )
                }
            }

                    com.todocompanion.app.domain.EditorField.ATTACHMENTS ->
                     DetailSection("Attachments", if (attachments.isEmpty()) null else "${attachments.size}", attachments.isNotEmpty()) {
                attachments.forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable { vm.openAttachment(a.id, a.fileName, a.mime) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (a.isImage) AttachmentThumb(vm, a.id) else {
                            val (fIcon, fTint) = attachmentGlyph(a.mime, a.fileName)
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(fTint.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(fIcon, null, tint = fTint, modifier = Modifier.size(21.dp)) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${attachmentKind(a.mime, a.fileName)} · ${formatBytes(a.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { vm.removeAttachment(a.id) }) { Icon(Icons.Filled.Close, "Remove attachment") }
                    }
                }
                val attachCtx = androidx.compose.ui.platform.LocalContext.current
                TextButton(onClick = { try { pickFile.launch("*/*") } catch (e: Exception) { android.widget.Toast.makeText(attachCtx, "No file manager is available on this device.", android.widget.Toast.LENGTH_LONG).show() } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add file") }
                if (attachments.isEmpty()) Text("Any file up to 25 MB — images, PDF, docs. Stored on-device and in backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

                    com.todocompanion.app.domain.EditorField.TAGS ->
                     DetailSection("Tags & contexts", (assignedTags.size + assignedCtx.size).takeIf { it > 0 }?.toString(), assignedTags.isNotEmpty() || assignedCtx.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allTags.forEach { tag ->
                        FilterChip(selected = tag.id in assignedTags, onClick = {
                            val next = if (tag.id in assignedTags) assignedTags - tag.id else assignedTags + tag.id
                            vm.setTags(task.id, next.toList())
                        }, label = { Text("#" + tag.name) })
                    }
                }
                AddInline(newTag, { newTag = it }, "New tag") { if (it.isNotBlank()) { vm.createTag(it.trim()); newTag = "" } }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allContexts.forEach { c ->
                        FilterChip(selected = c.id in assignedCtx, onClick = {
                            val next = if (c.id in assignedCtx) assignedCtx - c.id else assignedCtx + c.id
                            vm.setContexts(task.id, next.toList())
                        }, label = { Text("@" + c.name) })
                    }
                }
                AddInline(newContext, { newContext = it }, "New context") { if (it.isNotBlank()) { vm.createContext(it.trim()); newContext = "" } }
            }

                    com.todocompanion.app.domain.EditorField.BLOCKED ->
                     DetailSection("Blocked by", myDeps.size.takeIf { it > 0 }?.toString(), myDeps.isNotEmpty()) {
                val byId = allTasks.associateBy { it.id }
                if (myDeps.isEmpty()) Text("Not blocked — this task can be done now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                myDeps.forEach { dep ->
                    val pred = byId[dep.dependsOnTaskId]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (pred?.completed == true) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (pred?.completed == true) Color(0xFF12A594) else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(pred?.title ?: "(deleted task)", Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { vm.removeDependency(dep) }) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
                if (myDeps.size >= 2) {
                    val mode = myDeps.first().mode
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = mode == "AND", onClick = { vm.setDependencyMode(task.id, "AND") }, label = { Text("All must finish") })
                        FilterChip(selected = mode == "OR", onClick = { vm.setDependencyMode(task.id, "OR") }, label = { Text("Any one unblocks") })
                    }
                }
                if (myDeps.isNotEmpty()) {
                    val delay = myDeps.first().delayDays
                    Spacer(Modifier.height(4.dp))
                    Text("Start after", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "No delay", 1 to "1 day", 3 to "3 days", 7 to "1 week").forEach { (d, l) ->
                            FilterChip(selected = delay == d, onClick = { vm.setDependencyDelay(task.id, d) }, label = { Text(l) })
                        }
                    }
                }
                TextButton(onClick = { showBlockPicker = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("＋ Add a blocker") }
            }

                    com.todocompanion.app.domain.EditorField.ACTIVITY ->
                     DetailSection("Activity", activityLog.size.takeIf { it > 0 }?.toString(), false) {
                if (activityLog.isEmpty()) {
                    Text("No activity recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    activityLog.take(40).forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(activityIcon(a.type), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(activityLabel(a.type, a.detail), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(relativeTime(a.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

                    com.todocompanion.app.domain.EditorField.ADVANCED ->
                     DetailSection("More options", null, false) {
                if (totalN == 0) {
                    // Leaf manual progress lives here when not already set/shown above.
                    var p by remember(task.id, task.progressPct) { mutableStateOf((task.progressPct ?: 0).toFloat()) }
                    Text("Manual progress", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Slider(value = p, onValueChange = { p = it }, onValueChangeFinished = { update { it.copy(progressPct = p.toInt().takeIf { v -> v > 0 }) } }, valueRange = 0f..100f, steps = 19)
                }
                Dial("Estimate (min)", (task.estimateMin ?: 0).coerceIn(0, 5)) { v -> update { it.copy(estimateMin = v * 15) } }
                SwitchRow("Mark as goal", task.isGoal) { v -> update { it.copy(isGoal = v) } }
                SwitchRow("Mark as project", task.isProject) { v -> update { it.copy(isProject = v) } }
                if (hasChildren) SwitchRow("Complete subtasks in order", task.completeInOrder) { v -> update { it.copy(completeInOrder = v) } }
                Spacer(Modifier.height(6.dp)); CardLabel("Review cadence")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Off", 1 to "Daily", 7 to "Weekly", 30 to "Monthly", 90 to "Quarterly").forEach { (days, label) ->
                        FilterChip(selected = task.reviewEveryDays == days, onClick = { update { it.copy(reviewEveryDays = days) } }, label = { Text(label) })
                    }
                }
                if (task.reviewEveryDays != null) Text("When due, this task appears in the Weekly review (FAB ▸ Weekly review) under “Due for review”.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (task.reviewEveryDays != null) {
                    val last = task.reviewedAt ?: task.createdAt
                    val dueIn = ((last + task.reviewEveryDays!! * 86_400_000L) - System.currentTimeMillis()) / 86_400_000L
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (dueIn <= 0) "Due for review" else "Next review in ${dueIn}d", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = if (dueIn <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { update { it.copy(reviewedAt = System.currentTimeMillis()) } }) { Text("Mark reviewed") }
                    }
                }
            }
                }
            }
            // Progressive-disclosure toggle: reveals the "More" fields that have no value yet.
            if (anyCollapsed || moreExpanded) {
                TextButton(onClick = { moreExpanded = !moreExpanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Icon(if (moreExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (moreExpanded) "Show fewer fields" else "More fields")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showScore && task != null) {
        val bd = remember(task, allDeps, settings) { vm.explainScore(task) }
        AlertDialog(
            onDismissRequest = { showScore = false },
            confirmButton = { TextButton(onClick = { showScore = false }) { Text("Got it") } },
            title = { Text("Why this priority?") },
            text = {
                Column {
                    Text("How this task's Do-Next score is computed — adjust the weights in Settings → Do-Next priority.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    bd.lines.forEachIndexed { i, (label, value) ->
                        val last = i == bd.lines.lastIndex
                        if (last) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal)
                            Text(value, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
        )
    }
    if (confirmDiscard) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Unsaved changes") },
            text = { Text("You've made changes that haven't been saved. Save them before leaving?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; commit() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        )
    }
    if (showDuration) DurationPickerDialog(task?.durationMin ?: 30, onDismiss = { showDuration = false }) { mins ->
        update { it.copy(durationMin = mins.takeIf { m -> m > 0 }) }; showDuration = false
    }
    if (showDue) DateTimePickerDialog(task?.dueDate, { showDue = false },
        initialDurationMin = task?.durationMin,
        onDuration = { d -> update { it.copy(durationMin = d) } }) { m -> update { it.copy(dueDate = m) }; showDue = false }
    if (showStart) DateTimePickerDialog(task?.startDate, { showStart = false }) { m -> update { it.copy(startDate = m) }; showStart = false }
    if (showDeadline) DateTimePickerDialog(task?.deadlineDate, { showDeadline = false }) { m -> update { it.copy(deadlineDate = m) }; showDeadline = false }
    if (showReminder) DateTimePickerDialog(task?.dueDate ?: System.currentTimeMillis(), { showReminder = false }) { m -> task?.let { vm.addAbsoluteReminder(it, m) }; showReminder = false }
    if (showLocationReminder) task?.let { t ->
        LocationReminderDialog(onDismiss = { showLocationReminder = false }) { lat, lng, radius, place, onEnter ->
            vm.addLocationReminder(t, lat, lng, radius, place, onEnter); showLocationReminder = false
        }
    }
    if (showBlockPicker && task != null) {
        val existing = allDeps.filter { it.taskId == task.id }.map { it.dependsOnTaskId }.toSet()
        val candidates = allTasks.filter { it.id != task.id && it.id !in existing && !it.trashed && it.parentId != task.id }
        BlockerPickerDialog(candidates, onDismiss = { showBlockPicker = false }) { picked ->
            vm.addDependency(task.id, picked); showBlockPicker = false
        }
    }
    if (saveTemplate && task != null) {
        var tplName by remember(task.id) { mutableStateOf(task.title) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { saveTemplate = false },
            confirmButton = { TextButton(onClick = { vm.saveAsTemplate(task.id, tplName.trim()); saveTemplate = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveTemplate = false }) { Text("Cancel") } },
            title = { Text("Save as template") },
            text = {
                Column {
                    Text("Saves this task and its subtree — note, priority, flag, checklist, tags, contexts, recurrence and relative dates — for reuse.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(tplName, { tplName = it }, singleLine = true, label = { Text("Template name") }, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }
    if (showHistory && task != null) {
        val revisions by remember(task.id) { vm.taskRevisions(task.id) }.collectAsState(initial = emptyList())
        AlertDialog(
            onDismissRequest = { showHistory = false },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Done") } },
            title = { Text("History") },
            text = {
                Column {
                    Text("Earlier versions of this task, captured as you edit. Restore any one — your current version is saved first, so it's reversible.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (revisions.isEmpty()) {
                        Text("No earlier versions yet. Edits you make from now on will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                            revisions.forEachIndexed { i, r ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.label.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (i == 0) "Most recent · ${relativeTime(r.at)}" else relativeTime(r.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { vm.restoreRevision(r.id); showHistory = false }) { Text("Restore") }
                                }
                                if (i < revisions.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun BlockerPickerDialog(candidates: List<TaskEntity>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Blocked by which task?") },
        text = {
            if (candidates.isEmpty()) Text("No other tasks to depend on.")
            else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(candidates, key = { it.id }) { t ->
                    Text(t.title, Modifier.fillMaxWidth().clickable { onPick(t.id) }.padding(vertical = 12.dp), maxLines = 1)
                }
            }
        },
    )
}

@Composable
private fun BorderlessField(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier,
    singleLine: Boolean = true, strikethrough: Boolean = false,
) {
    Box(modifier) {
        if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = MaterialTheme.colorScheme.outline))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange,
            textStyle = textStyle.copy(textDecoration = if (strikethrough) androidx.compose.ui.text.style.TextDecoration.LineThrough else androidx.compose.ui.text.style.TextDecoration.None),
            singleLine = singleLine,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RepeatRow(rule: String?, hasChildren: Boolean, onChange: (String?) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { show = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Repeat", Modifier.weight(1f))
        Text(com.todocompanion.app.domain.recurrence.Recurrence.label(rule) ?: "Does not repeat", color = MaterialTheme.colorScheme.primary)
    }
    if (show) RepeatDialog(rule, hasChildren, onDismiss = { show = false }) { onChange(it); show = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatDialog(rule: String?, hasChildren: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val r0 = com.todocompanion.app.domain.recurrence.Recurrence.parse(rule)
    var freq by remember { mutableStateOf(r0?.freq) }   // null = does not repeat
    var interval by remember { mutableStateOf(r0?.interval ?: 1) }
    var days by remember { mutableStateOf(r0?.byDays ?: emptySet<Int>()) }
    // end: 0 never, 1 until, 2 count
    var endMode by remember { mutableStateOf(if (r0?.untilEpochDay != null) 1 else if (r0?.count != null) 2 else 0) }
    var until by remember { mutableStateOf(r0?.untilEpochDay ?: java.time.LocalDate.now().plusMonths(3).toEpochDay()) }
    var count by remember { mutableStateOf(r0?.count ?: 10) }
    var showUntil by remember { mutableStateOf(false) }
    // Monthly mode: 0 day-of-month, 1 nth weekday, 2 first working day. + regenerate-from-completion.
    var monthMode by remember { mutableStateOf(if (r0?.firstWorkday == true) 2 else if (r0?.bySetPos != null && r0.byWeekday != null) 1 else 0) }
    var pos by remember { mutableStateOf(r0?.bySetPos ?: 1) }
    var weekday by remember { mutableStateOf(r0?.byWeekday ?: 1) }
    var fromCompletion by remember { mutableStateOf(r0?.fromCompletion ?: false) }
    var subtaskReset by remember { mutableStateOf(r0?.subtaskReset ?: "all") }

    fun build(): String? {
        val f = freq ?: return null
        val isMonthly = f == com.todocompanion.app.domain.recurrence.Freq.MONTHLY
        return com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(
                freq = f, interval = interval.coerceAtLeast(1),
                byDays = if (f == com.todocompanion.app.domain.recurrence.Freq.WEEKLY) days else emptySet(),
                bySetPos = if (isMonthly && monthMode == 1) pos else null,
                byWeekday = if (isMonthly && monthMode == 1) weekday else null,
                firstWorkday = isMonthly && monthMode == 2,
                fromCompletion = fromCompletion,
                subtaskReset = subtaskReset,
                untilEpochDay = if (endMode == 1) until else null,
                count = if (endMode == 2) count.coerceAtLeast(1) else null,
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Repeat") },
        text = {
            Column {
                val freqs = listOf<Pair<com.todocompanion.app.domain.recurrence.Freq?, String>>(
                    null to "None",
                    com.todocompanion.app.domain.recurrence.Freq.DAILY to "Daily",
                    com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS to "Weekday",
                    com.todocompanion.app.domain.recurrence.Freq.WEEKLY to "Weekly",
                    com.todocompanion.app.domain.recurrence.Freq.MONTHLY to "Monthly",
                    com.todocompanion.app.domain.recurrence.Freq.YEARLY to "Yearly",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freqs.forEach { (f, l) -> FilterChip(selected = freq == f, onClick = { freq = f }, label = { Text(l) }) }
                }
                if (freq != null && freq != com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS) {
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", Modifier.padding(end = 8.dp))
                        Stepper(interval) { interval = it.coerceIn(1, 99) }
                    }
                }
                if (freq == com.todocompanion.app.domain.recurrence.Freq.WEEKLY) {
                    Spacer(Modifier.size(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (d, l) ->
                            FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(l) })
                        }
                    }
                }
                if (freq == com.todocompanion.app.domain.recurrence.Freq.MONTHLY) {
                    Spacer(Modifier.size(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = monthMode == 0, onClick = { monthMode = 0 }, label = { Text("On day of month") })
                        FilterChip(selected = monthMode == 1, onClick = { monthMode = 1 }, label = { Text("On a weekday") })
                        FilterChip(selected = monthMode == 2, onClick = { monthMode = 2 }, label = { Text("First working day") })
                    }
                    if (monthMode == 1) {
                        Spacer(Modifier.size(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", -1 to "Last").forEach { (p, l) ->
                                FilterChip(selected = pos == p, onClick = { pos = p }, label = { Text(l) })
                            }
                        }
                        Spacer(Modifier.size(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S").forEach { (d, l) ->
                                FilterChip(selected = weekday == d, onClick = { weekday = d }, label = { Text(l) })
                            }
                        }
                    }
                }
                if (freq != null) {
                    Spacer(Modifier.size(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Repeat after completion", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = fromCompletion, onCheckedChange = { fromCompletion = it })
                    }
                }
                if (freq != null && hasChildren) {
                    Spacer(Modifier.size(10.dp)); Text("Subtasks each cycle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "Reset all", "allDone" to "Only if all done", "keep" to "Keep").forEach { (k, l) ->
                            FilterChip(selected = subtaskReset == k, onClick = { subtaskReset = k }, label = { Text(l) })
                        }
                    }
                }
                if (freq != null) {
                    Spacer(Modifier.size(12.dp)); Text("Ends", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Never", 1 to "On date", 2 to "After N").forEachIndexed { _, (m, l) ->
                            FilterChip(selected = endMode == m, onClick = { endMode = m }, label = { Text(l) })
                        }
                    }
                    if (endMode == 1) TextButton(onClick = { showUntil = true }) { Text("Until " + java.time.LocalDate.ofEpochDay(until)) }
                    if (endMode == 2) Row(verticalAlignment = Alignment.CenterVertically) { Text("After", Modifier.padding(end = 8.dp)); Stepper(count) { count = it.coerceIn(1, 999) }; Text(" times", Modifier.padding(start = 6.dp)) }
                }
            }
        },
    )
    if (showUntil) {
        val z = java.time.ZoneId.systemDefault()
        DateTimePickerDialog(java.time.LocalDate.ofEpochDay(until).atStartOfDay(z).toInstant().toEpochMilli(), { showUntil = false }) { m ->
            until = java.time.Instant.ofEpochMilli(m).atZone(z).toLocalDate().toEpochDay(); showUntil = false
        }
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(value - 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) { Text("−") }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange(value + 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) { Text("+") }
    }
}

private fun offsetLabel(min: Int?): String {
    val m = min ?: 0
    return when {
        m % 1440 == 0 && m != 0 -> "${m / 1440} day${if (m / 1440 == 1) "" else "s"}"
        m % 60 == 0 && m != 0 -> "${m / 60} hour${if (m / 60 == 1) "" else "s"}"
        else -> "$m min"
    }
}

private fun reminderLabel(r: ReminderEntity): String = when (r.type) {
    "absolute" -> r.atTime?.let { formatDue(it) } ?: "Reminder"
    "relativeToDue" -> if ((r.offsetMin ?: 0) == 0) "When due" else "${offsetLabel(r.offsetMin)} before due"
    "relativeToStart" -> if ((r.offsetMin ?: 0) == 0) "When it starts" else "${offsetLabel(r.offsetMin)} before start"
    "location" -> (if (r.onEnter) "Arrive: " else "Leave: ") + (r.placeName ?: "a place")
    else -> r.type
}

/** Add a geofence-style reminder from the device's current location. Fully on-device — no maps,
 *  no network. Asks for location permission, grabs the last known fix, and lets the user tune it. */
@Composable
private fun LocationReminderDialog(onDismiss: () -> Unit, onConfirm: (Double, Double, Double, String?, Boolean) -> Unit) {
    val context = LocalContext.current
    var place by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150) }
    var onEnter by remember { mutableStateOf(true) }
    var coords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var status by remember { mutableStateOf("Getting your location…") }

    fun grabLocation() {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { status = "Location permission needed"; return }
        // Instant if a recent fix exists, otherwise actively request one (framework, no Play Services).
        com.todocompanion.app.reminders.LocationFix.lastKnown(context)?.let {
            coords = it; status = "Location captured ✓"; return
        }
        status = "Getting your location…"
        com.todocompanion.app.reminders.LocationFix.requestFix(context) { fix ->
            if (fix != null) { coords = fix; status = "Location captured ✓" }
            else status = "Couldn't get a fix — turn on location and move near a window, then reopen"
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grabLocation() }
    LaunchedEffect(Unit) {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fine || coarse) grabLocation()
        else permLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = coords != null, onClick = {
                coords?.let { (la, ln) -> onConfirm(la, ln, radius.toDouble(), place.ifBlank { null }, onEnter) }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Remind me at a place") },
        text = {
            Column {
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (coords != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(place, { place = it }, label = { Text("Place name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Trigger", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = onEnter, onClick = { onEnter = true }, label = { Text("When I arrive") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !onEnter, onClick = { onEnter = false }, label = { Text("When I leave") })
                }
                Spacer(Modifier.height(12.dp))
                Text("Radius: ${radius} m", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(100, 150, 250, 500, 1000).forEach { r ->
                        FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("${r}m") }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        },
    )
}

/** A compact, unboxed property row (Todoist-style): icon · label · value · chevron. Tapping edits;
 *  an optional clear button appears when the property is set. */
@Composable
private fun PropRow(icon: ImageVector, label: String, value: String?, valueColor: Color? = null, indent: Boolean = false, onClear: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }
            .padding(start = if (indent) 34.dp else 6.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!indent) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(14.dp)) }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        if (onClear != null) IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline) }
        else { Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp)) }
    }
}

/** An indented compact row whose value opens a dropdown of choices. */
@Composable
private fun <T> MenuRow(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(start = 34.dp, end = 4.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(current, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false }) }
        }
    }
}

private fun activityIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    "created" -> Icons.Filled.Add
    "completed" -> Icons.Filled.CheckCircle
    "rescheduled" -> Icons.Filled.Event
    "moved" -> Icons.AutoMirrored.Filled.DriveFileMove
    "trashed" -> Icons.Filled.Delete
    "restored" -> Icons.Filled.Restore
    "wontdo" -> Icons.Filled.Cancel
    else -> Icons.Filled.RadioButtonUnchecked   // reopened
}

private fun activityLabel(type: String, detail: String?): String = when (type) {
    "created" -> "Created"
    "completed" -> "Completed"
    "reopened" -> "Marked not done"
    "rescheduled" -> detail?.toLongOrNull()?.let { ms ->
        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        "Rescheduled to ${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}"
    } ?: "Date cleared"
    "moved" -> "Moved" + (detail?.let { " to $it" } ?: "")
    "trashed" -> "Moved to Trash"
    "restored" -> "Restored"
    "wontdo" -> "Marked won't do"
    else -> type.replaceFirstChar { it.uppercase() }
}

private fun relativeTime(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000L
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        min < 1440 -> "${min / 60}h"
        min < 1440 * 30 -> "${min / 1440}d"
        else -> java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { "${it.dayOfMonth}/${it.monthValue}" }
    }
}

/** A progressive collapsible section: a header with an optional count badge that reveals its body
 *  on tap. Collapsed by default when it has no content, so simple tasks stay short. */
@Composable
private fun DetailSection(title: String, badge: String?, initiallyOpen: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (badge != null) Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 1.dp)) { Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, bottom = 6.dp), content = content)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
    }
}

@Composable
private fun ScheduleRow(name: String, value: Long?, onSet: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        if (value != null) {
            AssistChip(onClick = onSet, label = { Text(formatDue(value)) })
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear") }
        } else TextButton(onClick = onSet) { Text("Set") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Dial(name: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$name: $value", Modifier.width(130.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(1, 5)) }, valueRange = 1f..5f, steps = 3, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddInline(value: String, onValueChange: (String) -> Unit, placeholder: String, onAdd: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BorderlessField(value, onValueChange, placeholder, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.weight(1f))
        if (value.isNotBlank()) TextButton(onClick = { onAdd(value) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text("Add") }
    }
}

/** Small image preview for an attachment, decoded off the main thread and downsampled. */
@Composable
private fun AttachmentThumb(vm: AppViewModel, id: String) {
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        val b64 = vm.attachmentContent(id) ?: return@LaunchedEffect
        bmp = withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Pick an icon + accent for a non-image attachment from its MIME type / extension. */
private fun attachmentGlyph(mime: String, name: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> {
    val ext = name.substringAfterLast('.', "").lowercase()
    val m = mime.lowercase()
    return when {
        m.contains("pdf") || ext == "pdf" -> Icons.Filled.PictureAsPdf to androidx.compose.ui.graphics.Color(0xFFE5484D)
        m.contains("word") || m.contains("msword") || ext in setOf("doc", "docx", "odt", "rtf") -> Icons.AutoMirrored.Filled.Article to androidx.compose.ui.graphics.Color(0xFF2F6BFF)
        m.contains("sheet") || m.contains("excel") || ext in setOf("xls", "xlsx", "ods", "csv") -> Icons.Filled.TableChart to androidx.compose.ui.graphics.Color(0xFF0EA371)
        m.contains("presentation") || m.contains("powerpoint") || ext in setOf("ppt", "pptx", "odp") -> Icons.Filled.Slideshow to androidx.compose.ui.graphics.Color(0xFFEA580C)
        ext == "epub" || m.contains("epub") -> Icons.Filled.MenuBook to androidx.compose.ui.graphics.Color(0xFF8B5CF6)
        m.startsWith("audio") -> Icons.Filled.AudioFile to androidx.compose.ui.graphics.Color(0xFFDB2777)
        m.startsWith("video") -> Icons.Filled.VideoFile to androidx.compose.ui.graphics.Color(0xFF7C3AED)
        m.contains("zip") || m.contains("compressed") || ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Filled.FolderZip to androidx.compose.ui.graphics.Color(0xFFCA8A04)
        m.startsWith("text") || ext in setOf("txt", "md", "log", "json", "xml") -> Icons.AutoMirrored.Filled.TextSnippet to androidx.compose.ui.graphics.Color(0xFF64748B)
        else -> Icons.Filled.InsertDriveFile to androidx.compose.ui.graphics.Color(0xFF64748B)
    }
}

private fun attachmentKind(mime: String, name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return ext.ifBlank { mime.substringAfterLast('/', "file").uppercase() }
}

private data class Rollup(val doneWeight: Double, val totalWeight: Double, val doneCount: Int, val totalCount: Int)

/** Effort-weighted completion across a task's whole subtree. Leaf tasks carry weight = their
 *  estimate (default 15 min); a heavier subtask contributes more, matching MLO's rollup. */
private fun projectRollup(taskId: String, all: List<TaskEntity>): Rollup {
    val byParent = all.filter { !it.trashed }.groupBy { it.parentId }
    var dw = 0.0; var tw = 0.0; var dc = 0; var tc = 0
    fun walk(id: String) {
        val kids = byParent[id].orEmpty()
        for (c in kids) {
            val grandkids = byParent[c.id].orEmpty()
            if (grandkids.isEmpty()) {
                val w = (c.estimateMin ?: 15).coerceAtLeast(1).toDouble()
                tw += w; tc += 1
                if (c.completed) { dw += w; dc += 1 }
            } else walk(c.id)
        }
    }
    walk(taskId)
    return Rollup(dw, tw, dc, tc)
}

private fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

/** Human duration, e.g. 90 → "1h 30m", 45 → "45m", 120 → "2h". */
fun fmtDuration(min: Int): String {
    val h = min / 60; val m = min % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** Flexible duration picker — any hours (0–23) and minutes (0–55, 5-min steps), not fixed presets. */
@Composable
private fun DurationPickerDialog(initialMin: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var hours by remember { mutableStateOf((initialMin / 60).coerceIn(0, 23)) }
    var mins by remember { mutableStateOf((initialMin % 60)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duration") },
        text = {
            Column {
                DurStepper("Hours", hours, 0, 23, 1) { hours = it }
                Spacer(Modifier.height(8.dp))
                DurStepper("Minutes", mins, 0, 55, 5) { mins = it }
                Spacer(Modifier.height(10.dp))
                Text("= ${fmtDuration((hours * 60 + mins).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hours * 60 + mins) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DurStepper(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Icon(Icons.Filled.Remove, "Less") }
        Text("$value", Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Icon(Icons.Filled.Add, "More") }
    }
}
