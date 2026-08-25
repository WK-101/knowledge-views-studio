package com.todocompanion.app.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.TaskEntity

/**
 * On-device geofence-style reminders using the platform [LocationManager.addProximityAlert].
 * No Google Play Services, no network — the OS watches GPS locally and fires a broadcast
 * PendingIntent when the device enters/leaves the radius. Location never leaves the device.
 */
object LocationReminders {

    const val ACTION_PROXIMITY = "com.todocompanion.app.action.PROXIMITY"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context, reminder: ReminderEntity, task: TaskEntity): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_PROXIMITY)
            .putExtra(AlarmScheduler.EXTRA_TASK_ID, task.id)
            .putExtra(AlarmScheduler.EXTRA_TITLE, task.title)
            .putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminder.id)
            .putExtra("onEnter", reminder.onEnter)
            .putExtra("place", reminder.placeName ?: "")
        return PendingIntent.getBroadcast(
            context, ("prox:" + reminder.id).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    fun register(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        if (reminder.type != "location") return
        if (task.completed || task.trashed || task.abandoned) return
        val lat = reminder.latitude ?: return
        val lng = reminder.longitude ?: return
        val radius = (reminder.radiusM ?: 150.0).toFloat()
        if (!hasPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            // expiration -1 = never expire; the alert lives until we remove it.
            lm.addProximityAlert(lat, lng, radius, -1L, pendingIntent(context, reminder, task))
        }
    }

    fun unregister(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.removeProximityAlert(pendingIntent(context, reminder, task)) }
    }

    // ---------- context geofences (E3: auto-surface a context on arrival) ----------
    private fun contextPendingIntent(context: Context, ctxId: String, ctxName: String): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_PROXIMITY)
            .putExtra("contextId", ctxId)
            .putExtra("contextName", ctxName)
            .putExtra("onEnter", true)
        return PendingIntent.getBroadcast(
            context, ("ctxprox:$ctxId").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    fun registerContext(context: Context, ctx: com.todocompanion.app.data.entity.ContextEntity) {
        val lat = ctx.latitude ?: return
        val lng = ctx.longitude ?: return
        val radius = (ctx.radiusM ?: 150.0).toFloat()
        if (!hasPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.addProximityAlert(lat, lng, radius, -1L, contextPendingIntent(context, ctx.id, ctx.name)) }
    }

    fun unregisterContext(context: Context, ctx: com.todocompanion.app.data.entity.ContextEntity) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { lm.removeProximityAlert(contextPendingIntent(context, ctx.id, ctx.name)) }
    }

    /** Re-arm every location reminder and context geofence (after boot, permission grant, app start). */
    suspend fun registerAll(context: Context, repo: AppRepository) {
        if (!hasPermission(context)) return
        repo.allRemindersOnce().filter { it.type == "location" }.forEach { r ->
            val task = repo.getTask(r.taskId) ?: return@forEach
            register(context, r, task)
        }
        repo.getContextsOnce().filter { it.latitude != null && it.longitude != null }.forEach { registerContext(context, it) }
    }
}
