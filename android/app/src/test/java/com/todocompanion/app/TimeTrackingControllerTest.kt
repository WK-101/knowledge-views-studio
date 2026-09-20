package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.time.TimeTrackingController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W1 — the app already decomposes the time-tracking slice out of the god-VM into
 * [TimeTrackingController] (the R84/R88 controller pattern), but that extracted seam had **no tests**.
 * These make it a *proven* seam: they drive the real controller over an isolated in-memory repository
 * and pin its imperative behaviour (start / stop / pause / resume, pin, reassign, and the audit fix where
 * deleting an activity clears a paused track on it) so the W1 decomposition can proceed safely.
 *
 * The controller takes its ambient reads as lambdas; the test backs them with snapshots it refreshes from
 * the repo, so the lambdas stay synchronous exactly as the ViewModel supplies them. Default [AppSettings]
 * keeps multiTimer off (start stops the previous timer) and automationApi off (no cross-app broadcast).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TimeTrackingControllerTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository
    private lateinit var controller: TimeTrackingController

    private var settingsSnap = AppSettings()
    private var actsSnap: List<TimeActivityEntity> = emptyList()
    private var entsSnap: List<TimeEntryEntity> = emptyList()
    private var habitRefreshes = 0

    /** Re-read the repo into the snapshots the controller's ambient-read lambdas return. */
    private suspend fun refresh() {
        settingsSnap = repo.settingsSnapshot()
        actsSnap = repo.allTimeActivities.first()
        entsSnap = repo.timeEntriesOnce()
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
        controller = TimeTrackingController(
            context = ApplicationProvider.getApplicationContext(),
            repo = repo,
            settings = { settingsSnap },
            activities = { actsSnap },
            entries = { entsSnap },
            onRefreshHabits = { habitRefreshes++ },
        )
    }

    @After fun teardown() = db.close()

    @Test fun startTimeTracking_createsARunningEntryForThatActivity() = runBlocking {
        val actId = repo.createTimeActivity("Deep work", null, null)
        refresh()
        controller.startTimeTracking(actId)
        val running = repo.runningTimeEntry()
        assertNotNull("a timer is now running", running)
        assertEquals("it is the activity we started", actId, running!!.activityId)
    }

    @Test fun stopTimeTracking_stopsTheEntry_andRefreshesLinkedHabits() = runBlocking {
        val actId = repo.createTimeActivity("Reading", null, null)
        refresh(); controller.startTimeTracking(actId)
        assertNotNull(repo.runningTimeEntry())
        val before = habitRefreshes
        refresh(); controller.stopTimeTracking()
        assertNull("no timer running after stop", repo.runningTimeEntry())
        assertTrue("linked-habit refresh fired on stop", habitRefreshes > before)
    }

    @Test fun pauseThenResume_recreatesTheSameTrack() = runBlocking {
        val actId = repo.createTimeActivity("Writing", null, null)
        refresh(); controller.startTimeTracking(actId)

        refresh(); controller.pauseTracking()
        assertNull("pause finalizes the running interval", repo.runningTimeEntry())
        assertEquals("pause remembers what to resume", actId, controller.pausedTrack.value?.first)

        refresh(); controller.resumeTracking()
        assertNotNull("resume starts the timer again", repo.runningTimeEntry())
        assertEquals(actId, repo.runningTimeEntry()!!.activityId)
        assertNull("paused memory is cleared after resume", controller.pausedTrack.value)
    }

    @Test fun deletingAnActivity_clearsAPausedTrackOnIt() = runBlocking {
        // Audit #7 — otherwise Resume would re-create an entry on a now-deleted activity (an orphan).
        val actId = repo.createTimeActivity("Chores", null, null)
        refresh(); controller.startTimeTracking(actId)
        refresh(); controller.pauseTracking()
        assertEquals(actId, controller.pausedTrack.value?.first)

        refresh(); controller.deleteTimeActivity(actId)
        assertNull("the paused track on the deleted activity is gone", controller.pausedTrack.value)
    }

    @Test fun toggleActivityPin_addsThenRemovesFromSettings() = runBlocking {
        val actId = repo.createTimeActivity("Email", null, null)
        refresh()
        controller.toggleActivityPin(actId)
        assertTrue("pinned after first toggle", repo.settingsSnapshot().pinnedActivities.contains(actId))
        refresh() // pick up the new pinned set so the second toggle sees it
        controller.toggleActivityPin(actId)
        assertFalse("unpinned after second toggle", repo.settingsSnapshot().pinnedActivities.contains(actId))
    }

    @Test fun reassignTimeEntry_movesTheEntryToAnotherActivity() = runBlocking {
        val a = repo.createTimeActivity("A", null, null)
        val b = repo.createTimeActivity("B", null, null)
        refresh(); controller.startTimeTracking(a)
        val entryId = repo.runningTimeEntry()!!.id

        refresh(); controller.reassignTimeEntry(entryId, b)
        assertEquals("entry now belongs to B", b, repo.timeEntriesOnce().first { it.id == entryId }.activityId)
    }
}
