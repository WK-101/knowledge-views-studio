package com.todocompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Segment
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.components.DueChip
import com.todocompanion.app.ui.components.appCardColor
import com.todocompanion.app.ui.theme.LocalKairoColors

/** Search result filters (task-scope; non-task results appear only under All). */
private enum class SF(val label: String) { ALL("All"), TODAY("Today"), OVERDUE("Overdue"), FLAGGED("Flagged"), HIGH("High priority"), DONE("Completed"), TRASH("Trashed") }

/** Sort order for the task results. */
private enum class SortBy(val label: String) { RELEVANCE("Relevance"), TITLE("Title A–Z"), DUE("Due date"), PRIORITY("Priority"), UPDATED("Recently updated") }

/** How the task results are grouped. */
private enum class GroupBy(val label: String) { TYPE("By type"), LIST("By list"), PRIORITY("By priority"), NONE("No groups") }

/** Which kind of result to show. ALL = everything; the others narrow to one type and reveal that type's
 *  own sub-filter, so occasions, notes, habits and events are each filterable in their own right. */
private enum class Scope(val label: String) { ALL("Everything"), TASKS("Tasks"), NOTES("Notes"), OCCASIONS("Occasions"), HABITS("Habits"), EVENTS("Events") }

/** Per-type sub-filters (shown only when that type is the active scope). */
private enum class NoteF(val label: String) { ALL("All notes"), PINNED("Pinned"), ARCHIVED("Archived"), JOURNAL("Journal"), MEETING("Meeting") }
private enum class OccF(val label: String) { ALL("All"), BIRTHDAYS("Birthdays"), UPCOMING("Upcoming"), YEARLY("Recurring"), ARCHIVED("Archived") }
private enum class HabitF(val label: String) { ALL("All"), ACTIVE("Active"), PAUSED("Paused"), BUILD("Build"), QUIT("Quit") }
private enum class EventF(val label: String) { ALL("All"), UPCOMING("Upcoming"), PAST("Past"), RECURRING("Recurring"), ALLDAY("All-day") }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    vm: AppViewModel, onOpenTask: (String) -> Unit, query: String, modifier: Modifier = Modifier,
    onOpenHabit: (String) -> Unit = {}, onOpenEvent: (String) -> Unit = {}, onOpenOccasion: (String) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
) {
    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val eventsState by vm.events.collectAsState()
    val occasionsState by vm.countdowns.collectAsState()
    val notesState by vm.notes.collectAsState()
    // R54 — FTS-accelerated for large histories, instant in-memory for small sets (see vm.searchAsync).
    val results by androidx.compose.runtime.produceState(initialValue = emptyList<TaskEntity>(), query, tasks) {
        value = vm.searchAsync(query)
    }
    // E1: habits are searchable too — shown only under the "All" filter (task filters don't apply).
    val habitResults = remember(query, habits) { vm.searchHabits(query) }
    // R57 — events & occasions are searchable too, so "Search everything" truly covers the calendar.
    val eventResults = remember(query, eventsState) { vm.searchEvents(query) }
    val occasionResults = remember(query, occasionsState) { vm.searchOccasions(query) }
    // NOTES — the whole-app search now reaches note titles AND bodies via the on-device note_fts index,
    // so "Search everything" is honest about notes. Driven by vm.searchNotes (async FTS) → noteSearchIds.
    androidx.compose.runtime.LaunchedEffect(query) { vm.searchNotes(query) }
    val noteIds by vm.noteSearchIds.collectAsState()
    val noteResults = remember(noteIds, notesState) {
        val byId = notesState.associateBy { it.id }   // active-workspace, non-trashed notes only
        noteIds.mapNotNull { byId[it] }
    }
    // R56 — attachment names are searchable; map taskId → the matched file name for the "📎 …" hint.
    val attachHits = remember(query) { vm.searchAttachmentNames(query).associate { it.taskId to it.fileName } }
    val lists by vm.lists.collectAsState()
    val folders by vm.folders.collectAsState()
    var scope by remember { mutableStateOf(Scope.ALL) }
    var filter by remember { mutableStateOf(SF.ALL) }
    var sortBy by remember { mutableStateOf(SortBy.RELEVANCE) }
    var groupBy by remember { mutableStateOf(GroupBy.TYPE) }
    var noteF by remember { mutableStateOf(NoteF.ALL) }
    var occF by remember { mutableStateOf(OccF.ALL) }
    var habitF by remember { mutableStateOf(HabitF.ALL) }
    var eventF by remember { mutableStateOf(EventF.ALL) }
    val zone = java.time.ZoneId.systemDefault()
    // Apply the task-scope filter, then the chosen sort. Grouping happens at render time.
    val shown = remember(results, filter, sortBy) {
        val today = java.time.LocalDate.now(); val nowMs = System.currentTimeMillis()
        val filtered = results.filter { t ->
            // Trashed tasks appear ONLY under the Trashed filter — every other filter hides them so the
            // default results stay clean, while nothing is unfindable (R56).
            when (filter) {
                SF.TRASH -> t.trashed
                SF.ALL -> !t.trashed
                SF.TODAY -> !t.trashed && t.dueDate?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true
                SF.OVERDUE -> !t.trashed && t.dueDate?.let { it < nowMs && !t.completed } == true
                SF.FLAGGED -> !t.trashed && t.flagId != null
                SF.HIGH -> !t.trashed && PriorityLevel.from(t.importance, t.urgency) == PriorityLevel.HIGH
                SF.DONE -> !t.trashed && t.completed
            }
        }
        when (sortBy) {
            SortBy.RELEVANCE -> filtered
            SortBy.TITLE -> filtered.sortedBy { it.title.trim().lowercase() }
            SortBy.DUE -> filtered.sortedWith(compareBy(nullsLast()) { it.dueDate })
            SortBy.PRIORITY -> filtered.sortedByDescending { PriorityLevel.from(it.importance, it.urgency).ordinal }
            SortBy.UPDATED -> filtered.sortedByDescending { it.updatedAt }
        }
    }
    // Each non-task type gets its own sub-filter, applied to its already-matched results. Kept out of the
    // task pipeline above so "search everything" stays honest while each type is independently filterable.
    val shownNotes = remember(noteResults, noteF) {
        noteResults.filter { n ->
            when (noteF) {
                NoteF.ALL -> true
                NoteF.PINNED -> n.pinned
                NoteF.ARCHIVED -> n.archived
                NoteF.JOURNAL -> n.kind == "journal"
                NoteF.MEETING -> n.kind == "meeting"
            }
        }
    }
    val shownOccasions = remember(occasionResults, occF) {
        occasionResults.filter { o ->
            when (occF) {
                OccF.ALL -> true
                OccF.BIRTHDAYS -> com.todocompanion.app.domain.LifeEvent.type(o) == com.todocompanion.app.domain.LifeEvent.EventType.BIRTHDAY
                OccF.UPCOMING -> !o.archived && com.todocompanion.app.domain.LifeEvent.daysUntil(o) >= 0
                OccF.YEARLY -> o.yearly
                OccF.ARCHIVED -> o.archived
            }
        }
    }
    val shownHabits = remember(habitResults, habitF) {
        habitResults.filter { h ->
            when (habitF) {
                HabitF.ALL -> true
                HabitF.ACTIVE -> !h.paused
                HabitF.PAUSED -> h.paused
                HabitF.BUILD -> h.habitType == "build"
                HabitF.QUIT -> h.habitType == "break"
            }
        }
    }
    val nowMsE = System.currentTimeMillis()
    val shownEvents = remember(eventResults, eventF) {
        eventResults.filter { e ->
            when (eventF) {
                EventF.ALL -> true
                EventF.UPCOMING -> e.endMillis >= nowMsE
                EventF.PAST -> e.endMillis < nowMsE
                EventF.RECURRING -> e.rrule.isNotBlank()
                EventF.ALLDAY -> e.allDay
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // The search field lives in the app top bar; this control row sits at the top of the results. The
        // Scope control decides which kind of result is shown; each non-task scope reveals ITS OWN sub-filter,
        // so occasions, notes, habits and events are each filterable — not just tasks. The row scrolls
        // sideways so it never overflows at phone width, no matter how many controls the scope reveals.
        val tasksShown = scope == Scope.ALL || scope == Scope.TASKS
        if (query.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DropControl(Icons.Filled.Category, scope, Scope.entries, { it.label }) { scope = it }
                when (scope) {
                    Scope.ALL, Scope.TASKS -> {
                        DropControl(Icons.Filled.FilterList, filter, SF.entries, { it.label }) { filter = it }
                        DropControl(Icons.AutoMirrored.Filled.Sort, sortBy, SortBy.entries, { it.label }) { sortBy = it }
                        DropControl(Icons.AutoMirrored.Filled.Segment, groupBy, GroupBy.entries, { it.label }) { groupBy = it }
                    }
                    Scope.NOTES -> DropControl(Icons.Filled.FilterList, noteF, NoteF.entries, { it.label }) { noteF = it }
                    Scope.OCCASIONS -> DropControl(Icons.Filled.FilterList, occF, OccF.entries, { it.label }) { occF = it }
                    Scope.HABITS -> DropControl(Icons.Filled.FilterList, habitF, HabitF.entries, { it.label }) { habitF = it }
                    Scope.EVENTS -> DropControl(Icons.Filled.FilterList, eventF, EventF.entries, { it.label }) { eventF = it }
                }
            }
        }
        // Task filter of ALL keeps non-task types visible under the Everything scope; under a specific scope only
        // that type shows. Each list is already narrowed by its own sub-filter above.
        val showHabits = (scope == Scope.ALL || scope == Scope.HABITS) && shownHabits.isNotEmpty()
        val showEvents = (scope == Scope.ALL || scope == Scope.EVENTS) && shownEvents.isNotEmpty()
        val showOccasions = (scope == Scope.ALL || scope == Scope.OCCASIONS) && shownOccasions.isNotEmpty()
        val showNotes = (scope == Scope.ALL || scope == Scope.NOTES) && shownNotes.isNotEmpty()
        val showTasks = tasksShown && shown.isNotEmpty()
        when {
            query.isBlank() -> SearchHint("Search everything", "Find any task, habit, event, occasion, note (title & content), #tag, @context or 📎 attachment name — completed, someday and archived included; tap Trashed to search the bin")
            !showTasks && !showHabits && !showEvents && !showOccasions && !showNotes -> SearchHint("No matches", "Nothing found for “$query”", off = true)
            else -> {
                val totalN = (if (showTasks) shown.size else 0) + (if (showHabits) shownHabits.size else 0) + (if (showEvents) shownEvents.size else 0) + (if (showOccasions) shownOccasions.size else 0) + (if (showNotes) shownNotes.size else 0)
                Text("$totalN result${if (totalN == 1) "" else "s"}",
                    Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (showHabits) {
                        item(key = "habits-header") { SectionHeader("HABITS") }
                        items(shownHabits, key = { "h:" + it.id }) { h ->
                            Surface(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                shape = RoundedCornerShape(12.dp), color = appCardColor(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpenHabit(h.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background((h.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                        Text(h.emoji ?: "🔁", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        val sub = listOfNotNull(h.category.ifBlank { null }, h.identity.ifBlank { null }, h.description.ifBlank { null }).firstOrNull()
                                        if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Habit" + (if (h.paused) " · paused" else "") + (if (h.archived) " · archived" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    // TASKS — grouped per the Group control. A single row composable (TaskResultRow) is reused
                    // for every layout so completed styling stays identical everywhere.
                    if (showTasks) {
                        when (groupBy) {
                            GroupBy.TYPE, GroupBy.NONE -> {
                                // "By type" keeps a TASKS header when other types are also on screen; "No groups"
                                // never prints a header (flat stream of results).
                                if (groupBy == GroupBy.TYPE && (showHabits || showEvents || showOccasions || showNotes)) item(key = "tasks-header") { SectionHeader("TASKS", top = 10.dp) }
                                items(shown, key = { it.id }) { task ->
                                    TaskResultRow(task, lists, folders, attachHits[task.id], zone) { onOpenTask(task.id) }
                                }
                            }
                            GroupBy.LIST -> {
                                val groups = shown.groupBy { t ->
                                    lists.firstOrNull { it.id == t.listId }?.name
                                        ?: t.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.let { "📁 " + it.name } }
                                        ?: "Inbox"
                                }.toSortedMap()
                                groups.forEach { (name, tasksInGroup) ->
                                    item(key = "lg:$name") { SectionHeader(name.uppercase(), top = 10.dp) }
                                    items(tasksInGroup, key = { it.id }) { task ->
                                        TaskResultRow(task, lists, folders, attachHits[task.id], zone) { onOpenTask(task.id) }
                                    }
                                }
                            }
                            GroupBy.PRIORITY -> {
                                // HIGH → NONE so the most urgent group leads.
                                val groups = shown.groupBy { PriorityLevel.from(it.importance, it.urgency) }
                                    .toSortedMap(compareByDescending { it.ordinal })
                                groups.forEach { (level, tasksInGroup) ->
                                    item(key = "pg:${level.name}") { SectionHeader(level.label.uppercase() + " PRIORITY", top = 10.dp) }
                                    items(tasksInGroup, key = { it.id }) { task ->
                                        TaskResultRow(task, lists, folders, attachHits[task.id], zone) { onOpenTask(task.id) }
                                    }
                                }
                            }
                        }
                    }
                    // R57 — EVENTS section (calendar), tap opens the event editor.
                    if (showEvents) {
                        item(key = "events-header") { SectionHeader("EVENTS", top = 10.dp) }
                        items(shownEvents, key = { "e:" + it.id }) { e ->
                            val df = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy")
                            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), shape = RoundedCornerShape(12.dp), color = appCardColor()) {
                                Row(Modifier.fillMaxWidth().clickable { onOpenEvent(e.id) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🗓️", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(e.title.ifBlank { "(untitled)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        val sub = (if (e.allDay) "All day" else java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).format(df)) +
                                            (if (e.location.isNotBlank()) " · " + e.location else "") + (if (e.rrule.isNotBlank()) " · repeats" else "")
                                        Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    // R57 — OCCASIONS section (birthdays / countdowns), tap opens the occasion.
                    if (showOccasions) {
                        item(key = "occasions-header") { SectionHeader("OCCASIONS", top = 10.dp) }
                        items(shownOccasions, key = { "o:" + it.id }) { o ->
                            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), shape = RoundedCornerShape(12.dp), color = appCardColor()) {
                                Row(Modifier.fillMaxWidth().clickable { onOpenOccasion(o.id) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(o.emoji?.ifBlank { null } ?: "🎉", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        // The real name is the person ("Sara"); title ("Birthday") is the type/subtitle.
                                        val name = com.todocompanion.app.domain.LifeEvent.displayName(o).ifBlank { "(untitled)" }
                                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        val typeLabel = if (o.personName.isNotBlank() && o.title.isNotBlank() && !o.title.equals(o.personName, true)) o.title
                                            else o.notes.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifBlank { "Occasion" }
                                        Text(typeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    // NOTES section — matched on title or body (note_fts), tap opens the note editor.
                    if (showNotes) {
                        item(key = "notes-header") { SectionHeader("NOTES", top = 10.dp) }
                        items(shownNotes, key = { "n:" + it.id }) { n ->
                            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), shape = RoundedCornerShape(12.dp), color = appCardColor()) {
                                Row(Modifier.fillMaxWidth().clickable { onOpenNote(n.id) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background((n.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                        Text(n.coverEmoji?.ifBlank { null } ?: "📝", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(n.title.ifBlank { "(untitled note)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        // A short body excerpt so a body-only match shows why the note surfaced.
                                        val snippet = n.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
                                        if (snippet.isNotBlank()) Text(snippet, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Note" + (if (n.pinned) " · pinned" else "") + (if (n.archived) " · archived" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One task result row. Completed tasks are styled distinctly from overdue/open ones: a green filled check,
 *  a struck-through muted title, a positive-tinted "Completed <date>" line, and NO overdue-coloured due chip —
 *  so a done item can never be mistaken for an overdue one. */
@Composable
private fun TaskResultRow(
    task: TaskEntity,
    lists: List<ListEntity>,
    folders: List<FolderEntity>,
    attachFile: String?,
    zone: java.time.ZoneId,
    onOpen: () -> Unit,
) {
    val level = PriorityLevel.from(task.importance, task.urgency)
    val kairo = LocalKairoColors.current
    val done = task.completed && !task.trashed
    // Completed rows read as done at a glance WITHOUT a crude strikethrough: a faint success wash on the whole
    // card, a filled green check where the priority dot would be, the title kept full-strength (a finished task
    // is an achievement, not struck-out noise), and the completion date as a soft green pill on the right —
    // clearly different from the red overdue chip an open task shows.
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (done) androidx.compose.ui.graphics.lerp(appCardColor(), kairo.good, 0.07f) else appCardColor(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (done) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = kairo.good)
                Spacer(Modifier.width(8.dp))
            } else {
                com.todocompanion.app.ui.components.Dot(
                    if (level == PriorityLevel.NONE) MaterialTheme.colorScheme.outlineVariant
                    else com.todocompanion.app.ui.components.priorityColor(level), 8,
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (task.note.isNotBlank()) Text(task.note.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // R56 — when the match came from an attachment name, show which file, so an expert instantly
                // sees why a task surfaced.
                attachFile?.let { fn ->
                    Text("📎 $fn", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                // Location: the task's list, or — for a task captured straight into a folder (empty listId) —
                // the folder name, rather than a wrong "Inbox".
                val loc = lists.firstOrNull { it.id == task.listId }?.name
                    ?: task.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.let { "📁 " + it.name } }
                    ?: "Inbox"
                val locSuffix = when {
                    task.trashed -> " · 🗑 Trash"
                    task.someday -> " · someday"
                    else -> ""
                }
                Text(loc + locSuffix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                // Completed → a soft green "Done · date" pill (never the red overdue chip).
                done -> {
                    Spacer(Modifier.width(6.dp))
                    val label = task.completedAt?.let { "Done " + java.time.Instant.ofEpochMilli(it).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) } ?: "Done"
                    Surface(shape = RoundedCornerShape(8.dp), color = kairo.good.copy(alpha = 0.16f)) {
                        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = kairo.good, maxLines = 1)
                    }
                }
                // Open/overdue tasks carry the (possibly red) due chip.
                else -> task.dueDate?.let { Spacer(Modifier.width(6.dp)); DueChip(it) }
            }
        }
    }
}

/** A compact top-bar dropdown control: leading icon + current selection + chevron, opening a checked menu. */
@Composable
private fun <T> DropControl(icon: ImageVector, selected: T, entries: List<T>, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(labelOf(selected), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { e ->
                DropdownMenuItem(
                    text = { Text(labelOf(e)) },
                    onClick = { onSelect(e); open = false },
                    trailingIcon = { if (e == selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, top: androidx.compose.ui.unit.Dp = 4.dp) {
    Text(text, Modifier.padding(start = 18.dp, top = top, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SearchHint(title: String, subtitle: String, off: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
            Icon(if (off) Icons.Outlined.SearchOff else Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.size(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
