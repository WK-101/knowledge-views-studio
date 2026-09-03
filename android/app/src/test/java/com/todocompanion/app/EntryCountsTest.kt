package com.todocompanion.app

import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TagEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TaskTagCrossRef
import com.todocompanion.app.domain.EntryCounts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/** R89 — coverage for the pure drawer entry-count math extracted from AppViewModel. */
class EntryCountsTest {
    private val zone = ZoneOffset.UTC
    private val now = 1_700_000_000_000L

    private fun task(id: String, listId: String, completed: Boolean = false) =
        TaskEntity(id = id, listId = listId, title = id, completed = completed,
            completedAt = if (completed) now else null, createdAt = 0L, updatedAt = 0L)

    @Test fun listAndFolderCountsRollUpNestedFoldersAndExcludeInactive() {
        // f2 nested under f1; l1 in f1, l2 in f2.
        val folders = listOf(FolderEntity(id = "f1", name = "F1"), FolderEntity(id = "f2", name = "F2", parentId = "f1"))
        val lists = listOf(ListEntity(id = "l1", name = "L1", folderId = "f1"), ListEntity(id = "l2", name = "L2", folderId = "f2"))
        val tasks = listOf(task("t1", "l1"), task("t2", "l2"), task("t3", "l1", completed = true))
        val r = EntryCounts.compute(tasks, emptyList(), emptyList(), lists, folders, emptyList(), emptyList(), zone, now)
        assertEquals(1, r.lists["l1"])          // t1 active; t3 completed excluded
        assertEquals(1, r.lists["l2"])          // t2
        assertEquals(2, r.folders["f1"])        // rolls up l1 + nested f2's l2
        assertEquals(1, r.folders["f2"])        // just l2
    }

    @Test fun tagCountsRollUpDescendantTagsDistinctly() {
        val lists = listOf(ListEntity(id = "l1", name = "L1"))
        val tasks = listOf(task("t1", "l1"))
        val tags = listOf(TagEntity(id = "gp", name = "Parent"), TagEntity(id = "gc", name = "Child", parentId = "gp"))
        val refs = listOf(TaskTagCrossRef("t1", "gc"))   // t1 tagged with the child only
        val r = EntryCounts.compute(tasks, refs, emptyList(), lists, emptyList(), tags, emptyList(), zone, now)
        assertEquals(1, r.tags["gc"])           // direct
        assertEquals(1, r.tags["gp"])           // parent rolls up the child's task
    }

    @Test fun emptyWhenNoData() {
        val r = EntryCounts.compute(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), zone, now)
        assertEquals(EntryCounts.Result(), r)
    }
}
