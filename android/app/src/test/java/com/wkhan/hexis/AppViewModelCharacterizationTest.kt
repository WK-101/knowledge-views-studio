package com.wkhan.hexis

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.ListEntity
import com.wkhan.hexis.data.entity.WorkspaceEntity
import com.wkhan.hexis.domain.AppSettings
import com.wkhan.hexis.domain.view.SmartKind
import com.wkhan.hexis.domain.view.ViewRef
import com.wkhan.hexis.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * W0 — the first tests to ever construct [AppViewModel]. They are a *characterization* harness: they pin
 * the VM's current observable behaviour so the W1 decomposition (splitting per-feature ViewModels out of the
 * 6.6k-line god object) can be proven equivalent instead of hoped equivalent.
 *
 * The A2 seam makes this possible: the VM's `internal constructor(app, repo)` — now its ONLY constructor —
 * lets a test inject an isolated in-memory-backed repository (the same real [AppRepository] the app uses,
 * over a fresh Room DB). Production wires the same constructor at the composition root through
 * [com.wkhan.hexis.ui.AppViewModelFactory], which resolves the repo from the [App] service-locator
 * outside the VM. Robolectric supplies the Application (the framework couplings — ThemePrefs/SecurePrefs/
 * widgets — are read through `appCtx`, which is SharedPreferences-safe or runCatching-guarded, so
 * construction and the read-model flows run on the JVM).
 *
 * The VM's derived flows go through `.state()` = `flowOn(Dispatchers.Default).stateIn(WhileSubscribed)`, so
 * their upstream runs on real background threads and is *lazy* until collected. [await] therefore subscribes
 * and waits with a real timeout rather than fighting virtual time; [Dispatchers.setMain] with an unconfined
 * dispatcher lets `viewModelScope` (the `stateIn` host) run inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelCharacterizationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository
    private lateinit var vm: AppViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
        vm = AppViewModel(ApplicationProvider.getApplicationContext(), repo)
    }

    @After fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** Wait (real time) for [flow] to emit a value satisfying [predicate], then return it. */
    private fun <T> await(flow: Flow<T>, timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(timeoutMs) { flow.first(predicate) } }

    @Test fun viewModel_constructsWithInjectedRepo_andStartsEmpty() {
        // The executable proof the A2 injection seam works end to end: before this, no test could build the
        // VM at all (it hard-referenced App.repository), leaving the most logic-dense class at 0% coverage.
        assertNotNull(vm)
        assertEquals("no tasks seeded → the task list starts empty", emptyList<Any>(), vm.tasks.value)
    }

    @Test fun tasks_areScopedToTheActiveWorkspace() = runBlocking {
        // Two workspaces, each with a list holding one task. The active-workspace filter (wsTasks/scopedBy —
        // the single most subtle read-model in the VM, and the one a decomposition is most likely to break)
        // must surface only the active space's task.
        val ws1 = repo.createWorkspace("Personal")
        val ws2 = repo.createWorkspace("Work")
        val list1 = repo.createList("P-list", workspaceId = ws1)
        val list2 = repo.createList("W-list", workspaceId = ws2)
        repo.createTask(list1, "personal task")
        repo.createTask(list2, "work task")

        repo.saveSettings(AppSettings(activeWorkspaceId = ws1))
        val inWs1 = await(vm.tasks) { list -> list.any { it.title == "personal task" } }
        assertTrue("active workspace's task is present", inWs1.any { it.title == "personal task" })
        assertTrue("the other workspace's task is filtered out", inWs1.none { it.title == "work task" })

        // Switching the active workspace flips the visible set — no restart, purely reactive.
        repo.saveSettings(AppSettings(activeWorkspaceId = ws2))
        val inWs2 = await(vm.tasks) { list -> list.any { it.title == "work task" } && list.none { it.title == "personal task" } }
        assertTrue("now the work task shows", inWs2.any { it.title == "work task" })
        assertTrue("and the personal task is filtered out", inWs2.none { it.title == "personal task" })
    }

    @Test fun inboxTask_belongsOnlyToTheWorkspaceItWasCapturedIn() = runBlocking {
        // R64 — the shared Inbox is workspace-owned per task: an Inbox capture belongs to the space it was
        // made in, and must not bleed into another space's smart lists. This pins that rule.
        val ws1 = repo.createWorkspace("Personal")
        val ws2 = repo.createWorkspace("Work")
        // createTask stamps an Inbox task with the repo's active workspace, so set it before each capture.
        repo.saveSettings(AppSettings(activeWorkspaceId = ws1))
        // Force the repo's snapshot to ws1, then capture into the shared Inbox.
        repo.createTask(ListEntity.INBOX_ID, "captured in personal")

        repo.saveSettings(AppSettings(activeWorkspaceId = ws1))
        val personalView = await(vm.tasks) { list -> list.any { it.title == "captured in personal" } }
        assertTrue("shows in the space it was captured in", personalView.any { it.title == "captured in personal" })

        repo.saveSettings(AppSettings(activeWorkspaceId = ws2))
        // Give the reactive chain a beat, then assert the inbox capture does NOT leak into ws2's smart list.
        val workView = await(vm.tasks) { list -> list.none { it.title == "captured in personal" } }
        assertTrue("does not leak into another workspace", workView.none { it.title == "captured in personal" })
    }

    @Test fun completingARepeatingTask_rollsItForwardInsteadOfClosingIt() = runBlocking {
        // The single most correctness-critical write path: toggleComplete on a repeating task must advance it
        // to the next occurrence (dates shifted, still open), not mark it done. The pure decision is unit-tested
        // in RecurringRollForwardTest; this pins the VM WIRING around it — the persist + re-surface — end to end,
        // exactly the seam a per-feature-VM split would put at risk.
        val zone = java.time.ZoneId.systemDefault()
        val ws = repo.createWorkspace("W")
        val list = repo.createList("L", workspaceId = ws)
        repo.saveSettings(AppSettings(activeWorkspaceId = ws))
        val due0 = java.time.LocalDate.now(zone).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val id = repo.createTask(list, "water the plants", dueDate = due0)
        val daily = com.wkhan.hexis.domain.recurrence.Recurrence.encode(
            com.wkhan.hexis.domain.recurrence.Recur(com.wkhan.hexis.domain.recurrence.Freq.DAILY))
        repo.getTask(id)!!.let { repo.saveTask(it.copy(rrule = daily)) }

        val before = await(vm.tasks) { l -> l.any { it.id == id && it.rrule == daily } }
        assertEquals("seeded at the original due date", due0, before.first { it.id == id }.dueDate)

        vm.toggleComplete(before.first { it.id == id })

        // It rolls forward: still open, due date advanced past the original occurrence — never closed.
        val after = await(vm.tasks) { l -> l.any { it.id == id && (it.dueDate ?: 0) > due0 } }
        val rolled = after.first { it.id == id }
        assertEquals("a repeating task stays open after completion", false, rolled.completed)
        assertTrue("its due date moved to the next occurrence", (rolled.dueDate ?: 0) > due0)
        assertEquals("and it keeps repeating", daily, rolled.rrule)
    }

    @Test fun capacitySnapshot_countsADatedTaskEstimateAsCommitted() = runBlocking {
        // The workload / over-commit read path behind the "will it fit?" surface: a dated task's estimate
        // becomes committed minutes, and free time is capacity minus commitment. Pins the VM orchestration.
        val zone = java.time.ZoneId.systemDefault()
        val ws = repo.createWorkspace("W")
        val list = repo.createList("L", workspaceId = ws)
        repo.saveSettings(AppSettings(activeWorkspaceId = ws))
        val due = java.time.LocalDate.now(zone).plusDays(3).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val id = repo.createTask(list, "deep work block", dueDate = due)
        repo.getTask(id)!!.let { repo.saveTask(it.copy(estimateMin = 60)) }

        await(vm.tasks) { l -> l.any { it.id == id && it.estimateMin == 60 } }
        val snap = vm.capacitySnapshot(days = 14)
        assertEquals("the 60-min dated task is counted as committed", 60, snap.committedMin)
        assertTrue("a 14-day window has some capacity", snap.capacityMin > 0)
        assertEquals("free = capacity − committed", (snap.capacityMin - 60).coerceAtLeast(0), snap.freeMin)
    }

    @Test fun groups_reDeriveForEachSelectedSmartList() = runBlocking {
        // The single highest-risk read model a per-feature-VM split must preserve: `groups` is the
        // view-filtered/grouped/sorted list behind every task screen, driven by `currentView` through
        // ListPipeline.compute. This drives the REAL pipeline across the three most-used smart lists —
        // Today (dated), Completed, Flagged — proving `select(...)` re-derives `groups` correctly for each,
        // and that the same four tasks partition cleanly (each shows in exactly the one list it belongs to).
        val ws = repo.createWorkspace("W")
        val list = repo.createList("L", workspaceId = ws)
        repo.saveSettings(AppSettings(activeWorkspaceId = ws))
        val zone = java.time.ZoneId.systemDefault()
        val todayDue = java.time.LocalDate.now(zone).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        val dated = repo.createTask(list, "due today", dueDate = todayDue)
        val done = repo.createTask(list, "already done")
        repo.getTask(done)!!.let { repo.saveTask(it.copy(completed = true, completedAt = System.currentTimeMillis())) }
        val flagged = repo.createTask(list, "flagged")
        repo.getTask(flagged)!!.let { repo.saveTask(it.copy(flagId = "f1")) }
        val plain = repo.createTask(list, "open, undated, unflagged")

        // Today: only the dated-today task; the completed, flagged-undated, and plain tasks are excluded.
        vm.select(ViewRef.Smart(SmartKind.TODAY))
        val today = await(vm.groups) { g -> g.flatMap { it.tasks }.any { it.id == dated } }
            .flatMap { it.tasks }.map { it.id }.toSet()
        assertTrue("due-today task is in Today", dated in today)
        assertTrue("completed / flagged-undated / plain are NOT in Today", done !in today && flagged !in today && plain !in today)

        // Completed: only the completed task.
        vm.select(ViewRef.Smart(SmartKind.COMPLETED))
        val completed = await(vm.groups) { g -> g.flatMap { it.tasks }.any { it.id == done } }
            .flatMap { it.tasks }.map { it.id }.toSet()
        assertTrue("completed task is in Completed", done in completed)
        assertTrue("open tasks are NOT in Completed", dated !in completed && flagged !in completed && plain !in completed)

        // Flagged: only the flagged open task.
        vm.select(ViewRef.Smart(SmartKind.FLAGGED))
        val flaggedView = await(vm.groups) { g -> g.flatMap { it.tasks }.any { it.id == flagged } }
            .flatMap { it.tasks }.map { it.id }.toSet()
        assertTrue("flagged task is in Flagged", flagged in flaggedView)
        assertTrue("unflagged / completed are NOT in Flagged", dated !in flaggedView && done !in flaggedView && plain !in flaggedView)
    }

    @Test fun aTaskBlockedByAnOpenPrerequisite_waits_untilTheBlockerCompletes() = runBlocking {
        // Blocked-by → Waiting-on: a GTD seam that has regressed before (Waiting showing empty despite a
        // real blocked-by). Pins the VM wiring end to end — an incomplete prerequisite surfaces the blocked
        // task in WAITING's "Blocked by your task" group; completing the prerequisite releases it.
        val ws = repo.createWorkspace("W")
        val list = repo.createList("L", workspaceId = ws)
        repo.saveSettings(AppSettings(activeWorkspaceId = ws))
        val blocker = repo.createTask(list, "do first")
        val blocked = repo.createTask(list, "waits on the first")
        repo.addDependency(taskId = blocked, dependsOn = blocker)   // "blocked is blocked by blocker"

        vm.select(ViewRef.Smart(SmartKind.WAITING))
        val waiting = await(vm.groups) { g -> g.flatMap { it.tasks }.any { it.id == blocked } }
        assertTrue("the blocked task waits in the Blocked group",
            waiting.firstOrNull { it.key == "waiting:blocked" }?.tasks?.any { it.id == blocked } == true)

        // Completing the prerequisite releases it — it leaves Waiting on the next recompute.
        repo.getTask(blocker)!!.let { repo.saveTask(it.copy(completed = true, completedAt = System.currentTimeMillis())) }
        val cleared = await(vm.groups) { g -> g.flatMap { it.tasks }.none { it.id == blocked } }
        assertTrue("once the blocker is done, the task is no longer waiting",
            cleared.flatMap { it.tasks }.none { it.id == blocked })
    }

    @Test fun subtasks_nestUnderTheirParentInTheListOutline() = runBlocking {
        // Subtask hierarchy: `outlineRows` walks parentId depth-first for a ListView. Pins the wiring that
        // renders a parent with its children indented beneath it — a seam a task-focused VM split would move.
        val ws = repo.createWorkspace("W")
        val list = repo.createList("L", workspaceId = ws)
        repo.saveSettings(AppSettings(activeWorkspaceId = ws))
        val parent = repo.createTask(list, "parent")
        val child = repo.createTask(list, "child", parentId = parent)

        vm.select(ViewRef.ListView(list))
        val rows = await(vm.outlineRows) { r -> r.any { it.task.id == child } && r.any { it.task.id == parent } }
        val parentRow = rows.first { it.task.id == parent }
        val childRow = rows.first { it.task.id == child }
        assertEquals("parent sits at the outline root", 0, parentRow.depth)
        assertTrue("the parent is shown as having children", parentRow.hasChildren)
        assertEquals("the child is nested one level under its parent", 1, childRow.depth)
    }
}
