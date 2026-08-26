package com.todocompanion.app.reminders

import android.content.Context
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.domain.AutomationRule
import com.todocompanion.app.domain.AutomationRules

/**
 * Tier U12 — evaluates the on-start automation rules when a timer begins, from anywhere (in-app, the
 * widget, the QS tile, an NFC tag). Fully offline: an action either posts a local notification or
 * chains another activity to start (only when multi-timer is on, so we never silently stop the timer
 * the user just began).
 */
object AutomationRunner {
    suspend fun onStart(context: Context, repo: AppRepository, activityId: String) {
        val s = repo.settingsSnapshot()
        val matched = AutomationRules.onStart(AutomationRules.parse(s.automationRulesJson), activityId)
        for (r in matched) when (r.actionType) {
            AutomationRule.ACTION_NOTIFY ->
                if (r.notifyText.isNotBlank()) Notifications.simple(context, "auto:${r.id}", "Automation", r.notifyText)
            AutomationRule.ACTION_START ->
                if (s.multiTimer && r.startActivityId.isNotBlank() && r.startActivityId != activityId)
                    repo.startTimeTracking(r.startActivityId, stopFirst = false)
        }
    }
}
