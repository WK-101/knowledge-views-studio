package com.wkhan.hexis.webbridge

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataPage
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.data.DataResult
import com.wkhan.hexis.bridge.data.EventDto
import com.wkhan.hexis.bridge.data.HabitDto
import com.wkhan.hexis.bridge.data.NoteDto
import com.wkhan.hexis.bridge.data.TaskDto
import com.wkhan.hexis.bridge.data.TimeEntryDto
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.ListEntity
import com.wkhan.hexis.data.entity.NoteEntity
import com.wkhan.hexis.data.entity.TaskEntity

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
class RepositoryDataSource(private val repo: AppRepository) : DataSource {

    override suspend fun query(query: DataQuery): DataPage = when (query.domain) {
        DataApi.DOMAIN_TASKS -> queryTasks(query)
        DataApi.DOMAIN_NOTES -> queryNotes(query)
        DataApi.DOMAIN_CALENDAR -> queryListOnly(query) { allEventDtos() }
        DataApi.DOMAIN_TIME -> queryListOnly(query) { allTimeEntryDtos() }
        DataApi.DOMAIN_HABITS -> queryListOnly(query) { allHabitDtos() }
        else -> throw UnsupportedDomainException(query.domain)
    }

    private suspend fun queryTasks(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allTasks(), query) { it.toDto() }
        DataApi.OP_GET -> onePage(idParam(query)?.let { repo.getTask(it)?.toDto() })
        else -> throw UnsupportedDomainException("tasks.${query.op}")
    }

    private suspend fun queryNotes(query: DataQuery): DataPage = when (query.op) {
        DataApi.OP_LIST -> listPage(allNotes(), query) { it.toDto() }
        DataApi.OP_GET -> onePage(idParam(query)?.let { repo.getNote(it)?.toDto() })
        else -> throw UnsupportedDomainException("notes.${query.op}")
    }

    /** The breadth domains (calendar/time/habits) are list-only; the DTOs are already projected. */
    private suspend inline fun <reified D> queryListOnly(query: DataQuery, load: () -> List<D>): DataPage {
        if (query.op != DataApi.OP_LIST) throw UnsupportedDomainException("${query.domain}.${query.op}")
        return listPage(load(), query) { it }
    }

    override suspend fun mutate(mutation: DataMutation): DataResult = when (mutation.domain) {
        DataApi.DOMAIN_TASKS -> mutateTask(mutation)
        DataApi.DOMAIN_NOTES -> mutateNote(mutation)
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
            else -> throw UnsupportedDomainException("tasks.${mutation.op}")
        }
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
        ) ?: NoteEntity(
            id = "",
            title = dto.title,
            body = dto.body,
            notebookId = dto.notebookId,
            pinned = dto.pinned,
            archived = dto.archived,
            workspaceId = repo.activeWs(),
        )
        return ok(repo.upsertNote(entity))
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
            .map { e ->
                EventDto(
                    id = e.id,
                    title = e.title,
                    calendarId = e.calendarId,
                    calendarName = calById[e.calendarId]?.name.orEmpty(),
                    location = e.location,
                    notes = e.notes,
                    startMillis = e.startMillis,
                    endMillis = e.endMillis,
                    allDay = e.allDay,
                    recurring = e.rrule.isNotBlank(),
                    colorArgb = e.colorArgb,
                    linkedTaskId = e.linkedTaskId,
                )
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
    }
}

// ---- op params (the small JSON shapes for complete/delete; upsert uses the full DTO) ------------------

@Serializable
private data class IdParam(val id: String)

@Serializable
private data class CompleteParam(val id: String, val completed: Boolean = true)

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
    updatedAt = updatedAt,
)
