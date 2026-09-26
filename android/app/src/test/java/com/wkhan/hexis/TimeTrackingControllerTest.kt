package com.wkhan.hexis

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.time.TimeTrackingController
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
 * W1 / Phase-3 Stage 2 — [TimeTrackingController] is the time-tracking slice split out of the god-VM
 * (R84/R88 pattern), now SELF-CONTAINED: it reads the current settings and the active-workspace activities
 * / entries straight from the repo, so the ViewModel no longer threads its state in. These tests drive the
 * real controller over an isolated in-memory repository and pin its imperative behaviour (start / stop /
 * pause / resume, pin, reassign, and the audit fix where deleting an activity clears a paused track on it).
 *
 * No snapshot plumbing anymore: the controller reads the repo directly, so the test just seeds the repo and
 * calls the controller. Default settings keep multiTimer off (start stops the previous timer) and
 * automationApi off (no cross-app broadcast).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TimeTrackingControllerTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository
    private lateinit var controller: TimeTrackingController

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
        controller = TimeTrackingController(ApplicationProvider.getApplicationContext(), repo)
    }

    @After fun teardown() = db.close()

    @Test fun startTimeTracking_createsARunningEntryForThatActivity() = runBlocking {
        val actId = repo.createTimeActivity("Deep work", null, null)
        controller.startTimeTracking(actId)
        val running = repo.runningTimeEntry()
        assertNotNull("a timer is now running", running)
        assertEquals("it is the activity we started", actId, running!!.activityId)
    }

    @Test fun stopTimeTracking_stopsTheRunningEntry() = runBlocking {
        val actId = repo.createTimeActivity("Reading", null, null)
        controller.startTimeTracking(actId)
        assertNotNull(repo.runningTimeEntry())
        controller.stopTimeTracking()
        assertNull("no timer running after stop", repo.runningTimeEntry())
    }

    @Test fun pauseThenResume_recreatesTheSameTrack() = runBlocking {
        val actId = repo.createTimeActivity("Writing", null, null)
        controller.startTimeTracking(actId)

        controller.pauseTracking()
        assertNull("pause finalizes the running interval", repo.runningTimeEntry())
        assertEquals("pause remembers what to resume", actId, controller.pausedTrack.value?.first)

        controller.resumeTracking()
        assertNotNull("resume starts the timer again", repo.runningTimeEntry())
        assertEquals(actId, repo.runningTimeEntry()!!.activityId)
        assertNull("paused memory is cleared after resume", controller.pausedTrack.value)
    }

    @Test fun deletingAnActivity_clearsAPausedTrackOnIt() = runBlocking {
        // Audit #7 — otherwise Resume would re-create an entry on a now-deleted activity (an orphan).
        val actId = repo.createTimeActivity("Chores", null, null)
        controller.startTimeTracking(actId)
        controller.pauseTracking()
        assertEquals(actId, controller.pausedTrack.value?.first)

        controller.deleteTimeActivity(actId)
        assertNull("the paused track on the deleted activity is gone", controller.pausedTrack.value)
    }

    @Test fun toggleActivityPin_addsThenRemovesFromSettings() = runBlocking {
        val actId = repo.createTimeActivity("Email", null, null)
        controller.toggleActivityPin(actId)
        assertTrue("pinned after first toggle", repo.settingsSnapshot().pinnedActivities.contains(actId))
        controller.toggleActivityPin(actId)
        assertFalse("unpinned after second toggle", repo.settingsSnapshot().pinnedActivities.contains(actId))
    }

    @Test fun reassignTimeEntry_movesTheEntryToAnotherActivity() = runBlocking {
        val a = repo.createTimeActivity("A", null, null)
        val b = repo.createTimeActivity("B", null, null)
        controller.startTimeTracking(a)
        val entryId = repo.runningTimeEntry()!!.id

        controller.reassignTimeEntry(entryId, b)
        assertEquals("entry now belongs to B", b, repo.timeEntriesOnce().first { it.id == entryId }.activityId)
    }
}
