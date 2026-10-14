package com.wkhan.hexis.webbridge

import android.content.Context
import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.data.AttachmentData
import com.wkhan.hexis.bridge.data.AttachmentDto
import com.wkhan.hexis.bridge.data.CalendarDto
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataPage
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.data.DataResult
import com.wkhan.hexis.bridge.data.EventDto
import com.wkhan.hexis.bridge.data.HabitDto
import com.wkhan.hexis.bridge.data.ListDto
import com.wkhan.hexis.bridge.data.NoteDto
import com.wkhan.hexis.bridge.data.TaskDto
import com.wkhan.hexis.bridge.data.TimeEntryDto
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.EventCalendarEntity
import com.wkhan.hexis.data.entity.EventEntity
import com.wkhan.hexis.data.entity.ListEntity
import com.wkhan.hexis.data.entity.NoteEntity
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
        DataApi.DOMAIN_TIME -> queryListOnly(query) { allTimeEntryDtos() }
        DataApi.DOMAIN_HABITS -> queryListOnly(query) { allHabitDtos() }
        else -> throw UnsupportedDomainException(query.domain)
    }

    private suspend fun queryCalendar(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allEventDtos(), query) { it }
        DataApi.OP_OCCURRENCES -> listPage(occurrenceDtos(query), query) { it }
        DataApi.OP_CALENDARS -> listPage(calendarDtos(), query) { it }
        else -> throw UnsupportedDomainException("calendar.${query.op}")
    }

    private suspend fun queryTasks(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allTasks(), query) { it.toDto() }
        DataApi.OP_GET -> onePage(idParam(query)?.let { repo.getTask(it)?.toDto() })
        DataApi.OP_LISTS -> listPage(taskListDtos(), query) { it }
        else -> throw UnsupportedDomainException("tasks.${query.op}")
    }

    /** The active-workspace task lists (names) for the sidebar, Inbox first, with open-task counts. */
    private suspend fun taskListDtos(): List<ListDto> {
        val ws = repo.activeWs()
        repo.ensureInbox()
        val openByList = allTasks().filter { !it.completed }.groupingBy { it.listId }.eachCount()
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
                )
            }
    }

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
        else -> throw UnsupportedDomainException(mutation.domain)
    }

    override fun changes(): Flow<String> = flow {
        val ws = repo.activeWs()
        // Room emits current state on subscribe; drop(1) keeps that initial frame from firing a redundant
        // reload — only real subsequent changes become ticks. The broad (unscoped) observers for
        // calendar/time/habits are fine here: a tick only says "this domain changed", never carries data,
        // and the handler filters ticks to the domains the caller may read.
        val tasks = repo.observeTasksByWorkspace(ws).drop(1).map { DataApi.DOMAIN_TASKS }
        val notes = repo.observeNotesByWorkspace(ws, trashed = false).drop(1).map { DataApi.DOMAIN_NOTES }
        val calendar = repo.allEvents.drop(1).map { DataApi.DOMAIN_CALENDAR }
        val time = repo.allTimeEntries.drop(1).map { DataApi.DOMAIN_TIME }
        val habits = repo.allHabits.drop(1).map { DataApi.DOMAIN_HABITS }
        val checkins = repo.allCheckins.drop(1).map { DataApi.DOMAIN_HABITS }
        emitAll(merge(tasks, notes, calendar, time, habits, checkins))
    }

    // ---- writes -------------------------------------------------------------------------------------

    @Suppress("ReturnCount") // guard-clause early returns read clearest here
    private suspend fun mutateTask(mutation: DataMutation): DataResult {
        return when (mutation.op) {
            DataApi.OP_UPSERT -> upsertTask(decode<TaskDto>(mutation.payloadJson) ?: return bad("invalid task"))
            DataApi.OP_COMPLETE -> {
                val p = decode<CompleteParam>(mutation.payloadJson) ?: return bad("invalid complete")
                if (repo.getTask(p.id) == null) return bad("not_found")
                repo.setCompletedById(p.id, p.completed)
                ok(p.id)
            }
            DataApi.OP_DELETE -> {
                val id = decode<IdParam>(mutation.payloadJson)?.id ?: return bad("invalid id")
                if (repo.getTask(id) == null) return bad("not_found")
                repo.setTrashed(id, trashed = true, workspaceId = repo.activeWs()) // reversible: moved to Trash
                ok(id)
            }
            DataApi.OP_BULK -> bulkTasks(decode<BulkOp>(mutation.payloadJson) ?: return bad("invalid bulk"))
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

    @Suppress("ReturnCount")
    private suspend fun upsertTask(dto: TaskDto): DataResult {
        val existing = dto.id.takeIf { it.isNotBlank() }?.let { repo.getTask(it) }
        if (existing != null) {
            repo.saveTask(
                existing.copy(
                    title = dto.title.ifBlank { existing.title },
                    note = dto.note ?: existing.note,
                    dueDate = dto.dueDate,
                    startDate = dto.startDate,
                    importance = dto.importance,
                    urgency = dto.urgency,
                    star = dto.star,
                ),
            )
            return ok(existing.id)
        }
        if (dto.title.isBlank()) return bad("empty_title")
        repo.ensureInbox()
        val id = repo.createTask(
            listId = dto.listId ?: ListEntity.INBOX_ID,
            title = dto.title.trim(),
            parentId = dto.parentId,
            importance = dto.importance,
            urgency = dto.urgency,
            dueDate = dto.dueDate,
            startDate = dto.startDate,
        )
        // Apply the fields createTask doesn't take (star / note) in one follow-up save.
        if (dto.star || !dto.note.isNullOrBlank()) {
            repo.getTask(id)?.let { repo.saveTask(it.copy(star = dto.star, note = dto.note ?: it.note)) }
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

    private inline fun <reified T> decode(json: String): T? =
        runCatching { BridgeCodec.decodeString<T>(json) }.getOrNull()

    private fun ok(id: String) = DataResult(ok = true, payloadJson = BridgeCodec.encodeString(IdParam(id)))
    private fun bad(reason: String) = DataResult(ok = false, error = reason)

    // ---- reads --------------------------------------------------------------------------------------

    private suspend fun allTasks(): List<TaskEntity> {
        val ws = repo.activeWs()
        return repo.allTasksOnce().asSequence()
            .filter { it.workspaceId == ws && !it.trashed }
            .sortedWith(compareBy({ it.completed }, { it.sortOrder }))
            .toList()
    }

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
                    category = h.category,
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
    }
}

// ---- op params (the small JSON shapes for complete/delete; upsert uses the full DTO) ------------------

@Serializable
private data class IdParam(val id: String)

@Serializable
private data class CompleteParam(val id: String, val completed: Boolean = true)

/** A bulk task op: an [action] (complete / delete / star) applied to many [ids]; [value] is the on/off flag. */
@Serializable
private data class BulkOp(val ids: List<String> = emptyList(), val action: String = "", val value: Boolean = true)

@Serializable
private data class CountResult(val count: Int)

// ---- entity → DTO projections (the only fields that leave the core) ----------------------------------

private fun TaskEntity.toDto() = TaskDto(
    id = id,
    title = title,
    listId = listId,
    parentId = parentId,
    completed = completed,
    dueDate = dueDate,
    startDate = startDate,
    importance = importance,
    urgency = urgency,
    star = star,
    sortOrder = sortOrder,
    note = note.ifBlank { null },
    updatedAt = updatedAt,
)

private fun NoteEntity.toDto() = NoteDto(
    id = id,
    title = title,
    body = body,
    notebookId = notebookId,
    pinned = pinned,
    archived = archived,
    colorArgb = colorArgb,
    coverEmoji = coverEmoji,
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
