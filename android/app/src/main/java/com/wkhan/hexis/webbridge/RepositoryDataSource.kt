package com.wkhan.hexis.webbridge

import android.content.Context
import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.data.AttachmentData
import com.wkhan.hexis.bridge.data.AttachmentDto
import com.wkhan.hexis.bridge.data.CalendarDto
import com.wkhan.hexis.bridge.data.ChecklistItemDto
import com.wkhan.hexis.bridge.data.ContextDto
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataPage
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.data.DataResult
import com.wkhan.hexis.bridge.data.DependencyDto
import com.wkhan.hexis.bridge.data.EventDto
import com.wkhan.hexis.bridge.data.FlagDto
import com.wkhan.hexis.bridge.data.FolderDto
import com.wkhan.hexis.bridge.data.HabitDayDto
import com.wkhan.hexis.bridge.data.HabitDto
import com.wkhan.hexis.bridge.data.ListDto
import com.wkhan.hexis.bridge.data.NoteDto
import com.wkhan.hexis.bridge.data.ReminderDto
import com.wkhan.hexis.bridge.data.SettingsDto
import com.wkhan.hexis.bridge.data.TagDto
import com.wkhan.hexis.bridge.data.TaskDto
import com.wkhan.hexis.bridge.data.TimeActivityDto
import com.wkhan.hexis.bridge.data.TimeEntryDto
import kotlinx.coroutines.flow.first
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.DependencyEntity
import com.wkhan.hexis.data.entity.EventCalendarEntity
import com.wkhan.hexis.data.entity.EventEntity
import com.wkhan.hexis.data.entity.ListEntity
import com.wkhan.hexis.data.entity.NoteEntity
import com.wkhan.hexis.data.entity.ReminderEntity
import com.wkhan.hexis.data.entity.TaskEntity
import com.wkhan.hexis.domain.AppClock
import com.wkhan.hexis.domain.calendar.CalendarEngine
import com.wkhan.hexis.reminders.AlarmScheduler

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable

/**
 * The read/write facade over [AppRepository] for the `data` capability — a small, deliberate projection
 * (tasks + notes, active workspace, non-trashed). Reads are offset-paginated; writes are curated and
 * reversible (delete = move to Trash, never a hard delete). Only the fields in [TaskDto] / [NoteDto] ever
 * cross the bridge — never the raw entity.
 */
@Suppress("TooManyFunctions") // a facade: many small, single-purpose read/write/map helpers by design
class RepositoryDataSource(
    private val repo: AppRepository,
    // The provider runs in the CORE process, so a Context is available: event writes (re)schedule or cancel
    // alarms exactly like the on-device calendar. Nullable only so unit tests can construct without Android.
    private val appContext: Context? = null,
) : DataSource {

    override suspend fun query(query: DataQuery): DataPage = when (query.domain) {
        DataApi.DOMAIN_TASKS -> queryTasks(query)
        DataApi.DOMAIN_NOTES -> queryNotes(query)
        DataApi.DOMAIN_CALENDAR -> queryCalendar(query)
        DataApi.DOMAIN_TIME -> queryTime(query)
        DataApi.DOMAIN_HABITS -> queryHabits(query)
        DataApi.DOMAIN_SETTINGS -> onePage(settingsDto())
        else -> throw UnsupportedDomainException(query.domain)
    }

    private suspend fun queryTime(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allTimeEntryDtos(), query) { it }
        DataApi.OP_ACTIVITIES -> listPage(timeActivityDtos(), query) { it }
        else -> throw UnsupportedDomainException("time.${query.op}")
    }

    private suspend fun queryHabits(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allHabitDtos(), query) { it }
        DataApi.OP_HISTORY -> listPage(habitHistoryDtos(query), query) { it }
        else -> throw UnsupportedDomainException("habits.${query.op}")
    }

    private suspend fun queryCalendar(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allEventDtos(), query) { it }
        DataApi.OP_OCCURRENCES -> listPage(occurrenceDtos(query), query) { it }
        DataApi.OP_CALENDARS -> listPage(calendarDtos(), query) { it }
        else -> throw UnsupportedDomainException("calendar.${query.op}")
    }

    private suspend fun queryTasks(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> {
            val tagsByTask = taskTagMap()
            val ctxByTask = taskContextMap()
            listPage(allTasks(), query) { it.toDto(tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty()) }
        }
        DataApi.OP_GET -> {
            val t = idParam(query)?.let { repo.getTask(it) }
            onePage(t?.toDto(taskTagMap()[t.id].orEmpty(), taskContextMap()[t.id].orEmpty()))
        }
        DataApi.OP_LISTS -> listPage(taskListDtos(), query) { it }
        DataApi.OP_FOLDERS -> listPage(folderDtos(), query) { it }
        DataApi.OP_TAGS -> listPage(tagDtos(), query) { it }
        DataApi.OP_CONTEXTS -> listPage(contextDtos(), query) { it }
        DataApi.OP_FLAGS -> listPage(flagDtos(), query) { it }
        DataApi.OP_DEPENDENCIES -> listPage(dependencyDtos(), query) { it }
        DataApi.OP_REMINDERS -> listPage(reminderDtos(idParam(query)), query) { it }
        DataApi.OP_CHECKLIST -> listPage(checklistDtos(idParam(query)), query) { it }
        else -> throw UnsupportedDomainException("tasks.${query.op}")
    }

    // ---- task relation maps (one-shot from the reactive cross-ref flows) ----
    private suspend fun taskTagMap(): Map<String, List<String>> =
        repo.taskTagRefs.first().groupBy({ it.taskId }, { it.tagId })
    private suspend fun taskContextMap(): Map<String, List<String>> =
        repo.taskContextRefs.first().groupBy({ it.taskId }, { it.contextId })

    // ---- task sidebar/editor container + relation projections (active-workspace scoped) ----
    private suspend fun folderDtos(): List<FolderDto> {
        val ws = repo.activeWs()
        return repo.allFolders.first()
            .filter { it.workspaceId == ws && !it.archived && !it.trashed }
            .sortedBy { it.sortOrder }
            .map { FolderDto(it.id, it.name, it.parentId, it.icon, it.colorArgb, it.sortOrder, it.archived) }
    }
    private suspend fun tagDtos(): List<TagDto> {
        val ws = repo.activeWs()
        return repo.getTagsOnce().filter { it.workspaceId == ws }.sortedBy { it.sortOrder }
            .map { TagDto(it.id, it.name, it.colorArgb, it.parentId, it.sortOrder) }
    }
    private suspend fun contextDtos(): List<ContextDto> {
        val ws = repo.activeWs()
        return repo.getContextsOnce().filter { it.workspaceId == ws }.sortedBy { it.sortOrder }
            .map { ContextDto(it.id, it.name, it.icon, it.colorArgb, it.parentId, it.sortOrder) }
    }
    private suspend fun flagDtos(): List<FlagDto> {
        val ws = repo.activeWs()
        return repo.getFlagsOnce().filter { it.workspaceId == ws }.sortedBy { it.sortOrder }
            .map { FlagDto(it.id, it.name, it.colorArgb, it.icon, it.sortOrder) }
    }
    private suspend fun dependencyDtos(): List<DependencyDto> =
        repo.allDependencies.first().map { DependencyDto(it.taskId, it.dependsOnTaskId, it.mode, it.delayDays) }
    private suspend fun reminderDtos(taskId: String?): List<ReminderDto> {
        if (taskId.isNullOrBlank() || !isReadableTask(taskId)) return emptyList()
        return repo.remindersForTask(taskId).map {
            ReminderDto(it.id, it.taskId, it.type, it.atTime, it.offsetMin, it.repeatEveryMin, it.repeatCount, it.placeName, it.onEnter, it.annoying, it.escalate)
        }
    }
    private suspend fun checklistDtos(taskId: String?): List<ChecklistItemDto> {
        if (taskId.isNullOrBlank() || !isReadableTask(taskId)) return emptyList()
        return repo.checklistFor(taskId).map { ChecklistItemDto(it.id, it.taskId, it.text, it.checked, it.sortOrder) }
    }
    private suspend fun isReadableTask(id: String): Boolean = repo.wsTasksOnce().any { it.id == id }

    /** Read-only projection of the display-relevant settings so the web mirrors the phone's configuration. */
    private suspend fun settingsDto(): SettingsDto {
        val s = repo.settingsSnapshot()
        return SettingsDto(
            activeWorkspaceId = s.activeWorkspaceId,
            weekStart = s.weekStart,
            timeFormat = s.timeFormat.name,
            timeZone = s.timeZone,
            dayStartHour = s.dayStartHour,
            dayStartMinute = s.dayStartMinute,
            density = s.density.name,
            advancedPriority = s.advancedPriority,
            smartListVis = s.smartListVis.entries.associate { it.key.name to it.value.name },
            smartListNames = s.smartListNames,
            smartOrder = s.smartOrder,
            editorFieldTiers = s.editorFieldTiers,
            editorFieldOrder = s.editorFieldOrder,
            editorFieldFolded = s.editorFieldFolded.toList(),
            matrixImportanceThreshold = s.matrixImportanceThreshold,
            matrixUrgencyThreshold = s.matrixUrgencyThreshold,
            matrixShowCompleted = s.matrixShowCompleted,
            matrixHideEmpty = s.matrixHideEmpty,
            matrixNames = s.matrixNames,
            matrixListFilter = s.matrixListFilter.toList(),
            matrixFolderFilter = s.matrixFolderFilter.toList(),
            matrixMaxDuration = s.matrixMaxDuration,
            matrixOverdueOnly = s.matrixOverdueOnly,
            matrixDateFilter = s.matrixDateFilter,
            matrixSort = s.matrixSort,
            calendarDefaultMode = s.calendarDefaultMode,
            calendarRememberLast = s.calendarRememberLast,
            calendarShowCompleted = s.calendarShowCompleted,
            calendarListFilter = s.calendarListFilter.toList(),
            notesNotebookMode = s.notesNotebookMode,
            noteDefaultView = s.noteDefaultView,
            notesViewMode = s.notesViewMode,
            notesSort = s.notesSort,
            habitSort = s.habitSort,
            habitGroupByCategory = s.habitGroupByCategory,
            strengthMeter = s.strengthMeter,
            calmMode = s.calmMode,
            forgivingStreaks = s.forgivingStreaks,
            hideStreaks = s.hideStreaks,
            timeGridColumns = s.timeGridColumns,
        )
    }

    /** The active-workspace task lists for the sidebar, Inbox first, with open-task counts + tree info. */
    private suspend fun taskListDtos(): List<ListDto> {
        val ws = repo.activeWs()
        repo.ensureInbox()
        val openByList = allTasks().filter { isOpenTask(it) }.groupingBy { it.listId }.eachCount()
        return repo.allListsOnce()
            .filter { (it.workspaceId == ws || it.id == ListEntity.INBOX_ID) && !it.archived && !it.trashed }
            .sortedWith(compareByDescending<ListEntity> { it.id == ListEntity.INBOX_ID }.thenBy { it.sortOrder })
            .map {
                ListDto(
                    id = it.id,
                    name = it.name,
                    emoji = it.emoji,
                    colorArgb = it.colorArgb,
                    count = openByList[it.id] ?: 0,
                    folderId = it.folderId,
                    parentListId = it.parentListId,
                    sortOrder = it.sortOrder,
                    viewMode = it.viewMode,
                    archived = it.archived,
                )
            }
    }

    /** Mirrors the core's `isOpen`: a task that counts toward active/smart views. */
    private fun isOpenTask(t: TaskEntity) = !t.trashed && !t.completed && !t.abandoned && !t.someday

    private suspend fun queryNotes(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allNotes(), query) { it.toDto() }
        DataApi.OP_GET -> onePage(idParam(query)?.let { repo.getNote(it)?.toDto() })
        DataApi.OP_NOTEBOOKS -> listPage(notebookDtos(), query) { it }
        DataApi.OP_ATTACHMENTS -> listPage(noteAttachmentDtos(idParam(query)), query) { it }
        DataApi.OP_ATTACHMENT_DATA -> onePage(attachmentData(query))
        else -> throw UnsupportedDomainException("notes.${query.op}")
    }

    /** The active-workspace notebooks (names) for the notes sidebar, with non-trashed note counts. */
    private suspend fun notebookDtos(): List<ListDto> {
        val ws = repo.activeWs()
        val countByBook = allNotes().groupingBy { it.notebookId }.eachCount()
        return repo.getNotebooksOnce()
            .filter { it.workspaceId == ws && !it.archived }
            .sortedBy { it.sortOrder }
            .map { ListDto(id = it.id, name = it.name, emoji = it.icon, colorArgb = it.colorArgb, count = countByBook[it.id] ?: 0) }
    }

    /** A note's attachment metadata — only for a note in the active workspace (default-deny otherwise). */
    @Suppress("ReturnCount")
    private suspend fun noteAttachmentDtos(noteId: String?): List<AttachmentDto> {
        val note = noteId?.let { repo.getNote(it) } ?: return emptyList()
        if (note.workspaceId != repo.activeWs() || note.trashed) return emptyList()
        return repo.noteAttachments(note.id).map {
            AttachmentDto(
                id = it.id,
                fileName = it.fileName,
                mime = it.mime,
                sizeBytes = it.sizeBytes,
                isImage = it.isImage,
                noteId = it.noteId,
                taskId = it.taskId,
            )
        }
    }

    /** One attachment's bytes, inlined only for a small image on a readable note. Binary stays off the spine. */
    @Suppress("ReturnCount") // sequential default-deny guards read clearest as early returns
    private suspend fun attachmentData(query: DataQuery): AttachmentData? {
        val params = runCatching { BridgeCodec.decodeString<Map<String, String>>(query.paramsJson) }.getOrNull()
        val attId = params?.get("id") ?: return null
        val noteId = params["noteId"] ?: return null
        val note = repo.getNote(noteId) ?: return null
        if (note.workspaceId != repo.activeWs() || note.trashed) return null
        val att = repo.noteAttachments(noteId).firstOrNull { it.id == attId } ?: return null
        if (!att.isImage || att.sizeBytes > DataApi.INLINE_ATTACHMENT_MAX_BYTES) {
            return AttachmentData(id = att.id, mime = att.mime, isImage = att.isImage, tooLarge = true)
        }
        val b64 = when {
            !att.filePath.isNullOrEmpty() ->
                runCatching { java.util.Base64.getEncoder().encodeToString(java.io.File(att.filePath!!).readBytes()) }.getOrNull()
            att.contentBase64.isNotEmpty() -> att.contentBase64
            else -> null
        } ?: return AttachmentData(id = att.id, mime = att.mime, isImage = att.isImage, tooLarge = true)
        return AttachmentData(id = att.id, mime = att.mime, isImage = true, dataB64 = b64)
    }

    /** The breadth domains (calendar/time/habits) are list-only; the DTOs are already projected. */
    private suspend inline fun <reified D> queryListOnly(query: DataQuery, load: () -> List<D>): DataPage {
        if (query.op != DataApi.OP_LIST) throw UnsupportedDomainException("${query.domain}.${query.op}")
        return listPage(load(), query) { it }
    }

    override suspend fun mutate(mutation: DataMutation): DataResult = when (mutation.domain) {
        DataApi.DOMAIN_TASKS -> mutateTask(mutation)
        DataApi.DOMAIN_NOTES -> mutateNote(mutation)
        DataApi.DOMAIN_CALENDAR -> mutateCalendar(mutation)
        DataApi.DOMAIN_TIME -> mutateTime(mutation)
        DataApi.DOMAIN_HABITS -> mutateHabits(mutation)
        else -> throw UnsupportedDomainException(mutation.domain)
    }

    override fun changes(): Flow<String> = flow {
        val ws = repo.activeWs()
        // Room emits current state on subscribe; drop(1) keeps that initial frame from firing a redundant
        // reload — only real subsequent changes become ticks. The broad (unscoped) observers for
        // calendar/time/habits are fine here: a tick only says "this domain changed", never carries data,
        // and the handler filters ticks to the domains the caller may read.
        val tasks = repo.observeTasksByWorkspace(ws).drop(1).map { DataApi.DOMAIN_TASKS }
        // Containers/relations also tick the tasks domain so the sidebar (lists/folders/tags/contexts/flags)
        // and dependency-derived views refresh when they change on the phone.
        val containers = merge(
            repo.allFolders.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.allLists.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.allTags.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.allContexts.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.allFlags.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.allDependencies.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.taskTagRefs.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.taskContextRefs.drop(1).map { DataApi.DOMAIN_TASKS },
            repo.allChecklist.drop(1).map { DataApi.DOMAIN_TASKS },
        )
        val notes = repo.observeNotesByWorkspace(ws, trashed = false).drop(1).map { DataApi.DOMAIN_NOTES }
        val calendar = repo.allEvents.drop(1).map { DataApi.DOMAIN_CALENDAR }
        val time = repo.allTimeEntries.drop(1).map { DataApi.DOMAIN_TIME }
        val habits = repo.allHabits.drop(1).map { DataApi.DOMAIN_HABITS }
        val checkins = repo.allCheckins.drop(1).map { DataApi.DOMAIN_HABITS }
        val settings = repo.allSettings.drop(1).map { DataApi.DOMAIN_SETTINGS }
        emitAll(merge(tasks, containers, notes, calendar, time, habits, checkins, settings))
    }

    // ---- writes -------------------------------------------------------------------------------------

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // a per-op write facade
    private suspend fun mutateTask(mutation: DataMutation): DataResult {
        val p = mutation.payloadJson
        return when (mutation.op) {
            DataApi.OP_UPSERT -> upsertTask(decode<TaskDto>(p) ?: return bad("invalid task"))
            DataApi.OP_COMPLETE -> {
                val c = decode<CompleteParam>(p) ?: return bad("invalid complete")
                if (repo.getTask(c.id) == null) return bad("not_found"); repo.setCompletedById(c.id, c.completed); ok(c.id)
            }
            DataApi.OP_DELETE -> {
                val id = decode<IdParam>(p)?.id ?: return bad("invalid id")
                if (repo.getTask(id) == null) return bad("not_found"); repo.setTrashed(id, trashed = true, workspaceId = repo.activeWs()); ok(id)
            }
            DataApi.OP_RESTORE -> {
                val id = decode<IdParam>(p)?.id ?: return bad("invalid id")
                if (repo.getTask(id) == null) return bad("not_found"); repo.setTrashed(id, trashed = false, workspaceId = repo.activeWs()); ok(id)
            }
            DataApi.OP_STAR -> { val s = decode<StarParam>(p) ?: return bad("invalid"); val t = repo.getTask(s.id) ?: return bad("not_found"); repo.saveTask(t.copy(star = s.star)); ok(s.id) }
            DataApi.OP_ABANDON -> { val s = decode<AbandonParam>(p) ?: return bad("invalid"); val t = repo.getTask(s.id) ?: return bad("not_found"); repo.saveTask(t.copy(abandoned = s.abandoned)); ok(s.id) }
            DataApi.OP_SET_PRIORITY -> { val s = decode<PriorityParam>(p) ?: return bad("invalid"); val t = repo.getTask(s.id) ?: return bad("not_found"); repo.saveTask(t.copy(importance = s.importance.coerceIn(1, 5), urgency = s.urgency.coerceIn(1, 5))); ok(s.id) }
            DataApi.OP_FLAG -> { val s = decode<FlagParam>(p) ?: return bad("invalid"); val t = repo.getTask(s.id) ?: return bad("not_found"); repo.setTaskFlag(t, s.flagId); ok(s.id) }
            DataApi.OP_MOVE -> { val s = decode<MoveParam>(p) ?: return bad("invalid"); val t = repo.getTask(s.id) ?: return bad("not_found"); repo.saveTask(t.copy(listId = s.listId ?: t.listId, folderId = s.folderId)); ok(s.id) }
            DataApi.OP_SET_TAGS -> { val s = decode<TagsParam>(p) ?: return bad("invalid"); if (repo.getTask(s.id) == null) return bad("not_found"); repo.setTaskTags(s.id, s.tagIds); ok(s.id) }
            DataApi.OP_SET_CONTEXTS -> { val s = decode<ContextsParam>(p) ?: return bad("invalid"); if (repo.getTask(s.id) == null) return bad("not_found"); repo.setTaskContexts(s.id, s.contextIds); ok(s.id) }
            DataApi.OP_SUBTASK -> {
                val s = decode<SubtaskParam>(p) ?: return bad("invalid"); val parent = repo.getTask(s.id) ?: return bad("not_found")
                if (s.title.isBlank()) return bad("empty_title"); ok(repo.createTask(listId = parent.listId, title = s.title.trim(), parentId = parent.id))
            }
            DataApi.OP_CHECKLIST_ADD -> { val s = decode<ChecklistAddParam>(p) ?: return bad("invalid"); if (repo.getTask(s.id) == null || s.text.isBlank()) return bad("invalid"); repo.addChecklistItem(s.id, s.text.trim()); ok(s.id) }
            DataApi.OP_CHECKLIST_SET -> {
                val s = decode<ChecklistSetParam>(p) ?: return bad("invalid"); val item = repo.checklistFor(s.taskId).firstOrNull { it.id == s.id } ?: return bad("not_found")
                repo.saveChecklistItem(item.copy(checked = s.checked ?: item.checked, text = s.text ?: item.text)); ok(s.id)
            }
            DataApi.OP_CHECKLIST_DELETE -> { val id = decode<IdParam>(p)?.id ?: return bad("invalid id"); repo.deleteChecklistItem(id); ok(id) }
            DataApi.OP_REMINDER_ADD -> {
                val r = decode<ReminderDto>(p) ?: return bad("invalid"); if (!isReadableTask(r.taskId)) return bad("not_found")
                val id = r.id.ifBlank { java.util.UUID.randomUUID().toString() }
                repo.upsertReminder(ReminderEntity(id = id, taskId = r.taskId, type = r.type, atTime = r.atTime, offsetMin = r.offsetMin, repeatEveryMin = r.repeatEveryMin, repeatCount = r.repeatCount, placeName = r.placeName, onEnter = r.onEnter, annoying = r.annoying, escalate = r.escalate)); ok(id)
            }
            DataApi.OP_REMINDER_DELETE -> { val id = decode<IdParam>(p)?.id ?: return bad("invalid id"); repo.deleteReminder(id); ok(id) }
            DataApi.OP_DEP_ADD -> { val d = decode<DepParam>(p) ?: return bad("invalid"); if (!isReadableTask(d.taskId) || !isReadableTask(d.dependsOnTaskId)) return bad("not_found"); repo.addDependency(d.taskId, d.dependsOnTaskId, d.mode, d.delayDays); ok(d.taskId) }
            DataApi.OP_DEP_REMOVE -> { val d = decode<DepParam>(p) ?: return bad("invalid"); repo.removeDependency(DependencyEntity(d.taskId, d.dependsOnTaskId)); ok(d.taskId) }
            DataApi.OP_BULK -> bulkTasks(decode<BulkOp>(p) ?: return bad("invalid bulk"))
            else -> throw UnsupportedDomainException("tasks.${mutation.op}")
        }
    }

    @Suppress("ReturnCount")
    private suspend fun bulkTasks(op: BulkOp): DataResult {
        val ids = op.ids.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return bad("empty_ids")
        var applied = 0
        for (id in ids) {
            val task = repo.getTask(id) ?: continue
            when (op.action) {
                "complete" -> repo.setCompletedById(id, op.value)
                "delete" -> repo.setTrashed(id, trashed = true, workspaceId = repo.activeWs())
                "star" -> repo.saveTask(task.copy(star = op.value))
                else -> return bad("unknown_action")
            }
            applied++
        }
        return DataResult(ok = true, payloadJson = BridgeCodec.encodeString(CountResult(applied)))
    }

    // The editor's authoritative whole-task save: the web sends the FULL current DTO, so every editable field
    // is applied. (Field-scoped ops — star/flag/move/setPriority — are for inline edits that must not touch
    // other fields.) A recurring master keeps its recurrence rule unless the DTO changes it.
    @Suppress("ReturnCount")
    private suspend fun upsertTask(dto: TaskDto): DataResult {
        val existing = dto.id.takeIf { it.isNotBlank() }?.let { repo.getTask(it) }
        if (existing != null) {
            val flagColor = dto.flagId?.let { fid -> repo.getFlagsOnce().firstOrNull { it.id == fid }?.colorArgb }
            repo.saveTask(
                existing.copy(
                    title = dto.title.ifBlank { existing.title }, note = dto.note ?: "",
                    listId = dto.listId ?: existing.listId, folderId = dto.folderId,
                    dueDate = dto.dueDate, startDate = dto.startDate, deadlineDate = dto.deadlineDate, isAllDay = dto.isAllDay,
                    importance = dto.importance.coerceIn(1, 5), urgency = dto.urgency.coerceIn(1, 5), energy = dto.energy,
                    durationMin = dto.durationMin, estimateMin = dto.estimateMin, estimateMax = dto.estimateMax, leadTimeMin = dto.leadTimeMin,
                    star = dto.star, flagId = dto.flagId, flagColorArgb = flagColor, pinned = dto.pinned, someday = dto.someday,
                    isGoal = dto.isGoal, isProject = dto.isProject, progressPct = dto.progressPct, completeInOrder = dto.completeInOrder,
                    rrule = dto.rrule, recurrenceMode = dto.recurrenceMode, waitingForWho = dto.waitingForWho,
                    defaultActivityId = dto.defaultActivityId, reviewEveryDays = dto.reviewEveryDays, colorArgb = dto.colorArgb,
                    winFlag = dto.winFlag, mood = dto.mood, outcomeNote = dto.outcomeNote, learnedNote = dto.learnedNote,
                    praiseQuote = dto.praiseQuote, whyText = dto.whyText, rewardText = dto.rewardText,
                ),
            )
            return ok(existing.id)
        }
        if (dto.title.isBlank()) return bad("empty_title")
        repo.ensureInbox()
        val id = repo.createTask(
            listId = dto.listId ?: ListEntity.INBOX_ID, title = dto.title.trim(), parentId = dto.parentId,
            importance = dto.importance, urgency = dto.urgency, dueDate = dto.dueDate, startDate = dto.startDate, folderId = dto.folderId,
        )
        // Apply the fields createTask doesn't take.
        repo.getTask(id)?.let { t ->
            repo.saveTask(
                t.copy(
                    note = dto.note ?: "", star = dto.star, flagId = dto.flagId, someday = dto.someday, isAllDay = dto.isAllDay,
                    deadlineDate = dto.deadlineDate, energy = dto.energy, estimateMin = dto.estimateMin, rrule = dto.rrule, isGoal = dto.isGoal,
                ),
            )
        }
        return ok(id)
    }

    @Suppress("ReturnCount") // guard-clause early returns read clearest here
    private suspend fun mutateNote(mutation: DataMutation): DataResult {
        return when (mutation.op) {
            DataApi.OP_UPSERT -> upsertNote(decode<NoteDto>(mutation.payloadJson) ?: return bad("invalid note"))
            DataApi.OP_DELETE -> {
                val id = decode<IdParam>(mutation.payloadJson)?.id ?: return bad("invalid id")
                if (repo.getNote(id) == null) return bad("not_found")
                repo.trashNote(id, trashed = true)
                ok(id)
            }
            else -> throw UnsupportedDomainException("notes.${mutation.op}")
        }
    }

    private suspend fun upsertNote(dto: NoteDto): DataResult {
        val existing = dto.id.takeIf { it.isNotBlank() }?.let { repo.getNote(it) }
        // Never let a plaintext web edit overwrite a vaulted (encrypted) note's ciphertext body.
        if (existing != null && existing.vault) return bad("note_vaulted")
        val entity = existing?.copy(
            title = dto.title,
            body = dto.body,
            pinned = dto.pinned,
            archived = dto.archived,
            colorArgb = dto.colorArgb,
            coverEmoji = dto.coverEmoji?.ifBlank { null },
        ) ?: NoteEntity(
            id = "",
            title = dto.title,
            body = dto.body,
            notebookId = dto.notebookId,
            pinned = dto.pinned,
            archived = dto.archived,
            colorArgb = dto.colorArgb,
            coverEmoji = dto.coverEmoji?.ifBlank { null },
            workspaceId = repo.activeWs(),
        )
        return ok(repo.upsertNote(entity))
    }

    // ---- calendar writes (R3b) ----------------------------------------------------------------------
    //
    // Create / edit / delete an event in the active workspace. Deletes are a HARD delete (events have no
    // Trash on-device either — the mobile delete is the same), so the web confirms first. Alarms are
    // rescheduled/cancelled through the available Context so a web-created event still fires its alerts and
    // an edited/deleted one never leaves a stale alarm pointing at the old time.
    @Suppress("ReturnCount") // guard-clause early returns read clearest here
    private suspend fun mutateCalendar(mutation: DataMutation): DataResult {
        return when (mutation.op) {
            DataApi.OP_UPSERT -> upsertEvent(decode<EventDto>(mutation.payloadJson) ?: return bad("invalid event"))
            DataApi.OP_DELETE -> {
                val id = decode<IdParam>(mutation.payloadJson)?.id ?: return bad("invalid id")
                val existing = repo.eventById(id) ?: return bad("not_found")
                if (!isEventInActiveWs(existing)) return bad("not_found") // default-deny across workspaces
                appContext?.let { AlarmScheduler.cancelEventAlerts(it, existing) }
                repo.deleteEvent(id)
                ok(id)
            }
            else -> throw UnsupportedDomainException("calendar.${mutation.op}")
        }
    }

    @Suppress("ReturnCount")
    private suspend fun upsertEvent(dto: EventDto): DataResult {
        if (dto.title.isBlank()) return bad("empty_title")
        val existing = dto.id.takeIf { it.isNotBlank() }?.let { repo.eventById(it) }
        if (existing != null && !isEventInActiveWs(existing)) return bad("not_found")
        val calId = resolveCalendarId(dto.calendarId.takeIf { it.isNotBlank() } ?: existing?.calendarId)
        val now = System.currentTimeMillis()
        // Timed events need a positive duration; default a bare start to a one-hour block. All-day bounds are
        // normalised to a floating UTC-midnight marker by the repository on write (Surfaced #3).
        var start = dto.startMillis
        var end = dto.endMillis
        if (!dto.allDay && end <= start) end = start + ONE_HOUR_MS
        val base = existing ?: EventEntity(
            id = java.util.UUID.randomUUID().toString(),
            calendarId = calId, title = dto.title.trim(),
            startMillis = start, endMillis = end, createdAt = now, updatedAt = now,
        )
        // A recurring master's times/all-day define the whole series; the web only sends an occurrence's
        // realised time, so rescheduling a series from here would silently move it. Keep the master's stored
        // timing and let the web edit only text/calendar for recurring events (times stay a phone operation).
        val isRecurring = existing?.rrule?.isNotBlank() == true
        val entity = base.copy(
            calendarId = calId,
            title = dto.title.trim(),
            location = dto.location.trim(),
            notes = dto.notes.trim(),
            startMillis = if (isRecurring) base.startMillis else start,
            endMillis = if (isRecurring) base.endMillis else end,
            allDay = if (isRecurring) base.allDay else dto.allDay,
            colorArgb = dto.colorArgb,
            updatedAt = now,
        )
        // Cancel the old schedule before moving times, then (re)schedule against the saved entity.
        appContext?.let { ctx -> existing?.let { AlarmScheduler.cancelEventAlerts(ctx, it) } }
        repo.upsertEvent(entity)
        appContext?.let { ctx -> AlarmScheduler.scheduleEventAlerts(ctx, entity) }
        return ok(entity.id)
    }

    private suspend fun isEventInActiveWs(e: EventEntity): Boolean {
        val ws = repo.activeWs()
        return repo.eventCalendarsOnce().any { it.id == e.calendarId && it.workspaceId == ws }
    }

    /** The calendar a web-created event lands in: the requested one (if in this workspace), else the
     *  workspace default, else its first calendar — creating a "Personal" default if the workspace has none. */
    private suspend fun resolveCalendarId(requested: String?): String {
        val ws = repo.activeWs()
        val wsCals = repo.eventCalendarsOnce().filter { it.workspaceId == ws }
        requested?.takeIf { rid -> wsCals.any { it.id == rid } }?.let { return it }
        wsCals.firstOrNull { it.isDefault }?.let { return it.id }
        wsCals.firstOrNull()?.let { return it.id }
        val id = java.util.UUID.randomUUID().toString()
        repo.upsertEventCalendar(
            EventCalendarEntity(
                id = id, name = "Personal", colorArgb = DEFAULT_CAL_COLOR, isDefault = true,
                orderIndex = 0, workspaceId = ws, createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    // ---- time writes (R3d): start / stop a timer ----------------------------------------------------
    @Suppress("ReturnCount")
    private suspend fun mutateTime(mutation: DataMutation): DataResult {
        return when (mutation.op) {
            DataApi.OP_START -> {
                val activityId = decode<ActivityParam>(mutation.payloadJson)?.activityId ?: return bad("invalid activity")
                val act = repo.wsTimeActivitiesOnce().firstOrNull { it.id == activityId } ?: return bad("not_found")
                ok(repo.startTimeTracking(act.id)) // stopFirst = true: one running timer at a time
            }
            DataApi.OP_STOP -> {
                val id = decode<IdParam>(mutation.payloadJson)?.id?.takeIf { it.isNotBlank() }
                if (id != null) repo.stopTimeEntry(id) else repo.stopTimeTracking()
                ok(id ?: "")
            }
            else -> throw UnsupportedDomainException("time.${mutation.op}")
        }
    }

    // ---- habit writes (R3d): tap today's check-in (cycles up to target, then clears) ----------------
    @Suppress("ReturnCount")
    private suspend fun mutateHabits(mutation: DataMutation): DataResult {
        return when (mutation.op) {
            DataApi.OP_CHECKIN -> {
                val id = decode<IdParam>(mutation.payloadJson)?.id ?: return bad("invalid id")
                val habit = repo.wsHabitsOnce().firstOrNull { it.id == id } ?: return bad("not_found")
                val today = java.time.LocalDate.now().toEpochDay()
                val current = repo.getHabitCheckinsOnce().firstOrNull { it.habitId == id && it.epochDay == today }?.count ?: 0
                repo.cycleCheckin(habitId = id, epochDay = today, target = habit.targetPerDay, current = current)
                ok(id)
            }
            else -> throw UnsupportedDomainException("habits.${mutation.op}")
        }
    }

    private inline fun <reified T> decode(json: String): T? =
        runCatching { BridgeCodec.decodeString<T>(json) }.getOrNull()

    private fun ok(id: String) = DataResult(ok = true, payloadJson = BridgeCodec.encodeString(IdParam(id)))
    private fun bad(reason: String) = DataResult(ok = false, error = reason)

    // ---- reads --------------------------------------------------------------------------------------

    // The full active-workspace task set (inbox by own workspace, others by their list/folder's workspace —
    // the exact SQL rule the mobile app uses), INCLUDING completed/abandoned/someday/trashed so the web can
    // compute every smart list (All/Today/…/Completed/Won't Do/Trash) itself. Ordered by sortOrder.
    private suspend fun allTasks(): List<TaskEntity> = repo.wsTasksOnce().sortedBy { it.sortOrder }

    private suspend fun allNotes(): List<NoteEntity> {
        val ws = repo.activeWs()
        return repo.getNotesOnce().asSequence()
            .filter { it.workspaceId == ws && !it.trashed }
            .sortedWith(compareByDescending<NoteEntity> { it.pinned }.thenByDescending { it.updatedAt })
            .toList()
    }

    // ---- breadth reads (W3): calendar / time / habits, all active-workspace scoped --------------------

    private suspend fun allEventDtos(): List<EventDto> {
        val calById = repo.eventCalendarsOnce().associateBy { it.id }
        return repo.wsEventsOnce()
            .sortedBy { it.startMillis }
            .map { e -> e.toDto(calById[e.calendarId]?.name.orEmpty()) }
    }

    /** The workspace's calendars, for the web legend + create picker (default first, then creation order). */
    private suspend fun calendarDtos(): List<CalendarDto> {
        val ws = repo.activeWs()
        return repo.eventCalendarsOnce()
            .filter { it.workspaceId == ws }
            .sortedWith(compareByDescending<EventCalendarEntity> { it.isDefault }.thenBy { it.orderIndex })
            .map { CalendarDto(id = it.id, name = it.name, colorArgb = it.colorArgb, visible = it.visible, isDefault = it.isDefault) }
    }

    /**
     * Expand every (visible) event that intersects the requested window into concrete occurrences — this is
     * what the month/week/agenda grids read, so a weekly event shows on every matching day, not just its
     * first. Each occurrence keeps its master event's id so a web edit/delete maps back to the series.
     */
    private suspend fun occurrenceDtos(query: DataQuery): List<EventDto> {
        val params = runCatching { BridgeCodec.decodeString<Map<String, Long>>(query.paramsJson) }.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val from = params["from"] ?: (now - 7L * 86_400_000L)
        val to = (params["to"] ?: (now + 45L * 86_400_000L)).coerceAtLeast(from)
        val calById = repo.eventCalendarsOnce().associateBy { it.id }
        val zone = AppClock.zone
        return CalendarEngine.expand(repo.wsEventsOnce(), from, to, zone)
            .sortedBy { it.startMillis }
            .map { occ ->
                occ.event.toDto(calById[occ.event.calendarId]?.name.orEmpty())
                    .copy(startMillis = occ.startMillis, endMillis = occ.endMillis)
            }
    }

    private suspend fun allTimeEntryDtos(): List<TimeEntryDto> {
        val ws = repo.activeWs()
        val actById = repo.wsTimeActivitiesOnce().associateBy { it.id }
        val now = System.currentTimeMillis()
        return repo.timeEntriesOnce()
            .filter { it.workspaceId == ws }
            .sortedByDescending { it.startMillis }
            .map { te ->
                TimeEntryDto(
                    id = te.id,
                    activityId = te.activityId,
                    activityName = actById[te.activityId]?.name.orEmpty(),
                    startMillis = te.startMillis,
                    endMillis = te.endMillis,
                    minutes = te.minutes(now),
                    note = te.note,
                    kind = te.kind,
                    running = te.running,
                )
            }
    }

    /** The active-workspace time activities (for the start-timer picker + report legend), live first. */
    private suspend fun timeActivityDtos(): List<TimeActivityDto> =
        repo.wsTimeActivitiesOnce()
            .filter { !it.archived }
            .sortedBy { it.sortOrder }
            .map {
                TimeActivityDto(
                    id = it.id, name = it.name, emoji = it.emoji, colorArgb = it.colorArgb,
                    goalMinutesPerDay = it.goalMinutesPerDay, goalDays = it.goalDays, sortOrder = it.sortOrder,
                )
            }

    /** Per-habit per-day check-in cells over the last N days (default 84 ≈ 12 weeks) for the heatmap. */
    private suspend fun habitHistoryDtos(query: DataQuery): List<HabitDayDto> {
        val days = (runCatching { BridgeCodec.decodeString<Map<String, Int>>(query.paramsJson) }.getOrNull().orEmpty()["days"] ?: DEFAULT_HISTORY_DAYS)
            .coerceIn(1, MAX_HISTORY_DAYS)
        val today = java.time.LocalDate.now().toEpochDay()
        val from = today - days + 1
        val targetById = repo.wsHabitsOnce().associate { it.id to it.targetPerDay.coerceAtLeast(1) }
        return repo.getHabitCheckinsOnce()
            .filter { it.habitId in targetById && it.epochDay in from..today }
            .map {
                val target = targetById[it.habitId] ?: 1
                HabitDayDto(
                    habitId = it.habitId, epochDay = it.epochDay, count = it.count,
                    done = it.status == "done", target = target,
                    goalMet = it.status == "done" && it.count >= target, skip = it.status == "skip",
                )
            }
    }

    private suspend fun allHabitDtos(): List<HabitDto> {
        val today = java.time.LocalDate.now().toEpochDay()
        val todayCheckins = repo.getHabitCheckinsOnce().filter { it.epochDay == today }.associateBy { it.habitId }
        return repo.wsHabitsOnce()
            .sortedBy { it.sortOrder }
            .map { h ->
                val c = todayCheckins[h.id]
                HabitDto(
                    id = h.id,
                    name = h.name,
                    emoji = h.emoji,
                    colorArgb = h.colorArgb,
                    targetPerDay = h.targetPerDay,
                    unit = h.unit,
                    habitType = h.habitType,
                    targetComparison = h.targetComparison,
                    category = h.category,
                    scheduleDays = h.scheduleDays,
                    reminderTimes = h.reminderTimes,
                    freqType = h.freqType,
                    freqParam = h.freqParam,
                    clickIncrement = h.clickIncrement,
                    extraTarget = h.extraTarget,
                    startDate = h.startDate,
                    sortOrder = h.sortOrder,
                    paused = h.paused,
                    todayCount = c?.count ?: 0,
                    doneToday = c?.status == "done",
                )
            }
    }

    // ---- paging + encoding --------------------------------------------------------------------------

    private inline fun <E, reified D> listPage(all: List<E>, query: DataQuery, map: (E) -> D): DataPage {
        val offset = query.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = query.limit.coerceIn(1, MAX_PAGE)
        val slice = all.drop(offset).take(limit).map(map)
        val next = (offset + limit).takeIf { it < all.size }?.toString()
        return DataPage(payloadJson = BridgeCodec.encodeString(slice), nextCursor = next, total = all.size)
    }

    private inline fun <reified D> onePage(dto: D?): DataPage =
        DataPage(payloadJson = if (dto == null) "null" else BridgeCodec.encodeString(dto))

    private fun idParam(query: DataQuery): String? =
        runCatching { BridgeCodec.decodeString<Map<String, String>>(query.paramsJson)["id"] }.getOrNull()

    private companion object {
        const val MAX_PAGE = 500
        const val ONE_HOUR_MS = 3_600_000L
        const val DEFAULT_CAL_COLOR = 0xFF4F46E5L // indigo, matching the core's on-demand default calendar
        const val DEFAULT_HISTORY_DAYS = 84 // ≈ 12 weeks of habit heatmap
        const val MAX_HISTORY_DAYS = 371 // a year-plus, bounded
    }
}

// ---- op params (the small JSON shapes for complete/delete; upsert uses the full DTO) ------------------

@Serializable
private data class IdParam(val id: String)

@Serializable
private data class ActivityParam(val activityId: String)

@Serializable
private data class StarParam(val id: String, val star: Boolean = true)

@Serializable
private data class AbandonParam(val id: String, val abandoned: Boolean = true)

@Serializable
private data class PriorityParam(val id: String, val importance: Int = 3, val urgency: Int = 3)

@Serializable
private data class FlagParam(val id: String, val flagId: String? = null)

@Serializable
private data class MoveParam(val id: String, val listId: String? = null, val folderId: String? = null)

@Serializable
private data class TagsParam(val id: String, val tagIds: List<String> = emptyList())

@Serializable
private data class ContextsParam(val id: String, val contextIds: List<String> = emptyList())

@Serializable
private data class SubtaskParam(val id: String, val title: String)

@Serializable
private data class ChecklistAddParam(val id: String, val text: String)

@Serializable
private data class ChecklistSetParam(val id: String, val taskId: String, val checked: Boolean? = null, val text: String? = null)

@Serializable
private data class DepParam(val taskId: String, val dependsOnTaskId: String, val mode: String = "AND", val delayDays: Int = 0)

@Serializable
private data class CompleteParam(val id: String, val completed: Boolean = true)

/** A bulk task op: an [action] (complete / delete / star) applied to many [ids]; [value] is the on/off flag. */
@Serializable
private data class BulkOp(val ids: List<String> = emptyList(), val action: String = "", val value: Boolean = true)

@Serializable
private data class CountResult(val count: Int)

// ---- entity → DTO projections (the only fields that leave the core) ----------------------------------

private fun TaskEntity.toDto(tagIds: List<String> = emptyList(), contextIds: List<String> = emptyList()) = TaskDto(
    id = id,
    title = title,
    listId = listId,
    folderId = folderId,
    parentId = parentId,
    completed = completed,
    completedAt = completedAt,
    abandoned = abandoned,
    someday = someday,
    dueDate = dueDate,
    startDate = startDate,
    deadlineDate = deadlineDate,
    isAllDay = isAllDay,
    importance = importance,
    urgency = urgency,
    energy = energy,
    durationMin = durationMin,
    estimateMin = estimateMin,
    estimateMax = estimateMax,
    leadTimeMin = leadTimeMin,
    star = star,
    flagId = flagId,
    flagColorArgb = flagColorArgb,
    pinned = pinned,
    isNote = isNote,
    isGoal = isGoal,
    isProject = isProject,
    progressPct = progressPct,
    completeInOrder = completeInOrder,
    rrule = rrule,
    recurrenceMode = recurrenceMode,
    waitingForWho = waitingForWho,
    delegatedOn = delegatedOn,
    defaultActivityId = defaultActivityId,
    reviewEveryDays = reviewEveryDays,
    colorArgb = colorArgb,
    sortOrder = sortOrder,
    note = note.ifBlank { null },
    tagIds = tagIds,
    contextIds = contextIds,
    winFlag = winFlag,
    mood = mood,
    outcomeNote = outcomeNote,
    learnedNote = learnedNote,
    praiseQuote = praiseQuote,
    whyText = whyText,
    rewardText = rewardText,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun NoteEntity.toDto() = NoteDto(
    id = id,
    title = title,
    body = body,
    notebookId = notebookId,
    folderId = folderId,
    pinned = pinned,
    archived = archived,
    colorArgb = colorArgb,
    coverEmoji = coverEmoji,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun EventEntity.toDto(calendarName: String) = EventDto(
    id = id,
    title = title,
    calendarId = calendarId,
    calendarName = calendarName,
    location = location,
    notes = notes,
    startMillis = startMillis,
    endMillis = endMillis,
    allDay = allDay,
    recurring = rrule.isNotBlank(),
    colorArgb = colorArgb,
    linkedTaskId = linkedTaskId,
)
