package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.WorkspaceEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.ui.AppViewModel
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
 * [com.todocompanion.app.ui.AppViewModelFactory], which resolves the repo from the [App] service-locator
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
        val daily = com.todocompanion.app.domain.recurrence.Recurrence.encode(
            com.todocompanion.app.domain.recurrence.Recur(com.todocompanion.app.domain.recurrence.Freq.DAILY))
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
}
