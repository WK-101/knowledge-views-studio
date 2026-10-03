package com.wkhan.hexis.voice.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

import com.wkhan.hexis.voice.R

/**
 * Microphone foreground service for push-to-talk capture. The engine ([com.wkhan.hexis.voice.engine.
 * WhisperSttEngine]) starts it (user-initiated, from the core's visible push-to-talk UI) for the
 * duration of a recording so the mic runs Android-14-compliantly, then stops it. It carries an ongoing
 * notification with a Stop action so the user can end capture from the shade / lock screen without
 * returning to the app.
 *
 * Compliance note: the start must be user-initiated from a visible UI; a `microphone` FGS cannot be
 * started from the background on Android 14+. If the OS refuses the start we swallow it and rely on the
 * microphone capability the core confers via BIND_INCLUDE_CAPABILITIES while it is in the foreground.
 */
class VoiceCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { stopRequest?.invoke() }
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "mic foreground service not allowed; relying on bound capability", t)
            false
        }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.capture_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setSmallIcon(R.drawable.ic_voice_mic)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET) // nothing sensitive, and keeps it off the lock screen face
            .addAction(Notification.Action.Builder(null, getString(R.string.capture_stop), stopIntent).build())
            .build()
    }

    companion object {
        private const val TAG = "VoiceCaptureService"
        private const val CHANNEL_ID = "voice_capture"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.wkhan.hexis.voice.capture.STOP"

        /** Set by the engine while a capture runs, so the notification's Stop action can end it. */
        @Volatile
        var stopRequest: (() -> Unit)? = null
    }
}
