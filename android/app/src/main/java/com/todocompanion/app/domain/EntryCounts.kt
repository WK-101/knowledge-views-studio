package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.FilterEntity
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskContextCrossRef
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.domain.view.Filters
import java.time.ZoneId

/**
 * R89 — the pure drawer entry-count math, lifted out of AppViewModel. A deterministic function of
 * plain snapshots plus a zone and `now`, so it is independently unit-testable and holds no Android or
 * coroutine dependency. The ViewModel keeps the reactive `combine { … }` (and the "show counts" gate)
 * and calls [compute]. Counts mirror each drawer view's own filter: active = not trashed / completed /
 * abandoned / someday; a folder rolls up its sub-folders' lists; a parent tag rolls up its descendant
 * tags (distinct tasks); a saved filter honours its own query. Behaviour is identical to the previous
 * in-ViewModel lambda — the pure folder-subtree resolver it needs moved here too.
 */
object EntryCounts {
    data class Result(
        val lists: Map<String, Int> = emptyMap(),
        val folders: Map<String, Int> = emptyMap(),
        val tags: Map<String, Int> = emptyMap(),
        val contexts: Map<String, Int> = emptyMap(),
        val filters: Map<String, Int> = emptyMap(),
    )

    /** All list-ids in the subtree rooted at [folderId] — the folder plus every nested sub-folder. Pure. */
    fun folderListIds(folderId: String, lists: List<ListEntity>, folders: List<FolderEntity>): Set<String> {
        val folderIds = mutableSetOf(folderId)
        var changed = true
        while (changed) {
            changed = false
            folders.forEach { if (it.parentId in folderIds && it.id !in folderIds) { folderIds.add(it.id); changed = true } }
        }
        return lists.filter { it.folderId in folderIds }.map { it.id }.toSet()
    }

    fun compute(
        all: List<TaskEntity>,
        tagRefs: List<TaskTagCrossRef>,
        ctxRefs: List<TaskContextCrossRef>,
        lists: List<ListEntity>,
        folders: List<FolderEntity>,
        tags: List<TagEntity>,
        filters: List<FilterEntity>,
        zone: ZoneId,
        now: Long,
    ): Result {
        val active = all.filter { !it.trashed && !it.completed && !it.abandoned && !it.someday }
        val activeIds = active.mapTo(HashSet()) { it.id }
        val listCounts = active.groupingBy { it.listId }.eachCount()
        val folderCounts = folders.associate { fo ->
            val ids = folderListIds(fo.id, lists, folders)
            fo.id to active.count { it.listId in ids || it.folderId == fo.id }
        }
        // Tag counts roll up the subtree, like folders: a parent tag's count includes every task
        // tagged with it OR any descendant tag (distinct tasks, so a task tagged with both parent
        // and child isn't double-counted).
        val tasksByTag = tagRefs.filter { it.taskId in activeIds }.groupBy { it.tagId }.mapValues { e -> e.value.mapTo(HashSet()) { it.taskId } }
        val tagChildren = tags.groupBy { it.parentId }
        val tagCounts = tags.associate { tg ->
            val seen = HashSet<String>(); val stack = ArrayDeque<String>().apply { add(tg.id) }
            val taskIds = HashSet<String>()
            while (stack.isNotEmpty()) { val id = stack.removeLast(); if (seen.add(id)) { tasksByTag[id]?.let { taskIds.addAll(it) }; tagChildren[id]?.forEach { stack.add(it.id) } } }
            tg.id to taskIds.size
        }.filterValues { it > 0 }
        val ctxCounts = ctxRefs.filter { it.taskId in activeIds }.groupingBy { it.contextId }.eachCount()
        val tagsByTask = tagRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.tagId }.toSet() }
        val ctxByTask = ctxRefs.groupBy { it.taskId }.mapValues { e -> e.value.map { it.contextId }.toSet() }
        val listFolderById = lists.associate { it.id to it.folderId }
        fun folderOf(t: TaskEntity) = t.folderId ?: listFolderById[t.listId]
        val filterCounts = filters.associate { fl ->
            val q = Filters.parse(fl.queryJson)
            fl.id to all.count { !it.trashed && Filters.matches(q, it, tagsByTask[it.id].orEmpty(), ctxByTask[it.id].orEmpty(), now, zone, folderOf(it)) }
        }
        return Result(listCounts, folderCounts, tagCounts, ctxCounts, filterCounts)
    }
}
