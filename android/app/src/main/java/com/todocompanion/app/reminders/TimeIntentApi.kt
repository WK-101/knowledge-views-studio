package com.todocompanion.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todocompanion.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tier V8 — a fully on-device automation surface for the time tracker. Local broadcast IPC only, so it
 * needs no INTERNET permission and stays inside the 0-network posture. Other apps (Tasker, MacroDroid,
 * Automate) can drive tracking IN via [TimeIntentReceiver], and react to tracking OUT via the events
 * [TimeIntentApi] emits on every start/stop.
 */
object TimeIntentApi {
    // Incoming — other apps → us.
    const val ACTION_START = "com.todocompanion.app.api.START_ACTIVITY"
    const val ACTION_STOP = "com.todocompanion.app.api.STOP_ACTIVITY"
    const val ACTION_STOP_ALL = "com.todocompanion.app.api.STOP_ALL"
    const val EXTRA_ACTIVITY = "activity"   // activity name (created if unknown)

    // Outgoing — us → other apps.
    const val EVENT_STARTED = "com.todocompanion.app.api.EVENT_STARTED"
    const val EVENT_STOPPED = "com.todocompanion.app.api.EVENT_STOPPED"
    const val EXTRA_NAME = "activityName"

    fun broadcastStarted(context: Context, activityName: String) {
        runCatching { context.sendBroadcast(Intent(EVENT_STARTED).setPackage(null).putExtra(EXTRA_NAME, activityName)) }
    }
    fun broadcastStopped(context: Context, activityName: String) {
        runCatching { context.sendBroadcast(Intent(EVENT_STOPPED).setPackage(null).putExtra(EXTRA_NAME, activityName)) }
    }
}

/** Receives START/STOP/STOP_ALL broadcasts from automation apps and drives the tracker accordingly. */
class TimeIntentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = app.repository
                when (intent.action) {
                    TimeIntentApi.ACTION_START -> {
                        val name = intent.getStringExtra(TimeIntentApi.EXTRA_ACTIVITY)?.trim().orEmpty()
                        if (name.isNotEmpty()) {
                            val existing = repo.getTimeActivitiesOnce().firstOrNull { it.name.equals(name, true) && !it.archived }
                            val id = existing?.id ?: repo.createTimeActivity(name, null, null)
                            val multi = repo.settingsSnapshot().multiTimer
                            repo.startTimeTracking(id, stopFirst = !multi)
                            AutomationRunner.onStart(context, repo, id)
                            TimeIntentApi.broadcastStarted(context, name)
                        }
                    }
                    TimeIntentApi.ACTION_STOP -> {
                        val name = intent.getStringExtra(TimeIntentApi.EXTRA_ACTIVITY)?.trim()
                        val running = repo.runningTimeEntries()
                        val acts = repo.getTimeActivitiesOnce()
                        val target = if (name.isNullOrBlank()) running.firstOrNull()
                            else running.firstOrNull { e -> acts.firstOrNull { it.id == e.activityId }?.name.equals(name, true) }
                        if (target != null) {
                            repo.stopTimeEntry(target.id)
                            TimeIntentApi.broadcastStopped(context, acts.firstOrNull { it.id == target.activityId }?.name ?: "")
                        }
                    }
                    TimeIntentApi.ACTION_STOP_ALL -> {
                        repo.runningTimeEntries().forEach { repo.stopTimeEntry(it.id) }
                        TimeIntentApi.broadcastStopped(context, "")
                    }
                }
                com.todocompanion.app.widget.TimeWidget.refresh(context)
            } finally { pending.finish() }
        }
    }
}
