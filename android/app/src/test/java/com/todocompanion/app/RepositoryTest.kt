package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R92 — repository integration tests on the JVM under Robolectric (no emulator/device). Drives the real
 * AppRepository against a fresh in-memory Room DB, so a single test covers the repo logic, the generated
 * DAO code, and the SQL together — the end-to-end data layer the pure unit tests can't reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
    }

    @After fun teardown() = db.close()

    @Test fun createTaskUnderListRoundTrips() = runBlocking {
        val listId = repo.createList("Work")
        val id = repo.createTask(listId, "Ship it")
        val t = repo.getTask(id)
        assertNotNull(t)
        assertEquals("Ship it", t!!.title)
        assertEquals(listId, t.listId)
        assertTrue("new task is not completed", !t.completed && !t.trashed)
    }

    @Test fun createListInFolderNestsCorrectly() = runBlocking {
        val folderId = repo.createFolder("Projects")
        val listId = repo.createList("Alpha", folderId = folderId)
        val lists = repo.allLists.first()
        assertEquals(folderId, lists.first { it.id == listId }.folderId)
    }

    @Test fun createdTasksSurfaceInObservedStream() = runBlocking {
        val listId = repo.createList("Inbox")
        repo.createTask(listId, "One")
        repo.createTask(listId, "Two")
        val titles = repo.allTasks.first().filter { it.listId == listId }.map { it.title }.toSet()
        assertEquals(setOf("One", "Two"), titles)
    }
}
