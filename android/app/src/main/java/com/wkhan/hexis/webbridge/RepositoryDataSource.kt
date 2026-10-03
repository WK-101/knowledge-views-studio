package com.wkhan.hexis.webbridge

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataPage
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.data.DataResult
import com.wkhan.hexis.bridge.data.NoteDto
import com.wkhan.hexis.bridge.data.TaskDto
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.NoteEntity
import com.wkhan.hexis.data.entity.TaskEntity

/**
 * The read/write facade over [AppRepository] for the `data` capability. W0 is **read-only** (tasks + notes,
 * active workspace, non-trashed, offset-paginated). Writes return a failed result until W2 wires them in.
 *
 * Only the fields in [TaskDto] / [NoteDto] ever leave the core — a deliberate projection, not the entity.
 */
class RepositoryDataSource(private val repo: AppRepository) : DataSource {

    override suspend fun query(query: DataQuery): DataPage = when (query.domain) {
        DataApi.DOMAIN_TASKS -> when (query.op) {
            DataApi.OP_LIST -> listPage(allTasks(), query) { it.toDto() }
            DataApi.OP_GET -> onePage(idParam(query)?.let { repo.getTask(it)?.toDto() })
            else -> throw UnsupportedDomainException("tasks.${query.op}")
        }
        DataApi.DOMAIN_NOTES -> when (query.op) {
            DataApi.OP_LIST -> listPage(allNotes(), query) { it.toDto() }
            DataApi.OP_GET -> onePage(idParam(query)?.let { repo.getNote(it)?.toDto() })
            else -> throw UnsupportedDomainException("notes.${query.op}")
        }
        else -> throw UnsupportedDomainException(query.domain)
    }

    override suspend fun mutate(mutation: DataMutation): DataResult =
        DataResult(ok = false, error = "writes_not_enabled") // W2 implements create/edit/complete/delete

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
