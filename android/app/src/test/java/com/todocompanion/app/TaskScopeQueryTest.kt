package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.FolderEntity
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TaskEntity
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
 * W2b (scale) — proves the "reverse coherently" move on the CORE task list. The new index-backed SQL query
 * [AppRepository.observeTasksByWorkspace] must return EXACTLY the set the old in-memory `wsTasks` filter did,
 * so routing the ViewModel's active-workspace task set to it cannot change what the user sees in any smart
 * list, folder, or list view. The old rule (reproduced verbatim in [oldInMemoryRule]) was an if/else:
 *
 *   if (listId == INBOX_ID) workspaceId == ws            // a shared-Inbox task belongs to the space it was captured in
 *   else                    listId in wsLists || folderId in wsFolders   // everything else via its container's workspace
 *
 * The subtle trap this test exists to catch: the Inbox LIST row itself lives in `lists` with workspaceId
 * "default". A naive OR-of-three-branches SQL would therefore pull an Inbox task captured in a NON-default
 * workspace into the default workspace (via the list-membership branch), diverging from the strict if/else.
 * [seed] plants exactly that case ([inboxInOther]) plus every other container shape, and the parity assertion
 * would fail if the query's else branch weren't guarded by `listId <> :inboxId`.
 *
 * It also asserts the covering indices the query relies on exist on the tasks table — since a fresh DB is
 * built from the entity @Index annotations and MIGRATION_84_85 recreates those exact index names, this is
 * strong evidence the migration produces a Room-valid schema. (The full migrated path is additionally covered
 * by the instrumented MigrationTest, which replays the whole chain on device.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TaskScopeQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
    }

    @After fun teardown() = db.close()

    // Task ids we assert on by name, so the concrete-set checks don't depend on insertion order.
    private var listTask1 = ""; private var folderTask1 = ""; private var inboxInWs1 = ""
    private var inboxInOther = ""; private var listTask2 = ""

    /** Seed two real workspaces ("ws1", "other") sharing the one Inbox, covering every container shape. */
    private suspend fun seed() {
        val taskDao = db.taskDao(); val listDao = db.listDao(); val folderDao = db.folderDao()
        // The shared Inbox list, exactly as the app seeds it: default workspace, well-known id.
        listDao.upsert(ListEntity(id = ListEntity.INBOX_ID, name = "Inbox", sortOrder = 0.0))
        // A list + a folder in each of the two workspaces.
        listDao.upsert(ListEntity(id = "L1", name = "ws1 list", workspaceId = "ws1"))
        listDao.upsert(ListEntity(id = "L2", name = "other list", workspaceId = "other"))
        folderDao.upsert(FolderEntity(id = "F1", name = "ws1 folder", workspaceId = "ws1"))
        folderDao.upsert(FolderEntity(id = "F2", name = "other folder", workspaceId = "other"))

        var order = 0.0
        fun t(id: String, listId: String, ws: String, folderId: String? = null): TaskEntity = TaskEntity(
            id = id, listId = listId, folderId = folderId, sortOrder = order++,
            title = id, workspaceId = ws, createdAt = 0L, updatedAt = 0L,
        )
        // In a real list belonging to ws1 → belongs to ws1 via list membership.
        listTask1 = "T-list-ws1"; taskDao.upsert(t(listTask1, "L1", "ws1"))
        // In a real list belonging to "other".
        listTask2 = "T-list-other"; taskDao.upsert(t(listTask2, "L2", "other"))
        // Captured straight into a folder (empty listId), folder belongs to ws1.
        folderTask1 = "T-folder-ws1"; taskDao.upsert(t(folderTask1, "", "ws1", folderId = "F1"))
        // A shared-Inbox task captured while in ws1 (its own workspaceId = ws1).
        inboxInWs1 = "T-inbox-ws1"; taskDao.upsert(t(inboxInWs1, ListEntity.INBOX_ID, "ws1"))
        // THE TRAP: a shared-Inbox task captured while in "other". Its own workspaceId = "other", but it sits
        // in the Inbox list row whose workspaceId is "default". It must appear ONLY in "other", never in the
        // default workspace via list membership.
        inboxInOther = "T-inbox-other"; taskDao.upsert(t(inboxInOther, ListEntity.INBOX_ID, "other"))
    }

    /** The exact old in-memory rule, reproduced from the pre-W2b AppViewModel.wsTasks combine. */
    private fun oldInMemoryRule(all: List<TaskEntity>, wsLists: Set<String>, wsFolders: Set<String>, ws: String): Set<String> =
        all.filter {
            if (it.listId == ListEntity.INBOX_ID) it.workspaceId == ws
            else it.listId in wsLists || (it.folderId != null && it.folderId in wsFolders)
        }.map { it.id }.toSet()

    private fun sql(ws: String): Set<String> = runBlocking {
        repo.observeTasksByWorkspace(ws).first().map { it.id }.toSet()
    }

    private fun inMemory(ws: String): Set<String> = runBlocking {
        val all = repo.allTasksOnce()
        // Derive the workspace's list/folder id sets exactly as the old activeListIds/activeFolderIds did:
        // lists whose workspaceId matches (plus the always-present Inbox id), and folders whose ws matches.
        val listIds = repo.allLists.first().filter { it.workspaceId == ws }.map { it.id }.toSet() + ListEntity.INBOX_ID
        val folderIds = repo.allFolders.first().filter { it.workspaceId == ws }.map { it.id }.toSet()
        oldInMemoryRule(all, listIds, folderIds, ws)
    }

    @Test fun sqlScopedQuery_matchesTheOldInMemoryRule_forEveryWorkspace() = runBlocking {
        seed()
        for (ws in listOf("ws1", "other", "default", "wsEmpty")) {
            assertEquals("SQL == old in-memory rule for ws=$ws", inMemory(ws), sql(ws))
        }
        // Concrete sanity: ws1 sees its list task, its folder task, and its own Inbox capture — nothing else.
        assertEquals(setOf(listTask1, folderTask1, inboxInWs1), sql("ws1"))
        // "other" sees its list task and its own Inbox capture.
        assertEquals(setOf(listTask2, inboxInOther), sql("other"))
        // The trap: the default workspace must NOT show the Inbox task captured in "other" (nor ws1's).
        assertFalse("Inbox task captured in 'other' must not leak into default", sql("default").contains(inboxInOther))
        assertFalse("Inbox task captured in 'ws1' must not leak into default", sql("default").contains(inboxInWs1))
        assertTrue("a workspace with no containers and no Inbox captures is empty", sql("wsEmpty").isEmpty())
    }

    @Test fun tasksTable_hasTheWorkspaceAndFolderIndices_theQueryReliesOn() {
        val cur = db.openHelper.writableDatabase.query("PRAGMA index_list(`tasks`)")
        val names = buildSet { while (cur.moveToNext()) add(cur.getString(cur.getColumnIndexOrThrow("name"))) }
        cur.close()
        assertTrue("the workspaceId index exists", names.contains("index_tasks_workspaceId"))
        assertTrue("the folderId index exists", names.contains("index_tasks_folderId"))
    }
}
