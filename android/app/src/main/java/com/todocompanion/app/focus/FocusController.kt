package com.todocompanion.app.focus

import android.content.Context
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.reminders.AlarmScheduler
import com.todocompanion.app.reminders.AutomationRunner
import com.todocompanion.app.reminders.FocusDnd
import com.todocompanion.app.util.Sounds

/**
 * R84 — the live control for a focus session, split out of AppViewModel. Focus is a MODE of time
 * tracking: Start opens a running interval tagged `kind="focus"` on the one timeline (so it is tracked
 * time the instant it begins), Stop finalizes it, and the focus-done alarm / DND / start & completion
 * cues hang off the same two calls. The pure statistics live in [com.todocompanion.app.domain.FocusStats];
 * this holds the imperative side (repo writes, alarms, DND, widget refresh, sounds).
 *
 * It owns no state: the ViewModel keeps `focusTargetMin` and the running-interval StateFlow and passes
 * in the settings snapshot, the activity resolver, and the widget-refresh callbacks. Behaviour is
 * identical to the previous in-ViewModel implementation.
 */
class FocusController(
    private val context: Context,
    private val repo: AppRepository,
    private val settings: () -> AppSettings,
    /** Resolve the activity to track: a task's / habit's linked activity, else null → generic Focus. */
    private val resolveActivity: (taskId: String?, habitId: String?) -> String?,
    private val setTargetMin: (Int) -> Unit,
    private val onRefreshTime: () -> Unit,
    private val onRefreshHabits: () -> Unit,
) {
    /**
     * Start a focus session. [remainingSec] lets Resume schedule the completion chime for exactly the
     * time still left; when [targetMin] is 0 it's an open-ended stopwatch (no chime scheduled).
     */
    suspend fun start(activityId: String?, targetMin: Int, remainingSec: Int, taskId: String?, habitId: String?) {
        setTargetMin(targetMin.coerceAtLeast(0))
        // Optional start cue.
        Sounds.play(context, settings().focusStartSound)
        val actId = activityId ?: resolveActivity(taskId, habitId) ?: repo.ensureFocusActivity()
        repo.startTimeTracking(actId, taskId, habitId, stopFirst = !settings().multiTimer, kind = "focus")
        // Focus-block DND: silence notifications for the duration if the user opted in.
        if (settings().focusDnd) FocusDnd.enter(context)
        AutomationRunner.onStart(context, repo, actId)
        if (targetMin > 0 && remainingSec > 0)
            AlarmScheduler.scheduleFocusDone(context, System.currentTimeMillis() + remainingSec * 1000L)
        onRefreshTime()
    }

    /** Stop the running focus interval (finalize + credit any linked habit) and cancel its chime. */
    suspend fun stop(runningFocusId: String?) {
        if (runningFocusId != null) { repo.stopTimeEntry(runningFocusId); onRefreshHabits(); onRefreshTime() }
        AlarmScheduler.cancelFocusDone(context)
        // Lift focus-block DND (no-op if it was never engaged / access not granted).
        FocusDnd.exit(context)
    }

    /** Play the chosen focus/timer completion cue in-app. */
    fun playDoneSound() = Sounds.play(context, settings().focusDoneSound)
}
