package com.todocompanion.app.reminders

import android.app.NotificationManager
import android.content.Context

/**
 * R59 (Wave 3) — focus-block DND enforcement. Silences notifications with the platform interruption
 * filter while a Focus session runs, and restores normal alerts when it ends. Requires the user to grant
 * Do-Not-Disturb access (ACCESS_NOTIFICATION_POLICY) in system settings; every call is a safe no-op until
 * they do. Fully local — no network.
 */
object FocusDnd {
    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Enter priority-only DND (alarms + priority senders still break through). */
    fun enter(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.isNotificationPolicyAccessGranted)
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
    }

    /** Restore normal notifications. */
    fun exit(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.isNotificationPolicyAccessGranted)
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
    }
}
