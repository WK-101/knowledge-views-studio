package com.wkhan.hexis.bridge.data

import kotlinx.serialization.Serializable

/**
 * The typed, transport-stable shapes the `data` capability exchanges. These are a deliberate, minimal
 * projection of the core's entities — the public contract a consumer (the web UI) can rely on — not the
 * internal Room entities. The core maps entity → DTO; adding a field here is additive (tolerant decoding).
 */

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val listId: String? = null,
    val folderId: String? = null,
    val parentId: String? = null,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val abandoned: Boolean = false,
    val someday: Boolean = false,
    val dueDate: Long? = null,
    val startDate: Long? = null,
    val deadlineDate: Long? = null,
    val isAllDay: Boolean = false,
    val importance: Int = 3,
    val urgency: Int = 3,
    val energy: Int? = null,
    val durationMin: Int? = null,
    val estimateMin: Int? = null,
    val estimateMax: Int? = null,
    val leadTimeMin: Int? = null,
    val star: Boolean = false,
    val flagId: String? = null,
    val flagColorArgb: Long? = null,
    val pinned: Boolean = false,
    val isNote: Boolean = false,
    val isGoal: Boolean = false,
    val isProject: Boolean = false,
    val progressPct: Int? = null,
    val completeInOrder: Boolean = false,
    val rrule: String? = null,
    val recurrenceMode: String? = null,
    val waitingForWho: String = "",
    val delegatedOn: Long? = null,
    val defaultActivityId: String? = null,
    val reviewEveryDays: Int? = null,
    val colorArgb: Long? = null,
    val sortOrder: Double = 0.0,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val contextIds: List<String> = emptyList(),
    // reflection (completed-task editor fields)
    val winFlag: Boolean = false,
    val mood: Int? = null,
    val outcomeNote: String? = null,
    val learnedNote: String? = null,
    val praiseQuote: String? = null,
    val whyText: String = "",
    val rewardText: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class NoteDto(
    val id: String,
    val title: String = "",
    val body: String = "", // Markdown
    val notebookId: String? = null,
    val folderId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val colorArgb: Long? = null,
    val coverEmoji: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** A task list (for the web sidebar). The special Inbox list has a fixed id. */
@Serializable
data class ListDto(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val count: Int = 0,
    val folderId: String? = null,
    val parentListId: String? = null,
    val sortOrder: Double = 0.0,
    val viewMode: String = "list", // list | outline
    val archived: Boolean = false,
)

/** A folder (groups lists, and can hold directly-captured tasks). */
@Serializable
data class FolderDto(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val sortOrder: Double = 0.0,
    val archived: Boolean = false,
)

/** A tag (hierarchical; `#name`). */
@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val colorArgb: Long? = null,
    val parentId: String? = null,
    val sortOrder: Double = 0.0,
)

/** A context (`@name`), optionally place-bound. */
@Serializable
data class ContextDto(
    val id: String,
    val name: String,
    val icon: String? = null,
    val colorArgb: Long? = null,
    val parentId: String? = null,
    val sortOrder: Double = 0.0,
)

/** A flag (labeled bookmark). Distinct from star and from priority. */
@Serializable
data class FlagDto(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val icon: String = "bookmark",
    val sortOrder: Double = 0.0,
)

/** A task dependency (blocked-by). [mode] AND/OR; [delayDays] "start after". */
@Serializable
data class DependencyDto(
    val taskId: String,
    val dependsOnTaskId: String,
    val mode: String = "AND",
    val delayDays: Int = 0,
)

/** A task reminder (editor). See the many [type]s the core supports. */
@Serializable
data class ReminderDto(
    val id: String,
    val taskId: String,
    val type: String = "absolute",
    val atTime: Long? = null,
    val offsetMin: Int? = null,
    val repeatEveryMin: Int? = null,
    val repeatCount: Int? = null,
    val placeName: String? = null,
    val onEnter: Boolean = true,
    val annoying: Boolean = false,
    val escalate: Boolean = false,
)

/** A flat checklist item on a task (distinct from nested subtasks). */
@Serializable
data class ChecklistItemDto(
    val id: String,
    val taskId: String,
    val text: String,
    val checked: Boolean = false,
    val sortOrder: Double = 0.0,
)

/**
 * A read-only projection of the display-relevant subset of the core's `AppSettings`, so the web renders
 * what the phone is configured to show (not its own guesses). Enum values are their `.name`; map/list
 * collections are passed through so the web can mirror visibility/order/thresholds exactly.
 */
@Serializable
data class SettingsDto(
    val activeWorkspaceId: String = "default",
    // time / day boundary
    val weekStart: Int = 0, // 0 = locale, 1..7 = Mon..Sun
    val timeFormat: String = "SYSTEM", // SYSTEM | H12 | H24
    val timeZone: String = "",
    val dayStartHour: Int = 0,
    val dayStartMinute: Int = 0,
    val density: String = "DEFAULT", // COMPACT | DEFAULT | RELAXED
    // tasks
    val advancedPriority: Boolean = false,
    val smartListVis: Map<String, String> = emptyMap(), // SmartKind.name -> SHOW|AUTO|HIDE
    val smartListNames: Map<String, String> = emptyMap(), // SmartKind.name -> custom title
    val smartOrder: List<String> = emptyList(),
    val editorFieldTiers: Map<String, Int> = emptyMap(), // EditorField.id -> 0/1/2
    val editorFieldOrder: List<String> = emptyList(),
    val editorFieldFolded: List<String> = emptyList(),
    // matrix
    val matrixImportanceThreshold: Int = 4,
    val matrixUrgencyThreshold: Int = 4,
    val matrixShowCompleted: Boolean = false,
    val matrixHideEmpty: Boolean = false,
    val matrixNames: List<String> = emptyList(),
    val matrixListFilter: List<String> = emptyList(),
    val matrixFolderFilter: List<String> = emptyList(),
    val matrixMaxDuration: Int = 0,
    val matrixOverdueOnly: Boolean = false,
    val matrixDateFilter: String = "all",
    val matrixSort: String = "priority",
    // calendar
    val calendarDefaultMode: String = "month",
    val calendarRememberLast: Boolean = true,
    val calendarShowCompleted: Boolean = false,
    val calendarListFilter: List<String> = emptyList(),
    // notes
    val notesNotebookMode: String = "folderTree", // folderTree | notebooks
    val noteDefaultView: String = "grid", // grid | list
    val notesViewMode: String = "cards", // cards | board | calendar
    val notesSort: String = "updated", // updated | created | titleAsc | titleDesc
    // habits
    val habitSort: String = "manual", // manual | name | streak | strength | created
    val habitGroupByCategory: Boolean = true,
    val strengthMeter: Boolean = false,
    val calmMode: Boolean = false,
    val forgivingStreaks: Boolean = true,
    val hideStreaks: Boolean = false,
    // time
    val timeGridColumns: Int = 2,
)

/** An event calendar (for the web calendar picker + colour legend). [isDefault] marks the create target. */
@Serializable
data class CalendarDto(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val visible: Boolean = true,
    val isDefault: Boolean = false,
)

/** A calendar event, projected for display. [calendarName] is resolved from the owning calendar. */
@Serializable
data class EventDto(
    val id: String,
    val title: String,
    val calendarId: String,
    val calendarName: String = "",
    val location: String = "",
    val notes: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val allDay: Boolean = false,
    val recurring: Boolean = false,
    val colorArgb: Long? = null,
    val linkedTaskId: String? = null,
)

/** A time activity (for the web start-timer picker + report legend). [goalMinutesPerDay] 0 = no goal. */
@Serializable
data class TimeActivityDto(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val goalMinutesPerDay: Int = 0,
    val goalDays: String = "", // CSV of ISO weekdays 1=Mon..7=Sun; "" = every day
    val sortOrder: Double = 0.0,
)

/** One habit's check-in for one day (for the heatmap). [done] = "done" status; [goalMet] = count reached target. */
@Serializable
data class HabitDayDto(
    val habitId: String,
    val epochDay: Long,
    val count: Int = 0,
    val done: Boolean = false,
    val target: Int = 1,
    val goalMet: Boolean = false,
    val skip: Boolean = false,
)

/** A time-tracking entry. [endMillis] null = still running; [minutes] is the resolved duration. */
@Serializable
data class TimeEntryDto(
    val id: String,
    val activityId: String,
    val activityName: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long? = null,
    val minutes: Int = 0,
    val note: String = "",
    val kind: String = "manual",
    val running: Boolean = false,
)

/** An attachment's metadata (never its bytes). [noteId] is set for note attachments. */
@Serializable
data class AttachmentDto(
    val id: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val isImage: Boolean,
    val noteId: String? = null,
    val taskId: String = "",
)

/** One attachment's bytes, inlined only for small images. [dataB64] is standard Base64 for a data URL. */
@Serializable
data class AttachmentData(
    val id: String,
    val mime: String,
    val isImage: Boolean = false,
    val dataB64: String? = null,
    val tooLarge: Boolean = false,
)

/** A habit, with today's progress folded in ([doneToday] / [todayCount] against [targetPerDay]). */
@Serializable
data class HabitDto(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val targetPerDay: Int = 1,
    val unit: String? = null,
    val habitType: String = "build",
    val targetComparison: String = "atleast", // atleast | atmost
    val category: String = "",
    val scheduleDays: String = "", // CSV 1=Mon..7=Sun; "" = every day
    val reminderTimes: String = "", // CSV minutes-from-midnight
    val freqType: String = "weekly", // weekly | times_week | times_month | interval
    val freqParam: Int = 0,
    val clickIncrement: Int = 1,
    val extraTarget: Int? = null,
    val startDate: Long? = null,
    val sortOrder: Double = 0.0,
    val paused: Boolean = false,
    val todayCount: Int = 0,
    val doneToday: Boolean = false,
    val currentStreak: Int = 0,
    val strength: Int = 0,
)
