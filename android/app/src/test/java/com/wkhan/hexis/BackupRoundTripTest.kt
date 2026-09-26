package com.wkhan.hexis

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.DayLogEntity
import com.wkhan.hexis.data.entity.EventCalendarEntity
import com.wkhan.hexis.data.entity.EventEntity
import com.wkhan.hexis.data.entity.HabitEntity
import com.wkhan.hexis.data.entity.SealedNoteEntity
import com.wkhan.hexis.data.entity.TimeEntryEntity
import com.wkhan.hexis.data.entity.toDomain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R94 — the data-critical backup path, end-to-end on the JVM under Robolectric. Builds a store in one
 * in-memory database, exports it to the app's JSON format, imports that into a *fresh* database, and
 * asserts the data survives the round-trip. This is the highest-value integration test: a silent change
 * to the backup format is exactly what would lose user data, and this catches it in CI without a device.
 *
 * R-audit: broadened beyond tasks/lists/folders/habits to also cover the surfaces most likely to be
 * forgotten in the export/import body — settings (the whole key/value map), day-logs (daily-review data),
 * time tracking, the dedicated calendar, sealed letters, and an attachment's inlined bytes. A future table
 * added to the schema + envelope but not wired into exportJson()/importJsonReplace() now fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRoundTripTest {
    private fun freshDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test fun exportThenImportPreservesTheStore() = runBlocking {
        val src = freshDb()
        val srcRepo = AppRepository(src)
        // Core structural + task data.
        val folderId = srcRepo.createFolder("Projects")
        val listId = srcRepo.createList("Alpha", folderId = folderId)
        val t1 = srcRepo.createTask(listId, "Ship it")
        val t2 = srcRepo.createTask(listId, "Write tests")
        val habitId = srcRepo.createHabit(HabitEntity(id = "h1", name = "Meditate", createdAt = 0L))
        srcRepo.setCheckinValue(habitId, 20_000L, 1)
        // #14 — task-adjacent relational tables: a flag assigned to a task, a tag + a context linked to a task,
        // and a cross-task dependency. Each rides its own backup-envelope collection and was previously
        // unasserted here; a future change that drops one from export/import now fails.
        val flagId = srcRepo.createFlag("Urgent", 0xFFEF4444L)
        srcRepo.getTask(t1)!!.let { srcRepo.saveTask(it.copy(flagId = flagId)) }
        srcRepo.upsertTag(com.wkhan.hexis.data.entity.TagEntity(id = "tag-work", name = "work"))
        srcRepo.setTaskTags(t1, listOf("tag-work"))
        srcRepo.upsertContext(com.wkhan.hexis.data.entity.ContextEntity(id = "ctx-home", name = "@home"))
        srcRepo.setTaskContexts(t1, listOf("ctx-home"))
        srcRepo.addDependency(taskId = t2, dependsOn = t1)
        // Settings (the whole key/value map — theme, review config, share config, daily questions, …).
        srcRepo.saveSettings(srcRepo.settingsSnapshot().copy(weekStart = 3, dailyQuestionsJson = "SEED-Q"))
        // W3 — Phase B goals now live in a Room table (with milestones + key results), not the settings blob;
        // the review log likewise. Seed the TABLE — export regenerates the transport goalsJson/goalReviewsJson
        // from it, and import populates a fresh table back from that transport.
        val seedGoal = com.wkhan.hexis.domain.Goal(
            id = "g1", name = "Ship it", emoji = "🚀", area = "Work", identity = "a builder who ships",
            milestones = listOf(com.wkhan.hexis.domain.GoalMilestone(id = "m1", title = "MVP", done = true)),
            keyResults = listOf(com.wkhan.hexis.domain.KeyResult(id = "k1", title = "Users", current = 5.0, target = 100.0)),
            cycleStartEpochDay = 19_000L, cycleWeeks = 12, reviewCadenceDays = 7, workspaceId = "default",
        )
        val seedReview = com.wkhan.hexis.domain.GoalReview(id = "r1", goalId = "", epochDay = 19_500L, executionPct = 80, commitmentsKept = 3, commitmentsTotal = 4, note = "seed-review")
        srcRepo.replaceWorkspaceGoals("default", listOf(seedGoal))
        srcRepo.replaceGoalReviews(listOf(seedReview))
        // W3 — Routines & their run history are Room-backed too; seed the tables (export regenerates the transport
        // routines/routine_runs JSON from them, import repopulates fresh tables).
        val seedRoutine = com.wkhan.hexis.domain.Routine(
            id = "rt1", name = "Morning primer", emoji = "☀️", activityId = "act1",
            steps = listOf(com.wkhan.hexis.domain.RoutineStep(id = "st1", title = "Water", durationSec = 60, essential = true)),
            whenReminderMin = 420, days = listOf(1, 2, 3, 4, 5), workspaceId = "default",
        )
        srcRepo.replaceWorkspaceRoutines("default", listOf(seedRoutine))
        srcRepo.appendRoutineRun(com.wkhan.hexis.domain.RoutineRun(routineId = "rt1", epochDay = 19_500L, startedAtMillis = 5_000L, completedStepIds = listOf("st1"), totalSec = 60))
        // Daily-review data (the felt-state / close-the-day store).
        srcRepo.upsertDayLog(DayLogEntity(epochDay = 20_000L, pmReflection = "seed-reflection", dayRating = 4))
        // Time tracking (activity + a logged interval).
        val actId = srcRepo.createTimeActivity("Deep work", null, null)
        srcRepo.upsertTimeEntry(TimeEntryEntity(id = "te1", activityId = actId, startMillis = 1_000L, endMillis = 61_000L))
        // Dedicated calendar (calendar + event).
        srcRepo.upsertEventCalendar(EventCalendarEntity(id = "cal1", name = "Personal", colorArgb = 0xFF33AA55L, createdAt = 0L))
        srcRepo.upsertEvent(EventEntity(id = "ev1", calendarId = "cal1", title = "Standup", startMillis = 1_000L, endMillis = 2_000L, createdAt = 0L, updatedAt = 0L))
        // Sealed "letter to your future self".
        srcRepo.upsertSealedNote(SealedNoteEntity(id = "sn1", createdEpochDay = 19_000L, revealEpochDay = 19_100L, title = "Future", body = "hello-future", anchorHash = "h", sealedCount = 0))
        // Attachment whose bytes are inlined as base64 (the self-contained-backup guarantee).
        val attachBytes = "hello-bytes".toByteArray()
        srcRepo.addAttachment(t1, "note.txt", "text/plain", attachBytes)
        // Notes module (v66): a notebook + a note in it, a tag linked to the note.
        val nbId = srcRepo.upsertNotebook(com.wkhan.hexis.data.entity.NotebookEntity(id = "nb1", name = "Journal"))
        srcRepo.upsertTag(com.wkhan.hexis.data.entity.TagEntity(id = "ntag1", name = "idea"))
        val noteId = srcRepo.upsertNote(com.wkhan.hexis.data.entity.NoteEntity(
            id = "n1", title = "First note", body = "# Heading\n\nseed-note-body", notebookId = nbId, kind = "journal",
            favorite = true, readonly = true,   // Wave B flags
        ))
        srcRepo.setNoteTags(noteId, listOf("ntag1"))
        srcRepo.saveNoteRevision(noteId, 50)   // Wave B — a version snapshot must ride the backup too

        val json = srcRepo.exportJson()
        src.close()

        val dst = freshDb()
        val dstRepo = AppRepository(dst)
        dstRepo.importJsonReplace(json)

        // Core.
        val titles = dstRepo.allTasks.first().map { it.title }.toSet()
        assertTrue("both tasks survive", titles.containsAll(listOf("Ship it", "Write tests")))
        assertEquals("task identity + content survive", "Ship it", dstRepo.getTask(t1)?.title)
        assertEquals("list keeps its folder", folderId, dstRepo.allLists.first().first { it.id == listId }.folderId)
        assertEquals("habit survives", "Meditate", dstRepo.allHabits.first().first { it.id == "h1" }.name)
        assertTrue("check-in survives", dstRepo.allCheckins.first().any { it.habitId == "h1" })
        // Task-adjacent relational tables (#14).
        assertTrue("flag survives", dstRepo.allFlags.first().any { it.id == flagId && it.name == "Urgent" })
        assertEquals("task keeps its flag", flagId, dstRepo.getTask(t1)?.flagId)
        assertTrue("task↔tag link survives", dstRepo.taskTagRefs.first().any { it.taskId == t1 && it.tagId == "tag-work" })
        assertTrue("task↔context link survives", dstRepo.taskContextRefs.first().any { it.taskId == t1 && it.contextId == "ctx-home" })
        assertTrue("dependency survives", dstRepo.allDependencies.first().any { it.taskId == t2 && it.dependsOnTaskId == t1 })
        // Settings.
        val restored = dstRepo.settingsSnapshot()
        assertEquals("settings survive (weekStart)", 3, restored.weekStart)
        assertEquals("settings survive (daily questions)", "SEED-Q", restored.dailyQuestionsJson)
        // Phase B goals (W3 — now Room-backed): the goal with its milestones + key results, and the review log,
        // must round-trip into the destination TABLE (not just the transport JSON). This proves both halves:
        // export regenerated the transport from the src table, and import repopulated the dst table from it.
        val restoredGoal = dstRepo.goalsFromTableOnce().map { it.toDomain() }.firstOrNull { it.id == "g1" }
        assertEquals("goal survives with area", "Work", restoredGoal?.area)
        assertEquals("goal milestone survives", 1, restoredGoal?.milestones?.count { it.done })
        assertEquals("goal key result survives", 5.0, restoredGoal?.keyResults?.firstOrNull()?.current)
        assertEquals("goal cycle survives", 12, restoredGoal?.cycleWeeks)
        assertTrue("goal review survives", dstRepo.goalReviewsFromTableOnce().map { it.toDomain() }.any { it.id == "r1" && it.executionPct == 80 })
        // Routines (W3 — Room-backed): the routine with its step + cadence, and a run, round-trip into the table.
        val restoredRoutine = dstRepo.routinesFromTableOnce().map { it.toDomain() }.firstOrNull { it.id == "rt1" }
        assertEquals("routine survives with reminder", 420, restoredRoutine?.whenReminderMin)
        assertEquals("routine step survives", "Water", restoredRoutine?.steps?.firstOrNull()?.title)
        assertEquals("routine cadence survives", listOf(1, 2, 3, 4, 5), restoredRoutine?.days)
        assertTrue("routine run survives", dstRepo.routineRunsFromTableOnce().map { it.toDomain() }.any { it.routineId == "rt1" && it.completedStepIds == listOf("st1") })
        // Day-log.
        assertTrue("day-log survives", dstRepo.dayLogsOnce().any { it.epochDay == 20_000L && it.pmReflection == "seed-reflection" && it.dayRating == 4 })
        // Time tracking.
        assertTrue("time entry survives", dstRepo.timeEntriesOnce().any { it.id == "te1" && it.activityId == actId })
        // Calendar.
        assertTrue("event survives", dstRepo.eventsOnce().any { it.id == "ev1" && it.title == "Standup" })
        // Sealed note.
        assertTrue("sealed note survives", dstRepo.allSealedNotesOnce().any { it.id == "sn1" && it.body == "hello-future" })
        // Attachment bytes: they must reappear in a fresh export of the restored store.
        val b64 = android.util.Base64.encodeToString(attachBytes, android.util.Base64.NO_WRAP)
        assertTrue("attachment bytes survive", dstRepo.exportJson().contains(b64))
        // Notes module: note + notebook + note↔tag link round-trip.
        val restoredNote = dstRepo.getNote("n1")
        assertEquals("note survives with body", "# Heading\n\nseed-note-body", restoredNote?.body)
        assertEquals("note keeps its notebook", "nb1", restoredNote?.notebookId)
        assertEquals("note keeps its kind", "journal", restoredNote?.kind)
        assertTrue("notebook survives", dstRepo.getNotebooksOnce().any { it.id == "nb1" && it.name == "Journal" })
        assertTrue("note↔tag link survives", dstRepo.getNoteTagCrossRefs().any { it.noteId == "n1" && it.tagId == "ntag1" })
        assertTrue("note favorite flag survives", restoredNote?.favorite == true)
        assertTrue("note read-only flag survives", restoredNote?.readonly == true)
        assertTrue("note version snapshot survives", dstRepo.getNoteRevisionsOnce().any { it.noteId == "n1" })
        dst.close()
    }

    /** R-audit hardening: a replace-restore must NOT wipe the device-local sync/backup passphrase (it is
     *  deliberately excluded from the backup file, so it has to be preserved locally across the restore). */
    @Test fun replaceRestoreKeepsLocalPassphrase() = runBlocking {
        val src = freshDb(); val srcRepo = AppRepository(src)
        srcRepo.createTask(srcRepo.createList("L"), "T")
        val json = srcRepo.exportJson(); src.close()

        val dst = freshDb(); val dstRepo = AppRepository(dst)
        dstRepo.saveSettings(dstRepo.settingsSnapshot().copy(syncPassphrase = "hunter2"))
        dstRepo.importJsonReplace(json)
        assertEquals("local passphrase preserved across restore", "hunter2", dstRepo.settingsSnapshot().syncPassphrase)
        dst.close()
    }
}
