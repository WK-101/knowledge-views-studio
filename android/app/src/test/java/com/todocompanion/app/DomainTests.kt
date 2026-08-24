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
    id = id, listId = "l", parentId = parent, importance = importance, urgency = urgency,
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

    @Test fun parsesListToken() {
        val p = QuickAddParser.parse("email boss ~Work tomorrow", now)
        assertEquals("Work", p.list)
        assertEquals("email boss", p.title)
    }
}

class RecurrenceTest {
    private val zone = java.time.ZoneId.of("UTC")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 9) =
        java.time.LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun weeklyAdvancesSevenDays() {
        val next = com.todocompanion.app.domain.recurrence.Recurrence.next("WEEKLY:1", ms(2026, 1, 1), zone)
        assertEquals(ms(2026, 1, 8), next)
    }

    @Test fun weekdaysSkipsWeekend() {
        // 2026-01-02 is a Friday → next weekday is Monday the 5th
        val next = com.todocompanion.app.domain.recurrence.Recurrence.next("WEEKDAYS:1", ms(2026, 1, 2), zone)
        assertEquals(ms(2026, 1, 5), next)
    }

    @Test fun labels() {
        assertEquals("Weekly", com.todocompanion.app.domain.recurrence.Recurrence.label("WEEKLY:1"))
        assertEquals("Every 2 weeks", com.todocompanion.app.domain.recurrence.Recurrence.label("WEEKLY:2"))
        assertEquals(null, com.todocompanion.app.domain.recurrence.Recurrence.label(null))
    }

    @Test fun weeklyOnSpecificDays() {
        // 2026-01-01 is a Thursday. Repeat Mon/Wed/Fri → next is Fri the 2nd.
        val rule = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.WEEKLY, 1, byDays = setOf(1, 3, 5)))
        assertEquals(ms(2026, 1, 2), com.todocompanion.app.domain.recurrence.Recurrence.next(rule, ms(2026, 1, 1), zone))
    }

    @Test fun countExhaustsAndUntilStops() {
        val once = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.DAILY, 1, count = 1))
        assertEquals(null, com.todocompanion.app.domain.recurrence.Recurrence.advance(once, ms(2026, 1, 1), zone).first)

        val untilPast = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.DAILY, 1,
                untilEpochDay = java.time.LocalDate.of(2026, 1, 1).toEpochDay()))
        assertEquals(null, com.todocompanion.app.domain.recurrence.Recurrence.advance(untilPast, ms(2026, 1, 1), zone).first)
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

    @Test fun importanceInheritsMultiplicatively() {
        val now = 1_000L
        // Two equally-important leaves; one under a high-importance parent (the MLO "snowball").
        val bigParent = task("bp", importance = 5)
        val underBig = task("ub", importance = 3, parent = "bp")
        val flat = task("flat", importance = 3)
        val byId = listOf(bigParent, underBig, flat).associateBy { it.id }
        assertTrue(PriorityEngine.score(underBig, now, byId) > PriorityEngine.score(flat, now, byId))
    }

    @Test fun modeIgnoresUrgencyWhenImportanceOnly() {
        val now = 1_000L
        val impCfg = PriorityEngine.Config(mode = PriorityEngine.Mode.IMPORTANCE)
        val lowUrg = task("a", importance = 4, urgency = 1)
        val highUrg = task("b", importance = 4, urgency = 5)
        // Importance-only mode: identical importance ⇒ equal base score (no due term here).
        assertEquals(PriorityEngine.score(lowUrg, now, mapOf(), impCfg), PriorityEngine.score(highUrg, now, mapOf(), impCfg), 1e-9)
    }
}

class HabitStatsTest {
    @Test fun streakCountsBackFromTodayOrYesterday() {
        val today = 100L
        // Done 98, 99, 100 → streak 3.
        assertEquals(3, com.todocompanion.app.domain.habit.HabitStats.streak(setOf(98L, 99L, 100L), today))
        // Today not done but 97,98,99 done → streak counts from yesterday = 3.
        assertEquals(3, com.todocompanion.app.domain.habit.HabitStats.streak(setOf(97L, 98L, 99L), today))
        // Gap breaks it: 100 done, 98 done, 99 missing → streak 1.
        assertEquals(1, com.todocompanion.app.domain.habit.HabitStats.streak(setOf(98L, 100L), today))
        assertEquals(0, com.todocompanion.app.domain.habit.HabitStats.streak(emptySet(), today))
    }
}

class FilterQueryTest {
    private val zone = java.time.ZoneId.of("UTC")
    private fun t(id: String, listId: String = "l", imp: Int = 3, urg: Int = 3, star: Boolean = false) =
        TaskEntity(id = id, listId = listId, title = id, importance = imp, urgency = urg, star = star, createdAt = 0, updatedAt = 0)

    @Test fun matchAllRequiresEveryGroup() {
        val q = com.todocompanion.app.domain.view.FilterQuery(matchAll = true, listIds = setOf("work"), flaggedOnly = true)
        assertTrue(com.todocompanion.app.domain.view.Filters.matches(q, t("a", listId = "work", star = true), emptySet(), emptySet(), 0, zone))
        assertFalse(com.todocompanion.app.domain.view.Filters.matches(q, t("b", listId = "work", star = false), emptySet(), emptySet(), 0, zone))
    }

    @Test fun matchAnyNeedsOneGroup() {
        val q = com.todocompanion.app.domain.view.FilterQuery(matchAll = false, tagIds = setOf("home"), flaggedOnly = true)
        assertTrue(com.todocompanion.app.domain.view.Filters.matches(q, t("a", star = true), emptySet(), emptySet(), 0, zone))       // flagged
        assertTrue(com.todocompanion.app.domain.view.Filters.matches(q, t("b"), setOf("home"), emptySet(), 0, zone))                 // tag
        assertFalse(com.todocompanion.app.domain.view.Filters.matches(q, t("c"), setOf("other"), emptySet(), 0, zone))               // neither
    }
}

class ContextAvailabilityTest {
    private val CA = com.todocompanion.app.domain.context.ContextAvailability
    private fun oh(vararg d: Int) = com.todocompanion.app.domain.context.OpenHours(days = d.toSet(), startMin = 540, endMin = 1020)

    @Test fun openOnlyWithinWindowAndDays() {
        val h = oh(1, 2, 3, 4, 5)
        assertTrue(CA.isOpen(h, 1, 600))    // Mon 10:00
        assertFalse(CA.isOpen(h, 1, 1100))  // Mon 18:20 — after close
        assertFalse(CA.isOpen(h, 6, 600))   // Sat — wrong day
    }

    @Test fun inactiveContextIsNeverAvailable() {
        val c = com.todocompanion.app.data.entity.ContextEntity(id = "x", name = "Office", active = false)
        assertFalse(CA.isAvailable(c, 1, 600))
    }

    @Test fun contextWithNoScheduleIsAlwaysAvailable() {
        val c = com.todocompanion.app.data.entity.ContextEntity(id = "x", name = "Home", active = true)
        assertTrue(CA.isAvailable(c, 6, 1300))
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
