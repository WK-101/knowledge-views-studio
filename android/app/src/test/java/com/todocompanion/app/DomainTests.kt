package com.todocompanion.app

import com.todocompanion.app.data.entity.DependencyEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.nlp.QuickAddParser
import com.todocompanion.app.domain.port.Backup
import com.todocompanion.app.domain.port.BackupFile
import com.todocompanion.app.domain.priority.PriorityEngine
import com.todocompanion.app.domain.priority.PriorityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private fun task(
    id: String,
    parent: String? = null,
    importance: Int = 3,
    urgency: Int = 3,
    completed: Boolean = false,
    due: Long? = null,
    start: Long? = null,
) = TaskEntity(
    id = id, parentId = parent, importance = importance, urgency = urgency,
    completed = completed, dueDate = due, startDate = start, title = id,
    createdAt = 0, updatedAt = 0,
)

class QuickAddParserTest {
    private val now = LocalDateTime.of(2026, 8, 24, 10, 0)

    @Test fun parsesTitleDatePriorityTagContext() {
        val p = QuickAddParser.parse("Pay rent tomorrow 5pm !! #home @errands", now)
        assertEquals("Pay rent", p.title)
        assertEquals(PriorityLevel.MEDIUM, p.priority)
        assertEquals(listOf("home"), p.tags)
        assertEquals(listOf("errands"), p.contexts)
        assertTrue(p.hasTime)
        assertEquals(now.toLocalDate().plusDays(1), p.dateTime!!.toLocalDate())
        assertEquals(17, p.dateTime!!.hour)
    }

    @Test fun parsesPlainTitle() {
        val p = QuickAddParser.parse("Buy milk", now)
        assertEquals("Buy milk", p.title)
        assertEquals(null, p.dateTime)
        assertEquals(null, p.priority)
    }

    @Test fun parsesPriorityBangs() {
        assertEquals(PriorityLevel.HIGH, QuickAddParser.parse("do it !!!", now).priority)
        assertEquals(PriorityLevel.LOW, QuickAddParser.parse("meh ! today", now).priority)
    }
}

class PriorityEngineTest {
    @Test fun quadrantMapping() {
        assertEquals(0, PriorityEngine.quadrant(task("a", importance = 5, urgency = 5)))
        assertEquals(1, PriorityEngine.quadrant(task("b", importance = 5, urgency = 2)))
        assertEquals(2, PriorityEngine.quadrant(task("c", importance = 2, urgency = 5)))
        assertEquals(3, PriorityEngine.quadrant(task("d", importance = 2, urgency = 2)))
    }

    @Test fun doNextExcludesCompletedParentsAndBlocked() {
        val now = 1_000_000L
        val parent = task("p")
        val child = task("c", parent = "p") // incomplete child -> parent not actionable
        val done = task("done", completed = true)
        val blocker = task("blk")
        val blocked = task("blocked")
        val all = listOf(parent, child, done, blocker, blocked)
        val deps = listOf(DependencyEntity("blocked", "blk", "AND"))
        val byId = all.associateBy { it.id }
        val blockedSet = PriorityEngine.computeBlocked(deps, byId)
        val byParent = all.groupBy { it.parentId }
        val result = PriorityEngine.doNext(
            all, now, blockedSet,
            hasIncompleteChild = { id -> byParent[id].orEmpty().any { !it.completed } },
            contextAvailable = { true },
        ).map { it.task.id }
        assertTrue(result.contains("c"))
        assertTrue(result.contains("blk"))
        assertFalse(result.contains("p"))       // has incomplete child
        assertFalse(result.contains("done"))    // completed
        assertFalse(result.contains("blocked")) // blocked by incomplete predecessor
    }

    @Test fun overdueRanksHigher() {
        val now = 10_000_000_000L
        val soon = task("soon", importance = 3, urgency = 3, due = now + 30L * 86_400_000)
        val overdue = task("overdue", importance = 3, urgency = 3, due = now - 86_400_000)
        assertTrue(PriorityEngine.score(overdue, now, mapOf()) > PriorityEngine.score(soon, now, mapOf()))
    }
}

class BackupTest {
    @Test fun roundTrip() {
        val original = BackupFile(
            exportedAt = 123,
            tasks = listOf(task("a", importance = 5), task("b", parent = "a")),
        )
        val decoded = Backup.decode(Backup.encode(original))
        assertEquals(original, decoded)
    }
}
