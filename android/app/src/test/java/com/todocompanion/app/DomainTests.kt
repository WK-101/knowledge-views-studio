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
    completedAt: Long? = null,
) = TaskEntity(
    id = id, listId = "l", parentId = parent, importance = importance, urgency = urgency,
    completed = completed, completedAt = completedAt, dueDate = due, startDate = start, title = id,
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

    @Test fun monthlyNthWeekday() {
        // "3rd Tuesday" — from 2026-01-20 (3rd Tue of Jan) next is 2026-02-17 (3rd Tue of Feb).
        val rule = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.MONTHLY, 1, bySetPos = 3, byWeekday = 2))
        assertEquals(ms(2026, 2, 17), com.todocompanion.app.domain.recurrence.Recurrence.next(rule, ms(2026, 1, 20), zone))
    }

    @Test fun lastWeekdayOfMonth() {
        // "last Friday" — from 2026-01-30 (last Fri of Jan) next is 2026-02-27 (last Fri of Feb).
        val rule = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.MONTHLY, 1, bySetPos = -1, byWeekday = 5))
        assertEquals(ms(2026, 2, 27), com.todocompanion.app.domain.recurrence.Recurrence.next(rule, ms(2026, 1, 30), zone))
    }

    @Test fun firstWorkingDayOfMonth() {
        // From 2026-01-01 (a Thursday, itself the 1st working day) next is 2026-02-02 (Feb 1 is Sunday → Mon 2nd).
        val rule = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.MONTHLY, 1, firstWorkday = true))
        assertEquals(ms(2026, 2, 2), com.todocompanion.app.domain.recurrence.Recurrence.next(rule, ms(2026, 1, 1), zone))
    }

    @Test fun subtaskResetRoundTrips() {
        val rule = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.DAILY, 1, subtaskReset = "allDone"))
        assertEquals("allDone", com.todocompanion.app.domain.recurrence.Recurrence.parse(rule)?.subtaskReset)
    }

    @Test fun regenerateFromCompletion() {
        // "every 3 days after completion": due Jan 1, completed late on Jan 5 → next due Jan 8.
        val rule = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.DAILY, 3, fromCompletion = true))
        val next = com.todocompanion.app.domain.recurrence.Recurrence.advance(rule, ms(2026, 1, 1), zone, ms(2026, 1, 5)).first
        assertEquals(ms(2026, 1, 8), next)
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

    @Test fun manualModeOrdersByStarThenImportanceNotScore() {
        val now = 1_000_000L
        // Low-importance but very-soon due (would win on computed score); high-importance starred; plain high.
        val soon = task("soon", importance = 1, urgency = 1).copy(dueDate = now + 3_600_000L, sortOrder = 1.0)
        val starred = task("starred", importance = 4).copy(star = true, sortOrder = 2.0)
        val high = task("high", importance = 5, urgency = 5).copy(sortOrder = 3.0)
        val all = listOf(soon, starred, high)
        val cfg = PriorityEngine.Config(computed = false)
        val out = PriorityEngine.doNext(all, now, emptySet(), { false }, { true }, cfg = cfg).map { it.task.id }
        // Star wins first; then importance/urgency (high 5>4); the imminent due date is ignored.
        assertEquals(listOf("starred", "high", "soon"), out)
    }

    @Test fun delayedDependencyHoldsThenReleases() {
        val doneAt = 1_000_000L
        val pred = task("p", completed = true, completedAt = doneAt)
        val t = task("t")
        val byId = mapOf("p" to pred, "t" to t)
        val deps = listOf(DependencyEntity("t", "p", "AND", delayDays = 2))
        // Half a day after completion → still held by the 2-day delay.
        assertTrue("t" in PriorityEngine.computeBlocked(deps, byId, doneAt + 43_200_000L))
        // Three days after → released.
        assertFalse("t" in PriorityEngine.computeBlocked(deps, byId, doneAt + 3L * 86_400_000L))
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

    // ---- Tier 9B: bounded egg-timer / spike-then-decay / dependency propagation ----

    @Test fun scheduledBeatsUnscheduled() {
        // MLO quirk fixed: even a far-future due date must edge out a task with no date at all.
        val now = 10_000_000_000L
        val dated = task("dated", importance = 3, urgency = 3, due = now + 60L * 86_400_000)
        val undated = task("undated", importance = 3, urgency = 3)
        assertTrue(PriorityEngine.score(dated, now, mapOf()) > PriorityEngine.score(undated, now, mapOf()))
    }

    @Test fun overdueSpikesThenDecays() {
        // A task that just slipped its deadline is more urgent than one overdue for weeks.
        val now = 10_000_000_000L
        val justOverdue = task("just", importance = 3, urgency = 3, due = now - 86_400_000)
        val longOverdue = task("long", importance = 3, urgency = 3, due = now - 40L * 86_400_000)
        assertTrue(PriorityEngine.score(justOverdue, now, mapOf()) > PriorityEngine.score(longOverdue, now, mapOf()))
    }

    @Test fun dependencyBoostRaisesBlocker() {
        // A low-priority task that blocks a high-priority successor should inherit a boost.
        val blocker = task("blk", importance = 1, urgency = 1)
        val important = task("imp", importance = 5, urgency = 5)
        val byId = mapOf("blk" to blocker, "imp" to important)
        val deps = listOf(DependencyEntity("imp", "blk", "AND"))
        val boosts = PriorityEngine.dependencyBoosts(deps, byId)
        assertTrue((boosts["blk"] ?: 0.0) > 0.0)
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

    @Test fun scheduleAwareStreakSkipsOffDays() {
        val S = com.todocompanion.app.domain.habit.HabitStats
        // Mon/Wed/Fri schedule. Pick a known Friday: 2026-01-02 is a Friday.
        val fri = java.time.LocalDate.of(2026, 1, 2).toEpochDay()
        val wed = java.time.LocalDate.of(2025, 12, 31).toEpochDay()
        val mon = java.time.LocalDate.of(2025, 12, 29).toEpochDay()
        val sched = S.parseSchedule("1,3,5")
        // Done on all three scheduled days; the weekend gap between must NOT break the streak.
        assertEquals(3, S.streak(setOf(mon, wed, fri), fri, sched))
        // Off days aren't scheduled → isScheduled false.
        val sat = java.time.LocalDate.of(2026, 1, 3).toEpochDay()
        assertEquals(false, S.isScheduled(sat, sched))
        // Rate counts only scheduled days.
        assertEquals(1f, S.rate(setOf(mon, wed, fri), fri, sched, window = 5), 1e-6f)
    }
}

class QuickAddRecurrenceTest {
    private val P = com.todocompanion.app.domain.nlp.QuickAddParser
    @Test fun parsesEveryWeekdayList() {
        val r = P.parse("gym every tuesday and thursday")
        assertTrue(r.rrule != null && r.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(r.rrule!!.contains("DAYS="))
        assertTrue(r.title.contains("gym"))
    }
    @Test fun parsesEveryNWeeks() {
        val r = P.parse("water plants every 2 weeks")
        assertTrue(r.rrule != null && r.rrule!!.contains("FREQ=WEEKLY"))
        assertTrue(r.rrule!!.contains("INT=2"))
    }
    @Test fun parsesMonthlyWord() {
        val r = P.parse("pay rent monthly")
        assertTrue(r.rrule != null && r.rrule!!.contains("FREQ=MONTHLY"))
    }
}

class FilterQueryTest {
    private val zone = java.time.ZoneId.of("UTC")
    private fun t(id: String, listId: String = "l", imp: Int = 3, urg: Int = 3, flag: String? = null) =
        TaskEntity(id = id, listId = listId, title = id, importance = imp, urgency = urg, flagId = flag, createdAt = 0, updatedAt = 0)

    @Test fun matchAllRequiresEveryGroup() {
        val q = com.todocompanion.app.domain.view.FilterQuery(matchAll = true, listIds = setOf("work"), flaggedOnly = true)
        assertTrue(com.todocompanion.app.domain.view.Filters.matches(q, t("a", listId = "work", flag = "f1"), emptySet(), emptySet(), 0, zone))
        assertFalse(com.todocompanion.app.domain.view.Filters.matches(q, t("b", listId = "work", flag = null), emptySet(), emptySet(), 0, zone))
    }

    @Test fun matchAnyNeedsOneGroup() {
        val q = com.todocompanion.app.domain.view.FilterQuery(matchAll = false, tagIds = setOf("home"), flaggedOnly = true)
        assertTrue(com.todocompanion.app.domain.view.Filters.matches(q, t("a", flag = "f1"), emptySet(), emptySet(), 0, zone))       // flagged
        assertTrue(com.todocompanion.app.domain.view.Filters.matches(q, t("b"), setOf("home"), emptySet(), 0, zone))                 // tag
        assertFalse(com.todocompanion.app.domain.view.Filters.matches(q, t("c"), setOf("other"), emptySet(), 0, zone))               // neither
    }

    @Test fun specificFlagFilter() {
        val q = com.todocompanion.app.domain.view.FilterQuery(matchAll = true, flagIds = setOf("red"))
        assertTrue(com.todocompanion.app.domain.view.Filters.matches(q, t("a", flag = "red"), emptySet(), emptySet(), 0, zone))
        assertFalse(com.todocompanion.app.domain.view.Filters.matches(q, t("b", flag = "blue"), emptySet(), emptySet(), 0, zone))
        assertFalse(com.todocompanion.app.domain.view.Filters.matches(q, t("c", flag = null), emptySet(), emptySet(), 0, zone))
    }

    @Test fun sortByFlagRanksThenUnflaggedLast() {
        val rank = mapOf("red" to 0, "blue" to 1)
        val tasks = listOf(t("u", flag = null), t("b", flag = "blue"), t("r", flag = "red"))
        val out = com.todocompanion.app.domain.view.TaskViews.sort(tasks, com.todocompanion.app.domain.view.SortMode.FLAG, rank).map { it.id }
        assertEquals(listOf("r", "b", "u"), out)
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

class NeedsAttentionTest {
    private val now = 1_000L * 86_400_000L   // day 1000, arbitrary "now"
    private val old = now - 30L * 86_400_000L // 30 days ago
    private val recent = now - 3L * 86_400_000L

    @Test fun surfacesOnlyStaleUndatedLeaves() {
        val stale = task("stale").copy(dueDate = null, updatedAt = old)
        val dated = task("dated").copy(dueDate = now, updatedAt = old)
        val fresh = task("fresh").copy(dueDate = null, updatedAt = recent)
        val parent = task("parent").copy(dueDate = null, updatedAt = old)
        val child = task("child", parent = "parent").copy(dueDate = null, updatedAt = old)
        val out = com.todocompanion.app.domain.view.TaskViews
            .filterSmart(listOf(stale, dated, fresh, parent, child),
                com.todocompanion.app.domain.view.SmartKind.NEEDS_ATTENTION, now)
            .map { it.id }.toSet()
        // stale + the stale child qualify; dated (has a date), fresh (recent), and the container parent do not.
        assertTrue("stale" in out)
        assertTrue("child" in out)
        assertFalse("dated" in out)
        assertFalse("fresh" in out)
        assertFalse("parent" in out)
    }
}

class HabitStatsTierITest {
    private fun habit(
        freq: String = "weekly", param: Int = 0, schedule: String = "",
        type: String = "build", target: Int = 1,
    ) = com.todocompanion.app.data.entity.HabitEntity(
        id = "h", name = "H", createdAt = 0L, freqType = freq, freqParam = param,
        scheduleDays = schedule, habitType = type, targetPerDay = target,
    )

    private val today = 20000L
    private val empty = emptySet<Long>()

    @Test fun strengthHighWhenAllDoneLowWhenMissed() {
        val h = habit()
        val allDone = (0..90).map { today - it }.toSet()
        assertTrue(com.todocompanion.app.domain.habit.HabitStats.strength(h, allDone, empty, empty, today) > 80)
        assertTrue(com.todocompanion.app.domain.habit.HabitStats.strength(h, empty, empty, empty, today) < 10)
    }

    @Test fun skipsAreNeutralForStrength() {
        val h = habit()
        val done = (10..90).map { today - it }.toSet()          // done except the last 10 days
        val skipped = (0..9).map { today - it }.toSet()          // last 10 days skipped, not missed
        val withSkip = com.todocompanion.app.domain.habit.HabitStats.strength(h, done, skipped, empty, today)
        val withMiss = com.todocompanion.app.domain.habit.HabitStats.strength(h, done, empty, empty, today)
        assertTrue("skips should preserve score vs misses", withSkip > withMiss)
    }

    @Test fun dailyStreakCountsConsecutive() {
        val h = habit()
        val done = setOf(today, today - 1, today - 2)            // today-3 missing
        assertEquals(3, com.todocompanion.app.domain.habit.HabitStats.currentStreak(h, done, empty, empty, today))
    }

    @Test fun timesPerWeekDueAndStreak() {
        val h = habit(freq = "times_week", param = 3)
        val twoDone = setOf(today - 1, today - 2)                 // only 2 of 3 this week
        assertTrue(com.todocompanion.app.domain.habit.HabitStats.dueToday(h, today, twoDone, 0))
        val threeDone = setOf(today, today - 1, today - 2)        // quota met
        assertFalse(com.todocompanion.app.domain.habit.HabitStats.dueToday(h, today, threeDone, 1))
    }

    @Test fun breakHabitStreakIsDaysSinceRelapse() {
        val h = habit(type = "break", target = 0)
        val relapse = setOf(today - 5)
        assertEquals(5, com.todocompanion.app.domain.habit.HabitStats.currentStreak(h, empty, empty, relapse, today))
    }

    @Test fun intervalEveryThreeDaysScheduling() {
        val h = habit(freq = "interval", param = 3)              // start epochDay 0; multiples of 3 expected
        assertTrue(com.todocompanion.app.domain.habit.HabitStats.isExpectedDay(h, 30003L))   // 30003 % 3 == 0
        assertFalse(com.todocompanion.app.domain.habit.HabitStats.isExpectedDay(h, 30004L))
    }

    @Test fun breakHabitIsNeverActivelyDue() {
        // A clean-kept break habit has no positive daily action, so it must not read as "due" —
        // otherwise it blocks the perfect-day banner and pollutes batch check-in.
        val h = habit(type = "break", target = 0)
        assertFalse(com.todocompanion.app.domain.habit.HabitStats.dueToday(h, today, empty, 0))
    }

    @Test fun pausedHabitIsNotDue() {
        val h = habit().copy(paused = true)
        assertFalse(com.todocompanion.app.domain.habit.HabitStats.dueToday(h, today, empty, 0))
    }
}

class HabitInsightsTest {
    private val today = 20000L
    private fun habit(id: String, name: String = id, type: String = "build") =
        com.todocompanion.app.data.entity.HabitEntity(id = id, name = name, createdAt = 0L, habitType = type, freqType = "weekly")
    private fun checkin(hid: String, day: Long, count: Int = 1) =
        com.todocompanion.app.data.entity.HabitCheckinEntity(habitId = hid, epochDay = day, count = count, status = "done")

    @Test fun nearBestStreakSurfaces() {
        val h = habit("h", "Meditate")
        // Best streak 10 (days 100..91 ago), current streak 8 ending today.
        val done = ((today - 9)..today).toList() + ((today - 110)..(today - 101)).toList()
        val insights = com.todocompanion.app.domain.habit.HabitInsights.compute(listOf(h), done.map { checkin("h", it) }, emptyList(), today)
        assertTrue(insights.any { it.text.contains("Meditate") && it.text.contains("best") })
    }

    @Test fun habitTaskCorrelationSurfaces() {
        val h = habit("h", "Exercise")
        val window = (0 until 88).map { today - it }
        val exDays = window.filter { it % 2 == 0L }               // exercise done on even days
        val checkins = exDays.map { checkin("h", it) }
        // Every day gets one task; exercise days get a second — so on-days average 2, off-days 1.
        val tasks = ArrayList<com.todocompanion.app.data.entity.TaskEntity>()
        fun taskOn(d: Long, n: Int) = repeat(n) { i ->
            tasks += com.todocompanion.app.data.entity.TaskEntity(id = "t$d-$i", listId = "l", title = "x", completed = true,
                completedAt = d * 86_400_000L + 43_200_000L, createdAt = 0L, updatedAt = 0L)
        }
        window.forEach { d -> taskOn(d, if (d % 2 == 0L) 2 else 1) }
        val insights = com.todocompanion.app.domain.habit.HabitInsights.compute(listOf(h), checkins, tasks, today,
            zone = java.time.ZoneId.of("UTC"))
        assertTrue(insights.any { it.text.contains("Exercise") && it.text.contains("tasks") })
    }

    @Test fun noInsightsWithoutData() {
        assertTrue(com.todocompanion.app.domain.habit.HabitInsights.compute(emptyList(), emptyList(), emptyList(), today).isEmpty())
    }

    @Test fun dailyBriefCountsDueAndDone() {
        val a = habit("a", "Meditate")   // not done today → still due
        val b = habit("b", "Read")       // done today
        val brief = com.todocompanion.app.domain.habit.HabitInsights.dailyBrief(
            listOf(a, b), listOf(checkin("b", today)), emptyList(), today)
        assertTrue(brief != null)
        assertTrue(brief!!.headline.contains("1 of 2"))
        assertTrue(brief.sub.contains("Meditate"))
    }

    @Test fun dailyBriefNullWithoutHabits() {
        assertTrue(com.todocompanion.app.domain.habit.HabitInsights.dailyBrief(emptyList(), emptyList(), emptyList(), today) == null)
    }
}

class HabitQuickParserTest {
    private val P = com.todocompanion.app.domain.habit.HabitQuickParser

    @Test fun parsesMinutesAndMorning() {
        val h = P.parse("meditate 10 min every morning")
        assertEquals("Meditate", h.name)
        assertEquals("min", h.unit)
        assertEquals(10, h.targetPerDay)
        assertTrue(h.reminderTimes.split(",").mapNotNull { it.toIntOrNull() }.contains(8 * 60))
    }

    @Test fun parsesTimesPerWeek() {
        val h = P.parse("gym 3x a week")
        assertEquals(com.todocompanion.app.domain.habit.HabitStats.FREQ_TIMES_WEEK, h.freqType)
        assertEquals(3, h.freqParam)
        assertEquals("Gym", h.name)
    }

    @Test fun parsesEveningTimeAndPages() {
        val h = P.parse("read 20 pages every evening at 9pm")
        assertEquals("pages", h.unit)
        assertEquals(20, h.targetPerDay)
        assertTrue(h.reminderTimes.split(",").mapNotNull { it.toIntOrNull() }.contains(21 * 60))
        assertEquals("Read", h.name)
    }

    @Test fun parsesWeekdays() {
        val h = P.parse("standup weekdays")
        assertEquals("1,2,3,4,5", h.scheduleDays)
    }
}
