package com.todocompanion.app

import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.LifeReport
import com.todocompanion.app.domain.ModuleHints
import com.todocompanion.app.domain.OmegaCommand
import com.todocompanion.app.domain.OmegaContext
import com.todocompanion.app.domain.OmegaQuery
import com.todocompanion.app.domain.PeriodRecap
import com.todocompanion.app.domain.YearReviewed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Tier Ω — the command parser, the local query engine, the recap, the hints and the annual report. */
class TierOmegaTests {

    // ---- Ω1: the command parser (pure) ----
    @Test fun parseTrack() {
        val c = OmegaCommand.parse("track deep work")
        assertTrue(c is OmegaCommand.Command.Track)
        assertEquals("deep work", (c as OmegaCommand.Command.Track).activity)
    }
    @Test fun parseGoto() {
        val c = OmegaCommand.parse("go to habits")
        assertTrue(c is OmegaCommand.Command.Goto)
        assertEquals("habits", (c as OmegaCommand.Command.Goto).target)
    }
    @Test fun parseActions() {
        assertEquals(OmegaCommand.Action.PLAN, (OmegaCommand.parse("plan my day") as OmegaCommand.Command.Act).action)
        assertEquals(OmegaCommand.Action.RECAP_LAST_WEEK, (OmegaCommand.parse("recap last week") as OmegaCommand.Command.Act).action)
        assertEquals(OmegaCommand.Action.ANNUAL_REPORT, (OmegaCommand.parse("year in review") as OmegaCommand.Command.Act).action)
    }
    @Test fun parseAskAndCapture() {
        assertTrue(OmegaCommand.parse("how many hours on reading this week") is OmegaCommand.Command.Ask)
        val cap = OmegaCommand.parse("buy milk tomorrow")
        assertTrue(cap is OmegaCommand.Command.Capture)
        assertEquals("buy milk tomorrow", (cap as OmegaCommand.Command.Capture).text)
    }

    // ---- a small fixture shared by the data-driven tests ----
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val todayDay = today.toEpochDay()
    private val now = System.currentTimeMillis()
    private fun ms(h: Int, m: Int = 0) = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun task(id: String, done: Boolean, est: Int? = null) = TaskEntity(
        id = id, listId = "l", title = "task $id", createdAt = now, updatedAt = now,
        completedAt = if (done) ms(12) else null, estimateMin = est,
    )
    private fun fixture() = OmegaContext(
        tasks = listOf(task("a", true), task("b", true), task("c", false)),
        habits = listOf(HabitEntity(id = "h1", name = "Meditate", createdAt = today.minusDays(20).atStartOfDay(zone).toInstant().toEpochMilli())),
        checkins = listOf(HabitCheckinEntity(habitId = "h1", epochDay = todayDay)),
        focus = listOf(FocusSessionEntity(id = "f1", epochDay = todayDay, startMillis = now, minutes = 25)),
        timeEntries = listOf(TimeEntryEntity(id = "e1", activityId = "act1", startMillis = ms(9), endMillis = ms(10))),
        activities = listOf(TimeActivityEntity(id = "act1", name = "Reading", createdAt = now)),
        zone = zone, today = todayDay, now = now,
    )

    // ---- Ω2: the local query engine ----
    @Test fun queryTasksDone() {
        val a = OmegaQuery.answer("how many tasks did I complete today", fixture())
        assertTrue(a.ok); assertTrue(a.text.contains("2 tasks"))
    }
    @Test fun queryTimeOnActivity() {
        val a = OmegaQuery.answer("hours on Reading today", fixture())
        assertTrue(a.ok); assertTrue(a.text.contains("1h")); assertTrue(a.text.contains("Reading"))
    }
    @Test fun queryBestHabit() {
        val a = OmegaQuery.answer("best habit", fixture())
        assertTrue(a.ok); assertTrue(a.text.contains("Meditate"))
    }
    @Test fun queryFocus() {
        val a = OmegaQuery.answer("how much focus today", fixture())
        assertTrue(a.ok); assertTrue(a.text.contains("25m"))
    }
    @Test fun queryUnknownIsGraceful() {
        val a = OmegaQuery.answer("what is the meaning of life", fixture())
        assertFalse(a.ok)   // no template matched → a helpful hint, not a crash
    }

    // ---- Ω5: the any-period recap ----
    @Test fun recapCountsTasks() {
        val r = PeriodRecap.compute(todayDay, todayDay, "Today", fixture())
        assertTrue(r.hasData)
        val tasksLine = r.lines.first { it.label == "Tasks done" }
        assertEquals("2", tasksLine.value)
    }

    // ---- Ω3: adaptive module hints ----
    @Test fun hintsOfferTimeWhenEstimatingButNotTracking() {
        val settings = AppSettings(disabledModules = setOf("time"))
        val tasks = (1..6).map { task("t$it", done = false, est = 30) }
        val hints = ModuleHints.compute(settings, tasks, emptyList())
        assertTrue(hints.any { it.enableModule == "time" && it.key == "hint_enable_time" })
    }
    @Test fun hintsSilentWhenModuleAlreadyOn() {
        val settings = AppSettings()   // all modules on by default
        val tasks = (1..6).map { task("t$it", done = false, est = 30) }
        assertTrue(ModuleHints.compute(settings, tasks, emptyList()).none { it.enableModule == "time" })
    }

    // ---- Ω4: the annual report — now spine-fed (Track 1 Unify) ----
    /** The one year spine over the canonical calendar-year window — what Wrapped, the "Year, reviewed"
     *  screen and the drawer report all fold, so their numbers agree. */
    private fun yearRecap(): YearReviewed.Recap {
        val fx = fixture()
        // A properly-completed task so DoneRecord counts it (the fixture's task() sets completedAt only).
        val doneTask = TaskEntity(id = "d1", listId = "l", title = "shipped", createdAt = now, updatedAt = now, completed = true, completedAt = ms(12))
        val (yStart, yEnd) = YearReviewed.calendarYearWindow(today.year, todayDay)
        return YearReviewed.compute(yStart, yEnd, emptyList(), fx.habits, fx.checkins, fx.timeEntries, fx.activities, zone, now, listOf(doneTask))
    }

    @Test fun annualReportRendersSelfContainedHtml() {
        val html = LifeReport.buildHtml(today.year, yearRecap())
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("Your year in review"))
        assertTrue(html.contains(today.year.toString()))
        assertTrue(html.contains("tasks completed"))
        // fully self-contained — no external resource references.
        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))
    }

    /** Track 1 (Unify) — the report's headline numbers are read straight off the spine, not a parallel fold. */
    @Test fun annualReportHeadlineCountsComeFromTheSpine() {
        val recap = yearRecap()
        val html = LifeReport.buildHtml(today.year, recap)
        assertEquals(1, recap.tasksFinished) // one task completed today (this calendar year)
        assertTrue(html.contains("<div class=\"n\">${recap.tasksFinished}</div><div class=\"k\">tasks completed</div>"))
        assertTrue(html.contains("<div class=\"n\">${recap.habitDaysKept}</div><div class=\"k\">habit check-ins kept</div>"))
    }
}
