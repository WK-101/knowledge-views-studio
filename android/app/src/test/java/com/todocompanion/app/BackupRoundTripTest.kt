package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.HabitEntity
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
 * asserts tasks / lists / folders / habits / check-ins all survive the round-trip. This is the highest-
 * value integration test: a silent change to the backup format is exactly what would lose user data,
 * and this now catches it in CI without a device.
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
        val folderId = srcRepo.createFolder("Projects")
        val listId = srcRepo.createList("Alpha", folderId = folderId)
        val t1 = srcRepo.createTask(listId, "Ship it")
        srcRepo.createTask(listId, "Write tests")
        val habitId = srcRepo.createHabit(HabitEntity(id = "h1", name = "Meditate", createdAt = 0L))
        srcRepo.setCheckinValue(habitId, 20_000L, 1)
        val json = srcRepo.exportJson()
        src.close()

        val dst = freshDb()
        val dstRepo = AppRepository(dst)
        dstRepo.importJsonReplace(json)

        val titles = dstRepo.allTasks.first().map { it.title }.toSet()
        assertTrue("both tasks survive", titles.containsAll(listOf("Ship it", "Write tests")))
        assertEquals("task identity + content survive", "Ship it", dstRepo.getTask(t1)?.title)
        assertEquals("list keeps its folder", folderId, dstRepo.allLists.first().first { it.id == listId }.folderId)
        assertEquals("habit survives", "Meditate", dstRepo.allHabits.first().first { it.id == "h1" }.name)
        assertTrue("check-in survives", dstRepo.allCheckins.first().any { it.habitId == "h1" })
        dst.close()
    }
}
