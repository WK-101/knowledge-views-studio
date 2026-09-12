package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.todocompanion.app.data.entity.AttachmentEntity
import com.todocompanion.app.data.entity.NoteEntity
import com.todocompanion.app.data.entity.NotebookEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── Notes: the repository IS the referential-integrity contract (the schema declares no foreign
    // keys; see NoteEntity's KDoc). These pin that deleteNote/deleteNotebook keep the graph orphan-free. ──

    @Test fun deleteNote_leavesNoOrphanChildRows() = runBlocking {
        // A note with every kind of child row hanging off it: tags, contexts, a wiki-link, a revision,
        // and an attachment.
        val noteId = repo.upsertNote(NoteEntity(id = "", title = "Parent", body = "See [[Elsewhere]]\n- [ ] todo"))
        repo.setNoteTags(noteId, listOf("tag-1", "tag-2"))
        repo.setNoteContexts(noteId, listOf("ctx-1"))
        repo.saveNoteRevision(noteId, keep = 5)
        db.attachmentDao().upsert(AttachmentEntity(
            id = "att-1", taskId = "", fileName = "a.png", mime = "image/png", sizeBytes = 1L,
            isImage = true, addedAt = 1L, contentBase64 = "", noteId = noteId,
        ))

        // Precondition: every child row exists.
        assertTrue(repo.getNoteTagCrossRefs().any { it.noteId == noteId })
        assertTrue(repo.getNoteContextCrossRefs().any { it.noteId == noteId })
        assertTrue(repo.getNoteLinksOnce().any { it.noteId == noteId })
        assertTrue(repo.getNoteRevisionsOnce().any { it.noteId == noteId })
        assertTrue(db.noteDao().attachmentsForNote(noteId).isNotEmpty())

        repo.deleteNote(noteId)

        // The note and EVERY child row are gone — no orphans left behind.
        assertNull(repo.getNote(noteId))
        assertTrue("orphan tag refs", repo.getNoteTagCrossRefs().none { it.noteId == noteId })
        assertTrue("orphan context refs", repo.getNoteContextCrossRefs().none { it.noteId == noteId })
        assertTrue("orphan links", repo.getNoteLinksOnce().none { it.noteId == noteId })
        assertTrue("orphan revisions", repo.getNoteRevisionsOnce().none { it.noteId == noteId })
        assertTrue("orphan attachments", db.noteDao().attachmentsForNote(noteId).isEmpty())
    }

    @Test fun deleteNotebook_reparentsNotesInsteadOfDeleting() = runBlocking {
        val nbId = repo.upsertNotebook(NotebookEntity(id = "", name = "Work"))
        val noteId = repo.upsertNote(NoteEntity(id = "", title = "In notebook", notebookId = nbId))

        repo.deleteNotebook(nbId)

        val n = repo.getNote(noteId)
        assertNotNull("a notebook's notes survive its deletion", n)
        assertNull("the note is reparented to 'no notebook'", n!!.notebookId)
    }
}
