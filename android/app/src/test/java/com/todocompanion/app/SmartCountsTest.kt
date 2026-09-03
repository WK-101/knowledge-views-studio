package com.todocompanion.app

import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.SmartCounts
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.view.SmartKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/**
 * R91 — coverage for the pure smart-list badge counts extracted from AppViewModel. The dispatch to
 * filterSmart / DoNext / computeBlocked is covered by their own tests; here we lock the bespoke Trash
 * branch (per-workspace) and the guarantee that every SmartKind gets a count.
 */
class SmartCountsTest {
    private val zone = ZoneOffset.UTC
    private val now = 1_700_000_000_000L

    private fun task(id: String, trashed: Boolean = false, ws: String = "ws1") =
        TaskEntity(id = id, listId = "l", title = id, trashed = trashed, workspaceId = ws,
            createdAt = 0L, updatedAt = 0L)

    private fun counts(tasks: List<TaskEntity>, activeWs: String) =
        SmartCounts.compute(tasks, emptyList(), emptyList(), PriorityEngine.Config(),
            emptyList(), emptyList(), activeWs, zone, 0, now)

    @Test fun trashCountsOnlyActiveWorkspace() {
        val tasks = listOf(
            task("a", trashed = true, ws = "ws1"),   // active workspace, trashed → counts
            task("b", trashed = true, ws = "ws2"),   // other workspace → excluded
            task("c", trashed = false, ws = "ws1"),  // not trashed → excluded
        )
        assertEquals(1, counts(tasks, "ws1")[SmartKind.TRASH])
        assertEquals(1, counts(tasks, "ws2")[SmartKind.TRASH])   // only b, from ws2's side
    }

    @Test fun everySmartKindGetsACount() {
        val r = counts(listOf(task("a")), "ws1")
        assertEquals(SmartKind.entries.toSet(), r.keys)
    }
}
