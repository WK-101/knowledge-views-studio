package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.NoteEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.Goal
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Re-audit #13 — characterization harness for the five feature collaborators carved out of AppViewModel
 * (timeVm / notesVm / habitsVm / goalsRoutinesVm / backupSyncVm). [AppViewModelCharacterizationTest] pins the
 * task/list read models on the parent VM; these pin the ONE observable contract each collaborator adds — its
 * workspace-scoped read flow or its settings-write wiring — so a future move of any collaborator (a shared VM
 * base, a further split) is proven equivalent, not hoped equivalent. Each collaborator is reached through the
 * real, constructed parent VM (vm.timeVm, vm.notesVm, …), exactly as production wires it.
 *
 * Same seam as the parent harness: an isolated in-memory Room DB behind the real AppRepository, Robolectric
 * for the Application, `.state()` flows awaited on real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeatureViewModelCharacterizationTest {

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

    private fun <T> await(flow: Flow<T>, timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(timeoutMs) { flow.first(predicate) } }

    @Test fun timeVm_surfacesTimeActivitiesScopedToTheActiveWorkspace() = runBlocking {
        // TimeTrackingViewModel owns the workspace-scoped `timeActivities` flow. createTimeActivity stamps the
        // repo's active workspace, so an activity made in one space must surface there and vanish from another.
        val ws1 = repo.createWorkspace("Personal")
        val ws2 = repo.createWorkspace("Work")
        repo.saveSettings(AppSettings(activeWorkspaceId = ws1))
        repo.createTimeActivity("Deep work", null, null)

        val inWs1 = await(vm.timeVm.timeActivities) { it.any { a -> a.name == "Deep work" } }
        assertTrue("the activity shows in the space it was created in", inWs1.any { it.name == "Deep work" })

        repo.saveSettings(AppSettings(activeWorkspaceId = ws2))
        val inWs2 = await(vm.timeVm.timeActivities) { it.none { a -> a.name == "Deep work" } }
        assertTrue("and does not leak into another workspace", inWs2.none { it.name == "Deep work" })
    }

    @Test fun habitsVm_surfacesHabitsScopedToTheActiveWorkspace() = runBlocking {
        // HabitsViewModel.habits filters the whole habit table to the active workspace (and drops archived /
        // trashed). Pin that scoping — the read model behind every Habits surface.
        val ws1 = repo.createWorkspace("Personal")
        val ws2 = repo.createWorkspace("Work")
        repo.createHabit("Meditate", null, null, 1, ws1)

        repo.saveSettings(AppSettings(activeWorkspaceId = ws1))
        val inWs1 = await(vm.habitsVm.habits) { it.any { h -> h.name == "Meditate" } }
        assertTrue("the habit shows in its workspace", inWs1.any { it.name == "Meditate" })

        repo.saveSettings(AppSettings(activeWorkspaceId = ws2))
        val inWs2 = await(vm.habitsVm.habits) { it.none { h -> h.name == "Meditate" } }
        assertTrue("and is filtered out of another workspace", inWs2.none { it.name == "Meditate" })
    }

    @Test fun notesVm_surfacesNotesScopedToTheActiveWorkspace() = runBlocking {
        // NotesViewModel.notes flatMapLatest over the active workspace's notes. Pin that a note captured in one
        // space stays there.
        val ws1 = repo.createWorkspace("Personal")
        val ws2 = repo.createWorkspace("Work")
        repo.upsertNote(NoteEntity(id = "", title = "Idea", body = "a note", workspaceId = ws1))

        repo.saveSettings(AppSettings(activeWorkspaceId = ws1))
        val inWs1 = await(vm.notesVm.notes) { it.any { n -> n.title == "Idea" } }
        assertTrue("the note shows in its workspace", inWs1.any { it.title == "Idea" })

        repo.saveSettings(AppSettings(activeWorkspaceId = ws2))
        val inWs2 = await(vm.notesVm.notes) { it.none { n -> n.title == "Idea" } }
        assertTrue("and does not leak into another workspace", inWs2.none { it.title == "Idea" })
    }

    @Test fun goalsRoutinesVm_persistsAndSurfacesAGoalThroughTheVm() = runBlocking {
        // GoalsRoutinesViewModel.upsertGoal → repo.replaceWorkspaceGoals → goalsState. Pin the persist+read
        // wiring end to end: a goal saved through the collaborator re-surfaces in its observable state.
        val ws = repo.createWorkspace("W")
        repo.saveSettings(AppSettings(activeWorkspaceId = ws))
        // Warm the settings flow so the collaborator's saveGoals reads the active workspace, then save.
        await(vm.settings) { it.activeWorkspaceId == ws }
        vm.goalsRoutinesVm.upsertGoal(Goal(id = "g1", name = "Read 12 books", workspaceId = ws))

        val goals = await(vm.goalsRoutinesVm.goalsState) { it.any { g -> g.id == "g1" } }
        assertTrue("the goal saved through the collaborator re-surfaces", goals.any { it.name == "Read 12 books" })
    }

    @Test fun backupSyncVm_writesAutoBackupSettings() = runBlocking {
        // BackupSyncViewModel is action-only; its contract is that a setter persists to settings. Pin that
        // enabling auto-backup through the collaborator lands in the observable settings.
        await(vm.settings) { true }   // warm the settings flow so the setter reads a real base
        vm.backupSyncVm.setAutoBackupEnabled(true)
        val s = await(vm.settings) { it.autoBackupEnabled }
        assertTrue("auto-backup enabled through the collaborator is persisted", s.autoBackupEnabled)
    }
}
