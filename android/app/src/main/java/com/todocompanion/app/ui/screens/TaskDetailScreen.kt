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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.TrendingUp
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
import com.todocompanion.app.ui.theme.LocalKairoColors
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import com.todocompanion.app.ui.components.OptionChips
import com.todocompanion.app.ui.components.Stepper
import com.todocompanion.app.ui.components.DateTimePickerDialog
import com.todocompanion.app.ui.components.formatDue
import com.todocompanion.app.ui.components.formatDueSpan
import kotlin.math.roundToInt
import com.todocompanion.app.ui.components.appCardColor

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
    val timeEntries by vm.timeEntries.collectAsState()   // T2
    val timeActivities by vm.timeActivities.collectAsState()

    var showDue by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showDuration by remember { mutableStateOf(false) }
    var showEstimate by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var editActivity by remember { mutableStateOf<com.todocompanion.app.data.entity.TimeActivityEntity?>(null) }
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

    // R45 — attachments pick through SystemPicker, which launches via MainActivity's classic
    // startActivityForResult (no ActivityOptions bundle — the thing that threw IllegalArgumentException
    // through the Compose registry on the user's ROM). This is exactly how Tasks.org attaches files.
    val pickerCtx = LocalContext.current
    val onPickerError: (String) -> Unit = { msg -> android.widget.Toast.makeText(pickerCtx, msg, android.widget.Toast.LENGTH_LONG).show() }

    // Staged editing: edits mutate the local draft only and are persisted on Save — never on Back.
    var savedSnapshot by remember(taskId) { mutableStateOf<TaskEntity?>(null) }
    if (savedSnapshot == null && loaded != null) savedSnapshot = loaded
    var confirmDiscard by remember { mutableStateOf(false) }

    // Tags & contexts are staged like the rest of the editor (R21 #2): pending edits live in these drafts and
    // only apply on Save, so the Save button lights up when you change them. While clean the drafts stay null
    // and the UI reads straight from the live DB sets — which also sidesteps the flow's async-load race.
    val liveTags = ttRefs.filter { it.taskId == taskId }.map { it.tagId }.toSet()
    val liveCtx = tcRefs.filter { it.taskId == taskId }.map { it.contextId }.toSet()
    var draftTags by remember(taskId) { mutableStateOf<Set<String>?>(null) }
    var draftCtx by remember(taskId) { mutableStateOf<Set<String>?>(null) }
    val effTags = draftTags ?: liveTags
    val effCtx = draftCtx ?: liveCtx
    val tagsDirty = draftTags != null && draftTags != liveTags
    val ctxDirty = draftCtx != null && draftCtx != liveCtx
    // Attachments (and camera photos) copy into the DB the instant they're picked — they're auto-saved, not
    // staged in the draft. So adding one never moved the draft and the Save check stayed grey, reading as
    // "there's no way to save." attachBump lights the Save button so the change is acknowledged; because the
    // bytes are already committed, Back needs no discard prompt for an attachment-only change.
    var attachBump by remember(taskId) { mutableIntStateOf(0) }
    var pendingDeleteAtt by remember(taskId) { mutableStateOf<com.todocompanion.app.data.entity.AttachmentMeta?>(null) }
    val contentDirty = (draft != null && savedSnapshot != null && draft != savedSnapshot) || tagsDirty || ctxDirty
    val dirty = contentDirty || attachBump > 0

    fun update(block: (TaskEntity) -> TaskEntity) {
        val d = draft ?: return; draft = block(d)
    }
    fun commit() {
        draft?.let { vm.save(it) }
        if (draftTags != null) vm.setTags(taskId, (draftTags ?: emptySet()).toList())
        if (draftCtx != null) vm.setContexts(taskId, (draftCtx ?: emptySet()).toList())
        savedSnapshot = draft; draftTags = null; draftCtx = null; attachBump = 0; onBack()
    }
    // Only real, still-unsaved content edits warrant a discard prompt; attachments are already on disk.
    fun attemptBack() { if (contentDirty) confirmDiscard = true else onBack() }

    BackHandler { attemptBack() }

    val task = draft
    Scaffold(topBar = {
        TopAppBar(expandedHeight = 52.dp, 
            title = { Text("Task") },
            navigationIcon = { IconButton(onClick = { attemptBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { update { it.copy(star = !it.star) } }) {
                    Icon(if (task?.star == true) Icons.Filled.Star else Icons.Filled.StarBorder, "Star")
                }
                // "Just start — focus now" (C2). When the Time module is on, this lives inside the unified
                // tracking control below (Start ▸ Focus session) so there aren't two tracking entry points
                // (R21 #4); here in the header it only remains as the focus entry when Time is off.
                if (onJustStart != null && task != null && !task.completed && !task.abandoned &&
                    !com.todocompanion.app.domain.Modules.isEnabled(settings, com.todocompanion.app.domain.Modules.TIME)) {
                    IconButton(onClick = { onJustStart(task.id) }) { Icon(Icons.Filled.PlayArrow, "Just start — focus now", tint = MaterialTheme.colorScheme.primary) }
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
                // Iconized (R19 #10) — a check, tinted when there are unsaved edits.
                IconButton(onClick = { commit() }, enabled = dirty) {
                    Icon(Icons.Filled.Check, "Save", tint = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    // Completed: mute the title (no strike-through — a line makes it hard to read/edit, R28).
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold,
                        color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f) else MaterialTheme.colorScheme.onSurface),
                    strikethrough = false,
                    // Long titles wrap onto multiple rows so the whole name stays readable & editable (R21 #8).
                    singleLine = false,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
            }
            // Q2: a goal's "why" leads — the reason it matters, shown the moment you open it.
            if ((task.isGoal || task.isProject) && task.whyText.isNotBlank()) {
                Surface(Modifier.fillMaxWidth().padding(start = 42.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .6f)) {
                    Text("🎯 " + task.whyText, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            // No separate "Notes" heading (R21 #7) — the placeholder itself labels the field, reclaiming a row.
            // The view/edit eye floats at the top-end of the notes area, only when there's a note to preview.
            Box(Modifier.fillMaxWidth().padding(start = 42.dp, end = 4.dp)) {
                if (task.note.isNotBlank() && notePreview) {
                    // View-only: the note renders as formatted text and only the eye button switches to editing —
                    // tapping the body no longer flips it into an editor. Wrapped so the rendered text is
                    // selectable (copy) while reading (R22).
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        com.todocompanion.app.ui.components.MarkdownText(
                            task.note,
                            modifier = Modifier.fillMaxWidth().padding(end = 34.dp, bottom = 4.dp),
                        )
                    }
                } else {
                    BorderlessField(
                        task.note, { v -> update { it.copy(note = v) } }, "Notes — Markdown supported",
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().padding(end = 34.dp),
                    )
                }
                if (task.note.isNotBlank()) {
                    IconButton(onClick = { notePreview = !notePreview }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                        if (notePreview) Icon(Icons.Outlined.Edit, "Edit notes", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Icon(Icons.Outlined.Visibility, "Preview notes", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
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
                    var p by remember(task.id, task.progressPct) { mutableFloatStateOf((task.progressPct ?: 0).toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Progress", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${p.toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ModernSlider(p, 0f..100f, 0, { p = it }, { update { it.copy(progressPct = p.toInt().takeIf { v -> v > 0 }) } }, Modifier.fillMaxWidth())
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            }

            // ("Just start — focus now" is now an icon in the top bar — R19 #10.)

            // ---------- Compact property rows ----------
            val level = PriorityLevel.from(task.importance, task.urgency)
            val zone = java.time.ZoneId.systemDefault()
            val dueOverdue = task.dueDate?.let { it < System.currentTimeMillis() && !task.completed } == true

            PropRow(Icons.Filled.Event, "Date", task.dueDate?.let { formatDueSpan(it, task.durationMin) } ?: "No date",
                valueColor = if (dueOverdue) MaterialTheme.colorScheme.error else if (task.dueDate != null) MaterialTheme.colorScheme.primary else null,
                onClear = if (task.dueDate != null) ({ update { it.copy(dueDate = null, durationMin = null) } }) else null) { showDue = true }
            if (task.dueDate != null || task.startDate != null || task.deadlineDate != null) {
                // Start date, all-day, duration, DEADLINE, repeat and reminders ALL live inside the unified Date
                // sheet now (tap "Date" above) — no duplicated controls out here (R19 #9 / R21 / R22). These are
                // read-only summaries of what's set in the sheet.
                if (task.startDate != null) Text("Starts " + formatDue(task.startDate!!), Modifier.padding(start = 34.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                task.deadlineDate?.let { dl ->
                    val passed = dl < System.currentTimeMillis() && !task.completed
                    Text("⚑ Deadline " + formatDue(dl), Modifier.padding(start = 34.dp), style = MaterialTheme.typography.labelSmall,
                        color = if (passed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                }
                // Distinct from "Starts" (which defers a task by its own start date): this is how far
                // BEFORE the due date the task begins ramping up in urgency / surfacing.
                if (task.dueDate != null) MenuRow("Surface before due", task.leadTimeMin?.let { "${it / 1440}d before" } ?: "Default",
                    listOf<Pair<Int?, String>>(null to "Default (7 days)", 1 to "1 day before", 3 to "3 days before", 7 to "1 week before", 14 to "2 weeks before")) { d -> update { it.copy(leadTimeMin = d?.let { n -> n * 1440 }) } }
            }

            // T2: track time against this task (Time module only). Planned (duration/estimate) vs actual,
            // with a live-ticking clock while running and a picker for which activity the time counts under.
            if (com.todocompanion.app.domain.Modules.isEnabled(settings, com.todocompanion.app.domain.Modules.TIME)) {
                val mine = timeEntries.filter { it.taskId == task.id }
                val running = mine.firstOrNull { it.running }
                // A one-second tick so the running total counts up live (was static before).
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(running?.id) {
                    while (running != null) { nowMs = System.currentTimeMillis(); delay(1000) }
                }
                val trackedMin = mine.sumOf { it.minutes(nowMs) }
                val planned = task.durationMin ?: task.estimateMin
                val linkedAct = timeActivities.firstOrNull { it.id == task.defaultActivityId && !it.archived }
                var actMenu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, tint = if (running != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Time tracked", style = MaterialTheme.typography.bodyMedium)
                        if (running != null) {
                            val secs = ((nowMs - running.startMillis) / 1000).coerceAtLeast(0)
                            Text("● %d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60) + "  · running",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(
                                (if (trackedMin > 0) fmtDuration(trackedMin) else "None yet") +
                                    (planned?.let { " · ${fmtDuration(it)} planned" } ?: "") +
                                    (if (planned != null && trackedMin > planned) "  ⚠ over" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (planned != null && trackedMin > planned) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // One unified tracking control (R21 #4): Stop while running; otherwise a single Start that
                    // offers both plain time tracking and a focus session — no separate focus button elsewhere.
                    if (running != null) {
                        androidx.compose.material3.FilledTonalButton(onClick = { vm.stopTimeTracking() }) {
                            Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop")
                        }
                    } else {
                        Box {
                            var startMenu by remember { mutableStateOf(false) }
                            androidx.compose.material3.FilledTonalButton(onClick = { startMenu = true }) {
                                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("Start")
                                Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = startMenu, onDismissRequest = { startMenu = false }) {
                                DropdownMenuItem(text = { Text("Track time") },
                                    leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)) },
                                    onClick = { vm.startTimeTrackingForTask(task); startMenu = false })
                                if (onJustStart != null && !task.completed && !task.abandoned) {
                                    DropdownMenuItem(text = { Text("Focus session") },
                                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)) },
                                        onClick = { startMenu = false; onJustStart(task.id) })
                                }
                            }
                        }
                    }
                }
                // "Counts under" — pick which time activity this task's tracked time belongs to. Falls back
                // to a shared "Tasks" bucket when unset. (Fixes: no way to link an activity to a task.)
                Row(Modifier.padding(start = 62.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { actMenu = true }.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Counts under: ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text((linkedAct?.emoji?.plus(" ") ?: "") + (linkedAct?.name ?: "Tasks (default)"),
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = actMenu, onDismissRequest = { actMenu = false }) {
                            // The link is STAGED into the draft (R22) — writing straight to the DB was overwritten
                            // by the next Save of the draft (which still held the old value) and the checkmark
                            // never moved. update{} keeps it consistent with every other field and Save persists it.
                            DropdownMenuItem(text = { Text("Tasks (default bucket)") },
                                leadingIcon = { if (task.defaultActivityId == null) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) },
                                onClick = { update { it.copy(defaultActivityId = null) }; actMenu = false })
                            timeActivities.filter { !it.archived }.forEach { a ->
                                // Each activity is editable/removable in place (R21 #3) — the trailing pencil opens
                                // the editor (which also deletes); tapping the row links it to the task.
                                DropdownMenuItem(text = { Text((a.emoji?.plus(" ") ?: "") + a.name) },
                                    leadingIcon = { if (task.defaultActivityId == a.id) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else Spacer(Modifier.width(18.dp)) },
                                    trailingIcon = {
                                        IconButton(onClick = { actMenu = false; editActivity = a }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Outlined.Edit, "Edit activity", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { update { it.copy(defaultActivityId = a.id) }; actMenu = false })
                            }
                        }
                    }
                }
            }

            // ---------- Priority & list (core, always shown) ----------
            Box {
                // Custom priority row: the "why this priority?" explainer is now a small superscript ⓘ next to
                // the label (R22) instead of a space-hungry button below — tapping it opens the score breakdown.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { prioMenu = true }
                        .padding(start = 6.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Priority", style = MaterialTheme.typography.bodyMedium)
                    if (settings.priorityComputed) {
                        IconButton(onClick = { showScore = true }, modifier = Modifier.size(20.dp).offset(y = (-5).dp)) {
                            Icon(Icons.Outlined.Info, "Why this priority?", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(level.label, style = MaterialTheme.typography.bodyMedium, color = priorityColor(level), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                }
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
            run {
                // Folder-direct tasks (empty listId) show the folder they live in until moved to a list.
                // Tapping opens the SAME unified folders+lists selector used everywhere else (R19 #10).
                val where = task.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name?.let { "📁 $it" } }
                    ?: lists.firstOrNull { it.id == task.listId }?.name ?: "Inbox"
                PropRow(Icons.AutoMirrored.Filled.FormatListBulleted, "List", where) { listMenu = true }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))

            // ---------- Optional fields — progressive disclosure (#114) ----------
            // Order + per-field visibility come from Settings → Task editor. A field that already
            // holds a value is always shown (its tier is ignored) so nothing you've set can hide.
            val myReminders = reminders.filter { it.taskId == task.id }
            val myCheck = checklist.filter { it.taskId == task.id }.sortedBy { it.sortOrder }
            val attFlow = remember(task.id) { vm.attachmentMeta(task.id) }
            val attachments by attFlow.collectAsState(initial = emptyList())
            // R43 — attachment pickers (pickFiles / pickPhotos / takePhoto) are hoisted to the top of
            // the composable now, registered unconditionally. See the block near the state declarations.
            // Staged tag/context sets (R21 #2): pending edits if any, else the live DB sets.
            val assignedTags = effTags
            val assignedCtx = effCtx
            val myDeps = allDeps.filter { it.taskId == task.id }
            fun hasFieldValue(f: com.todocompanion.app.domain.EditorField): Boolean = when (f) {
                com.todocompanion.app.domain.EditorField.REPEAT -> !task.rrule.isNullOrBlank()
                com.todocompanion.app.domain.EditorField.REMINDERS -> myReminders.isNotEmpty()
                com.todocompanion.app.domain.EditorField.CHECKLIST -> myCheck.isNotEmpty()
                com.todocompanion.app.domain.EditorField.DEADLINE -> false   // lives in the Date sheet now (R22)
                com.todocompanion.app.domain.EditorField.ENERGY -> task.energy != null
                com.todocompanion.app.domain.EditorField.FLAG -> task.flagId != null
                com.todocompanion.app.domain.EditorField.ATTACHMENTS -> attachments.isNotEmpty()
                com.todocompanion.app.domain.EditorField.TAGS -> assignedTags.isNotEmpty() || assignedCtx.isNotEmpty()
                com.todocompanion.app.domain.EditorField.BLOCKED -> myDeps.isNotEmpty()
                com.todocompanion.app.domain.EditorField.ACTIVITY -> activityLog.isNotEmpty()
                com.todocompanion.app.domain.EditorField.ADVANCED -> task.estimateMin != null || task.isGoal || task.isProject || task.reviewEveryDays != null || (task.progressPct ?: 0) > 0
                com.todocompanion.app.domain.EditorField.REFLECTION -> task.winFlag || !task.outcomeNote.isNullOrBlank() || !task.learnedNote.isNullOrBlank() || !task.praiseQuote.isNullOrBlank() || task.mood != null
            }
            val orderedFields = settings.editorFieldsOrdered()
            var moreExpanded by remember(task.id) { mutableStateOf(false) }
            val anyCollapsed = orderedFields.any { settings.editorTier(it) == com.todocompanion.app.domain.AppSettings.TIER_MORE && !hasFieldValue(it) }
            orderedFields.forEach { f ->
                val tier = settings.editorTier(f)
                val visible = tier == com.todocompanion.app.domain.AppSettings.TIER_ALWAYS || hasFieldValue(f) || (tier == com.todocompanion.app.domain.AppSettings.TIER_MORE && moreExpanded) ||
                    // Reflection still auto-appears on a finished task — unless the user hid it.
                    (f == com.todocompanion.app.domain.EditorField.REFLECTION && task.completed && tier != com.todocompanion.app.domain.AppSettings.TIER_HIDDEN)
                if (!visible) return@forEach
                when (f) {
                    // Deadline is set inside the unified Date sheet now (R22) and summarised under the Date row,
                    // so this standalone field renders nothing.
                    com.todocompanion.app.domain.EditorField.DEADLINE -> {}
                    com.todocompanion.app.domain.EditorField.ENERGY ->
                        // Energy tag — surfaced by the "right now" filter so you can match tasks to how you feel.
                        MenuRow("Energy", when (task.energy) { 1 -> "Low"; 2 -> "Medium"; 3 -> "High"; else -> "Any" },
                            listOf<Pair<Int?, String>>(null to "Any", 1 to "Low", 2 to "Medium", 3 to "High")) { e -> update { it.copy(energy = e) } }
                    com.todocompanion.app.domain.EditorField.FLAG ->
                        Box {
                            // Flag uses the bookmark glyph app-wide (FlagStar / FlagIcons); Priority keeps the flag
                            // glyph. Two different icons so the two rows aren't confused (R27 #5).
                            PropRow(Icons.Filled.Bookmark, "Flag", allFlags.firstOrNull { it.id == task.flagId }?.name ?: "None", valueColor = task.flagColorArgb?.let { Color(it) }) { flagMenu = true }
                            DropdownMenu(expanded = flagMenu, onDismissRequest = { flagMenu = false }) {
                                DropdownMenuItem(text = { Text("None") }, onClick = { update { it.copy(flagId = null, flagColorArgb = null) }; flagMenu = false })
                                allFlags.forEach { fl ->
                                    DropdownMenuItem(text = { Text(fl.name) }, leadingIcon = { Icon(com.todocompanion.app.ui.components.FlagIcons.vector(fl.icon), null, tint = Color(fl.colorArgb), modifier = Modifier.size(18.dp)) },
                                        onClick = { update { it.copy(flagId = fl.id, flagColorArgb = fl.colorArgb) }; flagMenu = false })
                                }
                            }
                        }
                    com.todocompanion.app.domain.EditorField.REPEAT -> {
                        // Repeat is set inside the unified Date sheet now (R19 #9); this section keeps only
                        // the recurrence insight (reliability) for tasks that already repeat.
                        // P1/Q3/Q4: reliability — score, forgiving streak, trend and time-of-day rhythm.
                        val reliability by vm.taskReliability.collectAsState()
                        reliability[task.id]?.let { rel ->
                            val acts by vm.taskActivity(task.id).collectAsState(initial = emptyList())
                            val trend = remember(acts, task.rrule) { com.todocompanion.app.domain.task.TaskReliability.trend(task, acts, System.currentTimeMillis()) }
                            val hours = remember(acts, task.rrule) { com.todocompanion.app.domain.task.TaskReliability.completionHours(task, acts) }
                            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.TrendingUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(14.dp))
                                Text("Reliability", style = MaterialTheme.typography.bodyMedium)
                                trend?.takeIf { it != 0 }?.let { Spacer(Modifier.width(6.dp)); Text(if (it > 0) "▲${it}" else "▼${-it}", style = MaterialTheme.typography.labelSmall, color = if (it > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                                Spacer(Modifier.weight(1f))
                                if (rel.streak >= 2) { Text("🔥 ${rel.streak}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(8.dp)) }
                                val relColor = when { rel.score >= 80 -> MaterialTheme.colorScheme.primary; rel.score >= 50 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error }
                                Text("${rel.score}% · ${rel.kept}/${rel.expected} kept", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = relColor)
                            }
                            if (hours.any { it > 0 }) {
                                val maxH = (hours.maxOrNull() ?: 1).coerceAtLeast(1)
                                Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 4.dp, top = 4.dp, bottom = 8.dp).height(28.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                                    for (h in 0..23) {
                                        val frac = hours[h].toFloat() / maxH
                                        Box(Modifier.weight(1f).height((2 + frac * 24).dp).clip(RoundedCornerShape(2.dp))
                                            .background(if (hours[h] > 0) MaterialTheme.colorScheme.tertiary.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceVariant))
                                    }
                                }
                            }
                            // Q5: chronically missed → the coach offers to ease the cadence, one tap.
                            if (rel.score < 40 && rel.expected >= 5) {
                                val easeCtx = LocalContext.current
                                Surface(Modifier.fillMaxWidth().padding(start = 40.dp, end = 4.dp, bottom = 8.dp), shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .5f)) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Missed a lot lately — make it less frequent?", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        TextButton(onClick = { vm.easeCadence(task) { lbl -> if (lbl != null) android.widget.Toast.makeText(easeCtx, "Now: $lbl", android.widget.Toast.LENGTH_SHORT).show() } }) { Text("Ease") }
                                    }
                                }
                            }
                        }
                    }

                    // Reminders are managed inside the unified Date sheet now (tap "Date"), so nothing
                    // renders here — no duplicated Reminders section in the editor body (R19 #9).
                    com.todocompanion.app.domain.EditorField.REMINDERS -> Unit

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
                                com.todocompanion.app.ui.components.AppTextField(bulk, { bulk = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = { Text("Draft outline\nGather sources\nWrite first pass\nEdit") })
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
                        IconButton(onClick = { pendingDeleteAtt = a }) { Icon(Icons.Filled.Close, "Remove attachment") }
                    }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Each button just invokes the hoisted, top-level picker (registered for the whole
                    // screen's lifetime). The helper runs the layered launch chain and surfaces the real
                    // error if every tier fails — so it never silently dead-ends. See SystemPickers.kt.
                    TextButton(onClick = { com.todocompanion.app.util.SystemPicker.gallery(onError = onPickerError) { uris -> vm.addAttachments(taskId, uris) { n -> if (n > 0) attachBump++ } } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp, 0.dp)) { Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Photo") }
                    TextButton(onClick = { com.todocompanion.app.util.SystemPicker.camera(onError = onPickerError) { uri -> vm.addAttachment(taskId, uri) { ok -> if (ok) attachBump++ } } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp, 0.dp)) { Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Camera") }
                    TextButton(onClick = { com.todocompanion.app.util.SystemPicker.openFiles(arrayOf("*/*"), onError = onPickerError) { uris -> vm.addAttachments(taskId, uris) { n -> if (n > 0) attachBump++ } } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp, 0.dp)) { Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("File") }
                }
                if (attachments.isEmpty()) Text("Photos, camera, or any file type, up to 50 MB — picked through your phone's own picker, so no storage permission is ever asked. Stored on-device and in your backups.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                pendingDeleteAtt?.let { a ->
                    AlertDialog(onDismissRequest = { pendingDeleteAtt = null },
                        icon = { Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error) },
                        confirmButton = { TextButton(onClick = { vm.removeAttachment(a.id); pendingDeleteAtt = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                        dismissButton = { TextButton(onClick = { pendingDeleteAtt = null }) { Text("Cancel") } },
                        title = { Text("Delete attachment?") },
                        text = { Text("“${a.fileName}” will be permanently removed from this task and your backups. This can't be undone.") })
                }
            }

                    com.todocompanion.app.domain.EditorField.TAGS ->
                     DetailSection("Tags & contexts", (assignedTags.size + assignedCtx.size).takeIf { it > 0 }?.toString(), assignedTags.isNotEmpty() || assignedCtx.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allTags.forEach { tag ->
                        // Toggling stages the change into draftTags — Save lights up and persists it (R21 #2).
                        FilterChip(selected = tag.id in assignedTags, onClick = {
                            draftTags = if (tag.id in assignedTags) assignedTags - tag.id else assignedTags + tag.id
                        }, label = { Text("#" + tag.name) })
                    }
                }
                AddInline(newTag, { newTag = it }, "New tag") { if (it.isNotBlank()) { vm.createTag(it.trim()); newTag = "" } }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allContexts.forEach { c ->
                        FilterChip(selected = c.id in assignedCtx, onClick = {
                            draftCtx = if (c.id in assignedCtx) assignedCtx - c.id else assignedCtx + c.id
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
                            tint = if (pred?.completed == true) LocalKairoColors.current.good else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(pred?.title ?: "(deleted task)", Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { vm.removeDependency(dep) }) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
                if (myDeps.size >= 2) {
                    val mode = myDeps.first().mode
                    OptionChips(listOf("AND", "OR"), mode, { vm.setDependencyMode(task.id, it) }, modifier = Modifier.padding(top = 2.dp), spacing = 6) {
                        if (it == "AND") "All must finish" else "Any one unblocks"
                    }
                }
                if (myDeps.isNotEmpty()) {
                    val delay = myDeps.first().delayDays
                    Spacer(Modifier.height(4.dp))
                    Text("Start after", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OptionChips(listOf(0, 1, 3, 7), delay, { vm.setDependencyDelay(task.id, it) }, spacing = 6) {
                        when (it) { 0 -> "No delay"; 1 -> "1 day"; 3 -> "3 days"; else -> "1 week" }
                    }
                }
                TextButton(onClick = { showBlockPicker = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("＋ Add a blocker") }
            }

                    com.todocompanion.app.domain.EditorField.ACTIVITY ->
                     DetailSection("Activity", activityLog.size.takeIf { it > 0 }?.toString(), false) {
                // R23: activity entries are an independent append-only log — any one (including "created")
                // can be deleted with no cascade; each deletion is confirmed first.
                var confirmDel by remember { mutableStateOf<com.todocompanion.app.data.entity.ActivityEntity?>(null) }
                var confirmClear by remember { mutableStateOf(false) }
                if (activityLog.isEmpty()) {
                    Text("No activity recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    activityLog.take(40).forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(activityIcon(a.type), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(activityLabel(a.type, a.detail), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(relativeTime(a.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { confirmDel = a }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, "Delete entry", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    TextButton(onClick = { confirmClear = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(4.dp)); Text("Clear history", color = MaterialTheme.colorScheme.error)
                    }
                }
                confirmDel?.let { a ->
                    AlertDialog(onDismissRequest = { confirmDel = null },
                        confirmButton = { TextButton(onClick = { vm.deleteActivityEntry(a.id); confirmDel = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                        dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("Cancel") } },
                        title = { Text("Delete this entry?") },
                        text = { Text("Removes “${activityLabel(a.type, a.detail)}” from this task's history. Only the log is edited — the task itself is unchanged.") })
                }
                if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
                    confirmButton = { TextButton(onClick = { vm.clearTaskActivity(task.id); confirmClear = false }) { Text("Clear all", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                    title = { Text("Clear activity history?") },
                    text = { Text("Deletes every recorded event for this task, including “created”. The task and its data are untouched — only the history log is cleared.") })
            }

                    com.todocompanion.app.domain.EditorField.ADVANCED ->
                     DetailSection("Estimate, goals & review", null, false) {
                if (totalN == 0) {
                    // Leaf manual progress lives here when not already set/shown above.
                    var p by remember(task.id, task.progressPct) { mutableFloatStateOf((task.progressPct ?: 0).toFloat()) }
                    Text("Manual progress", style = MaterialTheme.typography.bodyMedium)
                    ModernSlider(p, 0f..100f, 0, { p = it }, { update { it.copy(progressPct = p.toInt().takeIf { v -> v > 0 }) } }, Modifier.fillMaxWidth())
                }
                // R43 — Estimate moved into the Date sheet so it sits right beside Duration. Summarised here
                // with a pointer; tap opens the same sheet (via the Date row) where you set both together.
                if (task.estimateMin != null) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Estimate", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(fmtDuration(task.estimateMin!!), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text("  · set it in the Date sheet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SwitchRow("Mark as goal", task.isGoal) { v -> update { it.copy(isGoal = v) } }
                SwitchRow("Mark as project", task.isProject) { v -> update { it.copy(isProject = v) } }
                // R52 — GTD Someday/Maybe: park it out of the active lists; it resurfaces in the weekly review.
                SwitchRow("Someday / Maybe (park it)", task.someday) { v -> update { it.copy(someday = v, dueDate = if (v) null else it.dueDate, startDate = if (v) null else it.startDate) } }
                // Q2: goals & projects get the habit "why" + reward vocabulary.
                if (task.isGoal || task.isProject) {
                    com.todocompanion.app.ui.components.AppTextField(task.whyText, { v -> update { it.copy(whyText = v.take(140)) } }, singleLine = false,
                        label = { Text("Why this matters (shown when you open it)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    com.todocompanion.app.ui.components.AppTextField(task.rewardText, { v -> update { it.copy(rewardText = v.take(80)) } }, singleLine = true,
                        label = { Text("Reward yourself when it's done (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                if (hasChildren) SwitchRow("Complete subtasks in order", task.completeInOrder) { v -> update { it.copy(completeInOrder = v) } }
                Spacer(Modifier.height(6.dp)); CardLabel("Review cadence")
                OptionChips(listOf(null, 1, 7, 30, 90), task.reviewEveryDays, { d -> update { it.copy(reviewEveryDays = d) } }, spacing = 6) {
                    when (it) { null -> "Off"; 1 -> "Daily"; 7 -> "Weekly"; 30 -> "Monthly"; else -> "Quarterly" }
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
                    com.todocompanion.app.domain.EditorField.REFLECTION -> {
                        // R27/R29 #5, R30 #2 — reflection: win, mood, outcome, lesson, praise. A proper
                        // reorderable editor field, now wrapped in the same collapsible DetailSection every other
                        // field uses (fold consistency), and still open by default on a finished task.
                        val hasRefl = task.winFlag || !task.outcomeNote.isNullOrBlank() || !task.learnedNote.isNullOrBlank() || !task.praiseQuote.isNullOrBlank() || task.mood != null
                        DetailSection("Reflection", if (task.winFlag) "★" else null, task.completed || hasRefl) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.EmojiEvents, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Mark this a win", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                FilterChip(selected = task.winFlag, onClick = { update { it.copy(winFlag = !it.winFlag) } },
                                    label = { Text("Win") },
                                    leadingIcon = { Icon(if (task.winFlag) Icons.Filled.Star else Icons.Filled.StarBorder, null, Modifier.size(16.dp)) })
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val moods = listOf(1 to "😞", 2 to "😕", 3 to "🙂", 4 to "😀", 5 to "🤩")
                                moods.forEach { (v, e) ->
                                    val sel = task.mood == v
                                    Text(e, style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.clip(CircleShape)
                                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                            .clickable { update { it.copy(mood = if (sel) null else v) } }
                                            .padding(6.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            com.todocompanion.app.ui.components.AppTextField(task.outcomeNote ?: "", { v -> update { it.copy(outcomeNote = v) } },
                                label = { Text("Outcome / impact") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            com.todocompanion.app.ui.components.AppTextField(task.learnedNote ?: "", { v -> update { it.copy(learnedNote = v) } },
                                label = { Text("What I learned") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            com.todocompanion.app.ui.components.AppTextField(task.praiseQuote ?: "", { v -> update { it.copy(praiseQuote = v) } },
                                label = { Text("Praise / thank-you to remember") }, modifier = Modifier.fillMaxWidth())
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

            // R37 — the task coach: habit-science ports (deferral chain, micro-lesson, value link,
            // recurring-task reliability, ship-it escrow).
            if (task != null) TaskCoachCard(vm, task)

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
    if (showEstimate) DurationPickerDialog(task?.estimateMin ?: 30, onDismiss = { showEstimate = false }) { mins ->
        update { it.copy(estimateMin = mins.takeIf { m -> m > 0 }) }; showEstimate = false
    }
    if (showDue) {
        val t0 = task
        val timed0 = t0?.dueDate != null && !t0.isAllDay && java.time.Instant.ofEpochMilli(t0.dueDate!!).atZone(java.time.ZoneId.systemDefault()).let { it.hour != 0 || it.minute != 0 }
        val startTimed0 = t0?.startDate?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).let { z -> z.hour != 0 || z.minute != 0 } } ?: false
        com.todocompanion.app.ui.components.DateReminderSheet(
            initialDue = t0?.dueDate,
            initialHasTime = timed0,
            initialAllDay = t0?.isAllDay ?: false,
            initialDurationMin = t0?.durationMin,
            initialRrule = t0?.rrule,
            initialReminderOffsetMin = null,
            onDismiss = { showDue = false },
            onConfirm = { c ->
                // The sheet carries the full intended schedule state — due, all-day, duration, repeat, the
                // optional start date (R21), the deadline (R22) AND the effort estimate (R43) — apply it all.
                update { it.copy(dueDate = c.dueMillis, isAllDay = c.allDay, durationMin = c.durationMin, rrule = c.rrule, startDate = c.startMillis, deadlineDate = c.deadlineMillis, estimateMin = if (c.estimateSet) c.estimateMin else it.estimateMin) }
                showDue = false
            },
            // The full reminders manager lives inside the sheet (no separate section outside).
            reminderSlot = t0?.let { tt -> { TaskReminderManager(vm, tt, reminders.filter { it.taskId == tt.id }, onPickTime = { showReminder = true }) } },
            showStart = true,
            initialStart = t0?.startDate,
            initialStartHasTime = startTimed0,
            showDeadline = true,
            initialDeadline = t0?.deadlineDate,
            repeatHasChildren = allTasks.any { it.parentId == t0?.id && !it.trashed },
            showEstimate = true,
            initialEstimateMin = t0?.estimateMin,
            estimateHint = vm.estimateBias.collectAsState().value?.sentence(),
        )
    }
    if (listMenu && task != null) MoveTargetDialog(
        folders = folders, lists = lists.filter { !it.archived },
        pinnedRefs = settings.pinnedRefs, onPinToggle = { vm.togglePinnedRef(it) },
        onPickList = { lid -> update { it.copy(listId = lid, folderId = null) }; listMenu = false },
        onPickFolder = { fid -> update { it.copy(listId = "", folderId = fid) }; listMenu = false },
        onDismiss = { listMenu = false },
    )
    editActivity?.let { act ->
        ActivityEditDialog(act, onDismiss = { editActivity = null },
            onSave = { updated -> vm.updateTimeActivity(updated); editActivity = null },
            onDelete = { vm.deleteTimeActivity(act.id); editActivity = null })
    }
    // In-app file browser fallback for attachments on ROMs with no system picker (R23). R30 #6 — multi-select.
    if (showReminder) DateTimePickerDialog(task?.dueDate ?: System.currentTimeMillis(), { showReminder = false }) { m -> task?.let { vm.addAbsoluteReminder(it, m) }; showReminder = false }
    if (showBlockPicker && task != null) {
        val existing = allDeps.filter { it.taskId == task.id }.map { it.dependsOnTaskId }.toSet()
        val candidates = allTasks.filter { it.id != task.id && it.id !in existing && !it.trashed && it.parentId != task.id }
        BlockerPickerDialog(candidates, onDismiss = { showBlockPicker = false }) { picked ->
            picked.forEach { vm.addDependency(task.id, it) }; showBlockPicker = false
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
                    com.todocompanion.app.ui.components.AppTextField(tplName, { tplName = it }, singleLine = true, label = { Text("Template name") }, modifier = Modifier.fillMaxWidth())
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
private fun BlockerPickerDialog(candidates: List<TaskEntity>, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    // R27 #4: the blocker list can be long, so filter it live by a search box.
    // R29 #6: multi-select — tick several prerequisites and add them all in one go.
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val shown = remember(candidates, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) candidates else candidates.filter { it.title.lowercase().contains(q) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { onConfirm(picked) }) { Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Blocked by which tasks?") },
        text = {
            if (candidates.isEmpty()) Text("No other tasks to depend on.")
            else Column {
                com.todocompanion.app.ui.components.AppTextField(
                    query, { query = it }, singleLine = true,
                    label = { Text("Search tasks") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) Text("No tasks match “${query.trim()}”.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(shown, key = { it.id }) { t ->
                        val sel = t.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (sel) picked - t.id else picked + t.id }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (sel) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                                tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
internal fun RepeatDialog(rule: String?, hasChildren: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    val r0 = com.todocompanion.app.domain.recurrence.Recurrence.parse(rule)
    var freq by remember { mutableStateOf(r0?.freq) }   // null = does not repeat
    var interval by remember { mutableIntStateOf(r0?.interval ?: 1) }
    var days by remember { mutableStateOf(r0?.byDays ?: emptySet<Int>()) }
    // end: 0 never, 1 until, 2 count
    var endMode by remember { mutableIntStateOf(if (r0?.untilEpochDay != null) 1 else if (r0?.count != null) 2 else 0) }
    var until by remember { mutableLongStateOf(r0?.untilEpochDay ?: java.time.LocalDate.now().plusMonths(3).toEpochDay()) }
    var count by remember { mutableIntStateOf(r0?.count ?: 10) }
    var showUntil by remember { mutableStateOf(false) }
    // Monthly mode: 0 day-of-month, 1 nth weekday, 2 first working day. + regenerate-from-completion.
    var monthMode by remember { mutableIntStateOf(if (r0?.firstWorkday == true) 2 else if (r0?.bySetPos != null && r0.byWeekday != null) 1 else 0) }
    var pos by remember { mutableIntStateOf(r0?.bySetPos ?: 1) }
    var weekday by remember { mutableIntStateOf(r0?.byWeekday ?: 1) }
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
                OptionChips(freqs.map { it.first }, freq, { freq = it }, spacing = 6) { f -> freqs.first { it.first == f }.second }
                if (freq != null && freq != com.todocompanion.app.domain.recurrence.Freq.WEEKDAYS) {
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", Modifier.padding(end = 8.dp))
                        Stepper(interval, { interval = it.coerceIn(1, 99) })
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
                    OptionChips(listOf(0, 1, 2), monthMode, { monthMode = it }, spacing = 6) {
                        when (it) { 0 -> "On day of month"; 1 -> "On a weekday"; else -> "First working day" }
                    }
                    if (monthMode == 1) {
                        Spacer(Modifier.size(6.dp))
                        OptionChips(listOf(1, 2, 3, 4, -1), pos, { pos = it }, spacing = 4) {
                            when (it) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; 4 -> "4th"; else -> "Last" }
                        }
                        Spacer(Modifier.size(4.dp))
                        OptionChips(listOf(1, 2, 3, 4, 5, 6, 7), weekday, { weekday = it }, spacing = 4) {
                            when (it) { 1 -> "M"; 2 -> "T"; 3 -> "W"; 4 -> "T"; 5 -> "F"; 6 -> "S"; else -> "S" }
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
                    OptionChips(listOf("all", "allDone", "keep"), subtaskReset, { subtaskReset = it }, spacing = 6) {
                        when (it) { "all" -> "Reset all"; "allDone" -> "Only if all done"; else -> "Keep" }
                    }
                }
                if (freq != null) {
                    Spacer(Modifier.size(12.dp)); Text("Ends", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OptionChips(listOf(0, 1, 2), endMode, { endMode = it }, spacing = 6) {
                        when (it) { 0 -> "Never"; 1 -> "On date"; else -> "After N" }
                    }
                    if (endMode == 1) TextButton(onClick = { showUntil = true }) { Text("Until " + java.time.LocalDate.ofEpochDay(until)) }
                    if (endMode == 2) Row(verticalAlignment = Alignment.CenterVertically) { Text("After", Modifier.padding(end = 8.dp)); Stepper(count, { count = it.coerceIn(1, 999) }); Text(" times", Modifier.padding(start = 6.dp)) }
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

private fun offsetLabel(min: Int?): String {
    val m = min ?: 0
    return when {
        m % 1440 == 0 && m != 0 -> "${m / 1440} day${if (m / 1440 == 1) "" else "s"}"
        m % 60 == 0 && m != 0 -> "${m / 60} hour${if (m / 60 == 1) "" else "s"}"
        else -> "$m min"
    }
}

/** Edit or delete a time activity (name · colour · emoji) straight from the task editor (R19 #10). */
@Composable
private fun ActivityEditDialog(
    activity: com.todocompanion.app.data.entity.TimeActivityEntity,
    onDismiss: () -> Unit,
    onSave: (com.todocompanion.app.data.entity.TimeActivityEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(activity.name) }
    var emoji by remember { mutableStateOf(activity.emoji) }
    var color by remember { mutableStateOf(activity.colorArgb) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(activity.copy(name = name.trim(), emoji = emoji, colorArgb = color)) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit activity") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                com.todocompanion.app.ui.components.AppTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.todocompanion.app.ui.components.AppColorPicker(current = color, onPick = { color = it ?: color })
                }
                Spacer(Modifier.height(8.dp))
                Text("Icon", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.todocompanion.app.ui.components.EmojiGridPicker(emoji) { emoji = it }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Text("Delete activity", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        title = { Text("Delete activity?") },
        text = { Text("Removes \"${activity.name}\" and unlinks it from tasks. Time already tracked under it is kept.") },
    )
}

/** The full reminders manager (list + add presets + remove / persistent-alarm toggle), rendered inside
 *  the unified Date sheet so all scheduling lives in one place (R19 #9). [onPickTime] opens the specific
 *  time picker (the sheet renders above it). */
@Composable
private fun TaskReminderManager(vm: AppViewModel, task: TaskEntity, myReminders: List<ReminderEntity>, onPickTime: () -> Unit) {
    Column {
        myReminders.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(reminderLabel(r), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                // R59 — one control cycles the reminder's intensity tier (Gentle → Persistent → Insistent),
                // surfacing the escalation the alarm engine already supports.
                val tier = com.todocompanion.app.domain.reminders.ReminderPresets.tierOf(r.annoying, r.escalate)
                val tint = when (tier) {
                    0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    1 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
                TextButton(onClick = { vm.setReminderTier(r, task, (tier + 1) % 3) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Icon(if (tier == 0) Icons.Filled.NotificationsNone else Icons.Filled.NotificationsActive, "Reminder intensity", tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(com.todocompanion.app.domain.reminders.ReminderPresets.TIER_LABELS[tier], style = MaterialTheme.typography.labelMedium, color = tint)
                }
                IconButton(onClick = { vm.deleteReminder(r, task) }) { Icon(Icons.Filled.Close, "Remove") }
            }
        }
        Box {
            var addMenu by remember { mutableStateOf(false) }
            var placeDialog by remember { mutableStateOf(false) }
            TextButton(onClick = { addMenu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add reminder") }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("Pick a time…") }, onClick = { addMenu = false; onPickTime() })
                // R59 (Wave 2) — permission-free place reminder (fired on arrival via NFC/QR/shortcut).
                DropdownMenuItem(text = { Text("📍 At a place (arrival)…") }, onClick = { addMenu = false; placeDialog = true })
                if (task.dueDate != null) {
                    HorizontalDivider()
                    com.todocompanion.app.domain.reminders.ReminderPresets.OFFSETS.forEach { off ->
                        val label = if (off == 0) "When due" else "${com.todocompanion.app.domain.reminders.ReminderPresets.beforeLabel(off)} due"
                        DropdownMenuItem(text = { Text(label) }, onClick = { vm.addRelativeReminder(task, "relativeToDue", off); addMenu = false })
                    }
                }
                if (task.startDate != null) {
                    HorizontalDivider()
                    com.todocompanion.app.domain.reminders.ReminderPresets.OFFSETS.forEach { off ->
                        val label = if (off == 0) "When it starts" else "${com.todocompanion.app.domain.reminders.ReminderPresets.beforeLabel(off)} start"
                        DropdownMenuItem(text = { Text(label) }, onClick = { vm.addRelativeReminder(task, "relativeToStart", off); addMenu = false })
                    }
                }
                // R59 (Wave 2) — expert reminder types on the unified model.
                if (task.deadlineDate != null) {
                    HorizontalDivider()
                    DropdownMenuItem(enabled = false, text = { Text("Before deadline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                    listOf(0, 60, 1440, 4320).forEach { off ->
                        val label = if (off == 0) "At deadline" else "${com.todocompanion.app.domain.reminders.ReminderPresets.beforeLabel(off)} deadline"
                        DropdownMenuItem(text = { Text(label) }, onClick = { vm.addExpertReminder(task, "relativeToDeadline", off); addMenu = false })
                    }
                }
                if (task.dueDate != null) {
                    HorizontalDivider()
                    DropdownMenuItem(enabled = false, text = { Text("Expert", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                    DropdownMenuItem(text = { Text("When it becomes overdue") }, onClick = { vm.addExpertReminder(task, "whenOverdue"); addMenu = false })
                    listOf(480 to "On the day at 08:00", 540 to "On the day at 09:00", 1080 to "On the day at 18:00").forEach { (m, l) ->
                        DropdownMenuItem(text = { Text(l) }, onClick = { vm.addExpertReminder(task, "dueDayAt", m); addMenu = false })
                    }
                    DropdownMenuItem(text = { Text("Surprise me (random, before due)") }, onClick = { vm.addExpertReminder(task, "random", 120); addMenu = false })
                    DropdownMenuItem(text = { Text("When due, then nag every 30m ×4") }, onClick = { vm.addExpertReminder(task, "relativeToDue", 0, 30, 4); addMenu = false })
                    DropdownMenuItem(text = { Text("When overdue, nag every 1h ×3") }, onClick = { vm.addExpertReminder(task, "whenOverdue", 0, 60, 3); addMenu = false })
                }
            }
            if (placeDialog) {
                var place by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { placeDialog = false },
                    confirmButton = { TextButton(onClick = { if (place.isNotBlank()) vm.addPlaceReminder(task, place.trim()); placeDialog = false }) { Text("Arm reminder") } },
                    dismissButton = { TextButton(onClick = { placeDialog = false }) { Text("Cancel") } },
                    title = { Text("Remind me at a place") },
                    text = {
                        Column {
                            com.todocompanion.app.ui.components.AppTextField(place, { place = it }, singleLine = true, label = { Text("Place name (e.g. Office, Home)") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            Text("Fully offline — the app never tracks your location. Trigger it when you arrive by scanning an NFC tag or QR code you place there, encoding:",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("todocompanion://arrive?place=${place.trim().ifBlank { "Office" }}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                    },
                )
            }
        }
    }
}

private fun reminderLabel(r: ReminderEntity): String {
    val base = when (r.type) {
        "absolute" -> r.atTime?.let { formatDue(it) } ?: "Reminder"
        "relativeToDue" -> if ((r.offsetMin ?: 0) == 0) "When due" else "${offsetLabel(r.offsetMin)} before due"
        "relativeToStart" -> if ((r.offsetMin ?: 0) == 0) "When it starts" else "${offsetLabel(r.offsetMin)} before start"
        // R59 (Wave 2) — expert types.
        "relativeToDeadline" -> if ((r.offsetMin ?: 0) == 0) "At deadline" else "${offsetLabel(r.offsetMin)} before deadline"
        "dueDayAt" -> "On the day at %02d:%02d".format((r.offsetMin ?: 540) / 60, (r.offsetMin ?: 540) % 60)
        "whenOverdue" -> "When overdue"
        "random" -> "Surprise (within ${offsetLabel(r.offsetMin)} of due)"
        "location" -> (if (r.onEnter) "📍 At " else "📍 Leaving ") + (r.placeName ?: "a place")
        else -> r.type
    }
    return if ((r.repeatCount ?: 0) >= 2 && r.repeatEveryMin != null)
        "$base · ×${r.repeatCount} every ${offsetLabel(r.repeatEveryMin)}" else base
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

/** A modernized slider (R23): primary rounded track, a slim pill thumb and no busy tick marks — a calmer,
 *  more current look than the default dotted M3 slider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernSlider(value: Float, valueRange: ClosedFloatingPointRange<Float>, steps: Int, onValueChange: (Float) -> Unit, onFinished: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.material3.Slider(
        value = value, onValueChange = onValueChange, onValueChangeFinished = onFinished, valueRange = valueRange, steps = steps,
        interactionSource = interaction, modifier = modifier,
        thumb = {
            Box(Modifier.size(width = 10.dp, height = 22.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
        },
        track = { state ->
            val span = (valueRange.endInclusive - valueRange.start)
            val frac = (if (span > 0f) (state.value - valueRange.start) / span else 0f).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(frac).height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
            }
        },
    )
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
        ModernSlider(value.toFloat(), 1f..5f, 3, { onChange(it.roundToInt().coerceIn(1, 5)) }, null, Modifier.weight(1f))
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DurationPickerDialog(initialMin: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    // Any amount of time (R22): hours 0–99 and minutes 0–59 at 1-minute granularity, typeable directly or
    // nudged with ±. A quick-preset row covers the common cases without stepping.
    var hours by remember { mutableIntStateOf((initialMin / 60).coerceIn(0, 99)) }
    var mins by remember { mutableIntStateOf((initialMin % 60).coerceIn(0, 59)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Duration") },
        text = {
            Column {
                Stepper(hours, { hours = it }, min = 0, max = 99, step = 1, label = "Hours", editable = true)
                Spacer(Modifier.height(8.dp))
                Stepper(mins, { mins = it }, min = 0, max = 59, step = 5, label = "Minutes", editable = true)   // ± nudges by 5; type any minute directly
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60, 90, 120, 180, 240).forEach { m ->
                        FilterChip(selected = hours * 60 + mins == m, onClick = { hours = m / 60; mins = m % 60 },
                            label = { Text(fmtDuration(m)) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("= ${fmtDuration((hours * 60 + mins).coerceAtLeast(0))}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hours * 60 + mins) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


// ══════════════════════════════ R37 · Task coach (habit-science ports) ══════════════════════════════
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TaskCoachCard(vm: AppViewModel, task: com.todocompanion.app.data.entity.TaskEntity) {
    val tasks by vm.tasks.collectAsState()
    val values by vm.coreValues.collectAsState()
    val escrows by vm.escrows.collectAsState()
    val revisions by vm.taskRevisions(task.id).collectAsState(initial = emptyList())
    val subtaskCount = remember(tasks, task.id) { tasks.count { it.parentId == task.id } }
    val now = System.currentTimeMillis()
    val hour = java.time.LocalTime.now().hour
    val lesson = remember(task, subtaskCount, hour) { com.todocompanion.app.domain.task.TaskCoach.taskLesson(task, subtaskCount, hour, now) }
    val reliability = remember(task, revisions) { com.todocompanion.app.domain.task.TaskCoach.reliability(task, revisions) }
    val myEscrows = escrows.filter { it.taskId == task.id }
    val color = MaterialTheme.colorScheme.primary
    var valueOpen by remember { mutableStateOf(false) }
    var escrowOpen by remember { mutableStateOf(false) }

    // Nothing to show → render nothing (keep the editor clean).
    val hasAny = lesson != null || task.deferCount >= 2 || reliability != null || values.isNotEmpty() || myEscrows.isNotEmpty() || !task.completed
    if (!hasAny) return

    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(18.dp), color = appCardColor()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Coach", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)

            // Port 3 — deferral chain.
            if (task.deferCount >= 2 && !task.completed) {
                Text("🪃 You've pushed this ${task.deferCount} times. Rescheduling isn't a plan — make it smaller, or book it into a real slot today.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            // Port 2 — micro-lesson.
            lesson?.let { l ->
                Text("${l.emoji} ${l.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(l.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Port 10 — recurring-task reliability horizon.
            reliability?.let { r ->
                Text("Reliability: ${r.ratePct}% on-time (${r.onTime}/${r.completions})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(r.ratePct / 100f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
                }
                Text("How often you finish this repeating task on or before its due date — the honest analog to habit automaticity.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Port 9 — value link.
            if (values.isNotEmpty()) {
                val vName = values.firstOrNull { it.id == task.valueId }?.let { (it.emoji?.plus(" ") ?: "") + it.name }
                androidx.compose.material3.TextButton(onClick = { valueOpen = !valueOpen }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (vName != null) "Value: $vName" else "Link to a value…", color = color)
                }
                if (valueOpen) OptionChips(listOf<String?>(null) + values.map { it.id }, task.valueId, { id -> vm.setTaskValue(task.id, id); valueOpen = false }, spacing = 6) { id ->
                    if (id == null) "None" else values.firstOrNull { it.id == id }?.let { (it.emoji?.plus(" ") ?: "") + it.name } ?: ""
                }
            }

            // Port 6 — ship-it escrow.
            if (myEscrows.isNotEmpty()) {
                myEscrows.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (e.kind == "stake") "🎯 " else "🎁 ") + e.description + (if (e.released) " · done" else if (task.completed) " · ready to claim" else ""),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (!e.released && task.completed) androidx.compose.material3.TextButton(onClick = { vm.releaseEscrow(e, e.kind == "reward") }) { Text("Release") }
                        androidx.compose.material3.IconButton(onClick = { vm.deleteEscrow(e.id) }) { Icon(Icons.Filled.Delete, "Remove", modifier = Modifier.size(18.dp)) }
                    }
                }
            } else if (!task.completed) {
                androidx.compose.material3.TextButton(onClick = { escrowOpen = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Put something on shipping this…", color = color)
                }
            }
        }
    }

    if (escrowOpen) {
        var desc by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("reward") }
        AlertDialog(onDismissRequest = { escrowOpen = false },
            title = { Text("Escrow on shipping this") },
            text = {
                Column {
                    Text("Pre-commit a reward you'll unlock — or a stake you forfeit — when this task is done.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    com.todocompanion.app.ui.components.AppTextField(desc, { desc = it }, singleLine = true, label = { Text("Reward or stake") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OptionChips(listOf("reward", "stake"), kind, { kind = it }, spacing = 8) { if (it == "reward") "Reward" else "Stake" }
                }
            },
            confirmButton = { TextButton(enabled = desc.isNotBlank(), onClick = { vm.addTaskEscrow(task.id, desc, kind); escrowOpen = false }) { Text("Lock it") } },
            dismissButton = { TextButton(onClick = { escrowOpen = false }) { Text("Cancel") } })
    }
}
