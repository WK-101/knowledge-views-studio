package com.todocompanion.app.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R104 — the one broadcast target behind every task-list widget's item taps. A RemoteViews
 * collection can carry only a single PendingIntent template, so the per-row fill-in intent decides
 * what a tap does:
 *   • EXTRA_COMPLETE_ID present → tick that task off *in place* (no app launch), then refresh widgets
 *   • otherwise MainActivity.EXTRA_ACTION → open the app at that destination (a task, the calendar, …)
 *
 * Completing goes through the same repository path as the in-app checkbox (recurrence roll-over,
 * deferral-chain reset, everything), so a home-screen tick behaves exactly like ticking in the app.
 * Entirely offline — reads/writes the local Room DB only.
 */
class TaskWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val completeId = intent.getStringExtra(EXTRA_COMPLETE_ID)
        if (!completeId.isNullOrEmpty()) {
            val app = context.applicationContext as? App ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.repository.setCompletedById(completeId, true)
                    // Nudge the task-driven widgets right away (the DB observer also fires, debounced).
                    AgendaWidget.refresh(context)
                    DoNextWidget.refresh(context)
                    TodayWidget.refresh(context)
                    Next7Widget.refresh(context)
                    RecordWidget.refresh(context)
                    StatsWidget.refresh(context)
                } finally { pending.finish() }
            }
            return
        }

        // Open path: hand the action to MainActivity. A widget tap is a user action, so this
        // activity start from the tapped PendingIntent is permitted.
        val action = intent.getStringExtra(MainActivity.EXTRA_ACTION) ?: return
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
        })
    }

    companion object {
        const val ACTION = "com.todocompanion.app.action.WIDGET_TASK"
        const val EXTRA_COMPLETE_ID = "widget_complete_id"

        /** The single template a task-list collection sets; each row supplies a fill-in intent. */
        fun template(context: Context, reqCode: Int): PendingIntent {
            val i = Intent(context, TaskWidgetReceiver::class.java).setAction(ACTION)
            return PendingIntent.getBroadcast(
                context, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }

        /** Fill-in intent that ticks a task off. */
        fun completeFill(taskId: String): Intent = Intent().putExtra(EXTRA_COMPLETE_ID, taskId)

        /** Fill-in intent that opens the app at [action] (e.g. "open_task:<id>", "open_calendar"). */
        fun openFill(action: String): Intent = Intent().putExtra(MainActivity.EXTRA_ACTION, action)
    }
}
