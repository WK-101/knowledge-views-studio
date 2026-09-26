package com.wkhan.hexis.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wkhan.hexis.App
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
    const val ACTION_START = "com.wkhan.hexis.api.START_ACTIVITY"
    const val ACTION_STOP = "com.wkhan.hexis.api.STOP_ACTIVITY"
    const val ACTION_STOP_ALL = "com.wkhan.hexis.api.STOP_ALL"
    const val EXTRA_ACTIVITY = "activity"   // activity name (created if unknown)
    const val EXTRA_TOKEN = "token"         // SEC (R2-B/M4) — per-install shared secret; must match settings

    // Outgoing — us → other apps.
    const val EVENT_STARTED = "com.wkhan.hexis.api.EVENT_STARTED"
    const val EVENT_STOPPED = "com.wkhan.hexis.api.EVENT_STOPPED"
    const val EXTRA_NAME = "activityName"

    /** Generate a fresh per-install token to gate the automation receiver (shown to the user to paste
     *  into their automation app). URL-safe, no padding, ~128 bits. */
    fun newToken(): String {
        val raw = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }

    /** SEC (R2-B/M4) — outgoing events are pinned to the user's chosen automation package; a blank target
     *  means we emit nothing rather than world-broadcast the activity name to every registered receiver. */
    fun broadcastStarted(context: Context, activityName: String, targetPackage: String) {
        if (targetPackage.isBlank()) return
        runCatching { context.sendBroadcast(Intent(EVENT_STARTED).setPackage(targetPackage).putExtra(EXTRA_NAME, activityName)) }
    }
    fun broadcastStopped(context: Context, activityName: String, targetPackage: String) {
        if (targetPackage.isBlank()) return
        runCatching { context.sendBroadcast(Intent(EVENT_STOPPED).setPackage(targetPackage).putExtra(EXTRA_NAME, activityName)) }
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
                // SEC — this receiver is exported (automation apps need to reach it), so anyone could drive
                // or seed the tracker. Honour the opt-in: do nothing unless the user turned the automation
                // API on in Settings. Checked inside the coroutine so the DB read is off the main thread.
                val s = repo.settingsSnapshot()
                if (!s.automationApi) return@launch
                // SEC (R2-B/M4) — beyond the opt-in, require the per-install token so only the user's own
                // automation (which they pasted the token into) can drive the tracker, not any app that
                // knows the public action string. A blank stored token means the receiver stays closed.
                val token = intent.getStringExtra(TimeIntentApi.EXTRA_TOKEN).orEmpty()
                // Constant-time compare (MessageDigest.isEqual) so a normal String `!=`'s early-exit can't
                // leak the per-install token one byte at a time via response timing. A blank stored token
                // keeps the receiver closed.
                if (s.automationToken.isBlank() || !java.security.MessageDigest.isEqual(
                        token.toByteArray(Charsets.UTF_8), s.automationToken.toByteArray(Charsets.UTF_8),
                    )
                ) return@launch
                val targetPkg = s.automationTargetPackage
                when (intent.action) {
                    TimeIntentApi.ACTION_START -> {
                        val name = intent.getStringExtra(TimeIntentApi.EXTRA_ACTIVITY)?.trim().orEmpty()
                        if (name.isNotEmpty()) {
                            val existing = repo.getTimeActivitiesOnce().firstOrNull { it.name.equals(name, true) && !it.archived }
                            val id = existing?.id ?: repo.createTimeActivity(name, null, null)
                            val multi = repo.settingsSnapshot().multiTimer
                            repo.startTimeTracking(id, stopFirst = !multi)
                            AutomationRunner.onStart(context, repo, id)
                            TimeIntentApi.broadcastStarted(context, name, targetPkg)
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
                            TimeIntentApi.broadcastStopped(context, acts.firstOrNull { it.id == target.activityId }?.name ?: "", targetPkg)
                        }
                    }
                    TimeIntentApi.ACTION_STOP_ALL -> {
                        repo.runningTimeEntries().forEach { repo.stopTimeEntry(it.id) }
                        TimeIntentApi.broadcastStopped(context, "", targetPkg)
                    }
                }
                com.wkhan.hexis.widget.TimeWidget.refresh(context)
            } finally { pending.finish() }
        }
    }
}
