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

    @Test fun inlineBodyTagsMaterializeIntoStructuredTags() = runBlocking {
        // A body with two inline #tags — and one already-typed duplicate case (#Idea vs #idea).
        val noteId = repo.upsertNote(NoteEntity(id = "", title = "Plan", body = "Ship #project and capture #idea. Again #idea."))
        // Structured tags now exist for both names (deduped, case-insensitive), created once.
        val tagNames = db.tagDao().getAll().map { it.name.lowercase() }.toSet()
        assertTrue("project tag created", "project" in tagNames)
        assertTrue("idea tag created", "idea" in tagNames)
        // The note is linked to both — so chips / untagged / hasTag all agree with the body.
        val linkedTagIds = repo.getNoteTagCrossRefs().filter { it.noteId == noteId }.map { it.tagId }.toSet()
        val ideaId = db.tagDao().getAll().first { it.name.equals("idea", true) }.id
        val projectId = db.tagDao().getAll().first { it.name.equals("project", true) }.id
        assertTrue(ideaId in linkedTagIds && projectId in linkedTagIds)
        assertEquals("no duplicate tag rows for #idea/#Idea", 2, db.tagDao().getAll().count { it.name.lowercase() in setOf("project", "idea") })
    }

    @Test fun materializeNoteTags_isAdditive_keepsManualTags() = runBlocking {
        val noteId = repo.upsertNote(NoteEntity(id = "", title = "N", body = "just #alpha"))
        // A manually-assigned tag not present in the body.
        db.tagDao().upsert(com.todocompanion.app.data.entity.TagEntity("manual", "manual"))
        repo.setNoteTags(noteId, (repo.getNoteTagCrossRefs().filter { it.noteId == noteId }.map { it.tagId } + "manual").distinct())
        // Re-save (body unchanged): the body tag re-materializes but the manual tag is untouched.
        repo.upsertNote(repo.getNote(noteId)!!)
        val linked = repo.getNoteTagCrossRefs().filter { it.noteId == noteId }.map { it.tagId }.toSet()
        assertTrue("manual tag survives re-save", "manual" in linked)
        val alphaId = db.tagDao().getAll().first { it.name.equals("alpha", true) }.id
        assertTrue("body tag still present", alphaId in linked)
    }

    @Test fun upsertNote_materializesPreviewAndHasOpen() = runBlocking {
        val id = repo.upsertNote(NoteEntity(id = "", title = "T", body = "# Heading\n\nSome prose here.\n- [ ] a todo"))
        val n = repo.getNote(id)!!
        assertTrue("preview is plain prose (markdown stripped)", n.preview.contains("Some prose here"))
        assertTrue("preview drops the # heading mark", !n.preview.contains("#"))
        assertTrue("hasOpen set from the unchecked item", n.hasOpen)
        // Editing the body away from having an open item clears the flag on save.
        val cleared = repo.getNote(id)!!.copy(body = "no tasks now")
        repo.upsertNote(cleared)
        assertTrue("hasOpen cleared", !repo.getNote(id)!!.hasOpen)
    }

    // ── FTS search correctness — the manual index stays in step with edits, trash/restore, and tags. ──

    @Test fun search_findsByTitleAndBody_andHonorsTrashRestore() = runBlocking {
        val a = repo.upsertNote(NoteEntity(id = "", title = "Alpha meeting", body = "discuss the roadmap"))
        val b = repo.upsertNote(NoteEntity(id = "", title = "Beta", body = "grocery list"))
        assertTrue("title match", a in repo.searchNoteIds("meeting"))
        assertTrue("body match", a in repo.searchNoteIds("roadmap"))
        assertTrue(b in repo.searchNoteIds("grocery"))
        assertTrue("no cross match", b !in repo.searchNoteIds("roadmap"))
        repo.trashNote(a, true)
        assertTrue("trashed note drops out of search", a !in repo.searchNoteIds("roadmap"))
        repo.trashNote(a, false)
        assertTrue("restored note is searchable again", a in repo.searchNoteIds("roadmap"))
    }

    @Test fun search_findsByMaterializedInlineTag() = runBlocking {
        val id = repo.upsertNote(NoteEntity(id = "", title = "Note", body = "planning #quarterly review"))
        // #quarterly becomes a structured tag AND is mirrored into the note's FTS text (reindex).
        assertTrue("found by its inline tag name", id in repo.searchNoteIds("quarterly"))
    }

    @Test fun search_reflectsEdits() = runBlocking {
        val id = repo.upsertNote(NoteEntity(id = "", title = "Draft", body = "old content"))
        assertTrue(id in repo.searchNoteIds("old"))
        repo.upsertNote(repo.getNote(id)!!.copy(body = "new content"))
        assertTrue("stale term no longer matches", id !in repo.searchNoteIds("old"))
        assertTrue("new term matches", id in repo.searchNoteIds("new"))
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
