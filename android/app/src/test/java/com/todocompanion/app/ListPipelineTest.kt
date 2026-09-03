package com.todocompanion.app

import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.view.GroupMode
import com.todocompanion.app.domain.view.ListPipeline
import com.todocompanion.app.domain.view.SmartKind
import com.todocompanion.app.domain.view.SortMode
import com.todocompanion.app.domain.view.ViewRef
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/**
 * R93 — coverage for the pure task-list rendering pipeline extracted from AppViewModel. Exercises the
 * view → filter → group path (grouping/sorting themselves are covered by the TaskViews tests). Sort is
 * MANUAL and group NONE, so the pipeline returns one "all" group and the assertions are on membership.
 */
class ListPipelineTest {
    private val zone = ZoneOffset.UTC
    private val now = 1_700_000_000_000L

    private fun task(id: String, listId: String = "l1", folderId: String? = null, trashed: Boolean = false, completed: Boolean = false, ws: String = "default") =
        TaskEntity(id = id, listId = listId, title = id, folderId = folderId, trashed = trashed, completed = completed,
            completedAt = if (completed) now else null, workspaceId = ws, createdAt = 0L, updatedAt = 0L)

    private fun ids(view: ViewRef, tasks: List<TaskEntity>, vc: ListPipeline.ViewCtx, ws: String = "default"): Set<String> {
        val cfg = ListPipeline.Cfg(view, GroupMode.NONE, SortMode.MANUAL, PriorityEngine.Config(), emptyList(), ws = ws)
        return ListPipeline.compute(tasks, emptyList(), cfg, emptyList(), vc, emptyList(), zone, 0, now)
            .flatMap { it.tasks }.map { it.id }.toSet()
    }

    private fun ctx(lists: List<ListEntity> = emptyList(), folders: List<FolderEntity> = emptyList()) =
        ListPipeline.ViewCtx(emptyList(), emptyList(), emptyList(), lists, folders, emptyList())

    @Test fun listViewShowsOnlyActiveTasksOfThatList() {
        val tasks = listOf(task("a", "l1"), task("b", "l1", trashed = true), task("c", "l1", completed = true), task("d", "l2"))
        assertEquals(setOf("a"), ids(ViewRef.ListView("l1"), tasks, ctx(lists = listOf(ListEntity(id = "l1", name = "L1")))))
    }

    @Test fun trashSmartListShowsCurrentWorkspaceTrashOnly() {
        val tasks = listOf(task("a", trashed = true, ws = "w1"), task("b", trashed = true, ws = "w2"), task("c"))
        assertEquals(setOf("a"), ids(ViewRef.Smart(SmartKind.TRASH), tasks, ctx(), ws = "w1"))
    }

    @Test fun folderViewRollsUpListTasksAndDirectlyCapturedTasks() {
        val lists = listOf(ListEntity(id = "l1", name = "L1", folderId = "f1"))
        val folders = listOf(FolderEntity(id = "f1", name = "F1"))
        val tasks = listOf(
            task("a", "l1"),                       // in the folder's list
            task("direct", listId = "", folderId = "f1"),  // captured straight into the folder
            task("other", "l2"),                   // unrelated
        )
        assertEquals(setOf("a", "direct"), ids(ViewRef.FolderView("f1"), tasks, ctx(lists = lists, folders = folders)))
    }
}
