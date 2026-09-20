package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W2 (scale) — proves the "reverse coherently" move on the notes flow: the new index-backed SQL query
 * [AppRepository.observeNotesByWorkspace] returns EXACTLY what the old in-memory
 * `observeNotes().filter { workspaceId == ws && trashed == t }` returned, so routing the ViewModel's
 * `notes` / `trashedNotes` to it cannot change what the user sees. It also asserts the covering index the
 * query relies on actually exists on the notes table (and the plain workspaceId index it replaced is gone),
 * which — since a fresh DB is created from the entity @Index annotations, and MIGRATION_83_84 mirrors those
 * exact index names — is strong evidence the migration produces a Room-valid schema. (The full migrated
 * path is additionally covered by the instrumented MigrationTest, which replays the whole chain on device.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NoteScopeQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
    }

    @After fun teardown() = db.close()

    // Ids are captured from upsertNote (it may mint its own), so the test never assumes a fixed id.
    private var n1 = ""; private var n2 = ""; private var n3 = ""; private var n4 = ""

    private suspend fun seed() {
        // Two workspaces; a live and a trashed note in each, plus an extra live note in ws1.
        n1 = repo.upsertNote(NoteEntity(id = "", title = "ws1 live A", workspaceId = "ws1"))
        n2 = repo.upsertNote(NoteEntity(id = "", title = "ws1 live B", workspaceId = "ws1"))
        n3 = repo.upsertNote(NoteEntity(id = "", title = "ws1 trashed", workspaceId = "ws1"))
        n4 = repo.upsertNote(NoteEntity(id = "", title = "ws2 live", workspaceId = "ws2"))
        val n5 = repo.upsertNote(NoteEntity(id = "", title = "ws2 trashed", workspaceId = "ws2"))
        repo.trashNote(n3, true)
        repo.trashNote(n5, true)
    }

    private fun inMemory(ws: String, trashed: Boolean): Set<String> = runBlocking {
        repo.observeNotes().first().filter { it.workspaceId == ws && it.trashed == trashed }.map { it.id }.toSet()
    }

    private fun sql(ws: String, trashed: Boolean): Set<String> = runBlocking {
        repo.observeNotesByWorkspace(ws, trashed).first().map { it.id }.toSet()
    }

    @Test fun sqlScopedQuery_matchesTheInMemoryFilter_forEveryWorkspaceAndTrashState() = runBlocking {
        seed()
        for (ws in listOf("ws1", "ws2", "wsEmpty")) {
            for (t in listOf(false, true)) {
                assertEquals("SQL == in-memory for ws=$ws trashed=$t", inMemory(ws, t), sql(ws, t))
            }
        }
        // And a concrete sanity check on the actual sets, not just their equality.
        assertEquals(setOf(n1, n2), sql("ws1", false))
        assertEquals(setOf(n3), sql("ws1", true))
        assertEquals(setOf(n4), sql("ws2", false))
    }

    @Test fun notesTable_hasTheCoveringIndex_andNotTheSupersededOne() {
        val cur = db.openHelper.writableDatabase.query("PRAGMA index_list(`notes`)")
        val names = buildSet { while (cur.moveToNext()) add(cur.getString(cur.getColumnIndexOrThrow("name"))) }
        cur.close()
        assertTrue("the (workspaceId, trashed) covering index exists", names.contains("index_notes_workspaceId_trashed"))
        assertFalse("the plain workspaceId index it replaced is gone", names.contains("index_notes_workspaceId"))
    }
}
