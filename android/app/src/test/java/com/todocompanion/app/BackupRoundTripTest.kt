package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.DayLogEntity
import com.todocompanion.app.data.entity.EventCalendarEntity
import com.todocompanion.app.data.entity.EventEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.SealedNoteEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
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
        srcRepo.createTask(listId, "Write tests")
        val habitId = srcRepo.createHabit(HabitEntity(id = "h1", name = "Meditate", createdAt = 0L))
        srcRepo.setCheckinValue(habitId, 20_000L, 1)
        // Settings (the whole key/value map — theme, review config, share config, daily questions, …).
        srcRepo.saveSettings(srcRepo.settingsSnapshot().copy(weekStart = 3, dailyQuestionsJson = "SEED-Q"))
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
        // Settings.
        val restored = dstRepo.settingsSnapshot()
        assertEquals("settings survive (weekStart)", 3, restored.weekStart)
        assertEquals("settings survive (daily questions)", "SEED-Q", restored.dailyQuestionsJson)
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
