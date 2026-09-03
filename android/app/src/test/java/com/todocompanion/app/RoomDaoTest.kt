package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.entity.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R92 — Room DAO integration tests running on the JVM under Robolectric (no emulator/device). Builds a
 * fresh in-memory AppDatabase per test (framework SQLite, not SQLCipher — the DAO queries are identical
 * either way) and exercises the real generated Room code: the task queries, ordering and hierarchy that
 * the pure unit tests can't reach. This fills the middle of the test pyramid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    private fun task(id: String, listId: String = "l", parentId: String? = null, sort: Double = 0.0, title: String = id) =
        TaskEntity(id = id, listId = listId, title = title, parentId = parentId, sortOrder = sort, createdAt = 0L, updatedAt = 0L)

    @Test fun upsertThenGetByIdRoundTrips() = runBlocking {
        val dao = db.taskDao()
        dao.upsert(task("t1", title = "Buy milk"))
        val got = dao.getById("t1")
        assertNotNull(got)
        assertEquals("Buy milk", got!!.title)
        assertNull("missing id returns null", dao.getById("nope"))
    }

    @Test fun childrenOfAndMaxSortOrderRespectParentAndList() = runBlocking {
        val dao = db.taskDao()
        dao.upsert(task("p", listId = "l"))
        dao.upsert(task("c1", listId = "l", parentId = "p", sort = 1.0))
        dao.upsert(task("c2", listId = "l", parentId = "p", sort = 2.0))
        dao.upsert(task("top", listId = "l", parentId = null, sort = 5.0))
        assertEquals(listOf("c1", "c2"), dao.childrenOf("p").map { it.id })   // ordered, only p's kids
        assertEquals(2.0, dao.maxSortOrder("l", "p"), 1e-9)
        assertEquals(5.0, dao.maxSortOrder("l", null), 1e-9)                  // top-level max (p=0, top=5)
    }

    @Test fun observeAllOrdersBySortOrder() = runBlocking {
        val dao = db.taskDao()
        dao.upsert(task("a", sort = 2.0))
        dao.upsert(task("b", sort = 1.0))
        assertEquals(listOf("b", "a"), dao.observeAll().first().map { it.id })
    }

    @Test fun deleteByIdRemovesRow() = runBlocking {
        val dao = db.taskDao()
        dao.upsert(task("x"))
        dao.deleteById("x")
        assertNull(dao.getById("x"))
        assertEquals(0, dao.getAll().size)
    }
}
