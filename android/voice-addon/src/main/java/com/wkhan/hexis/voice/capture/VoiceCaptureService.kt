package com.wkhan.hexis.voice.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

import com.wkhan.hexis.voice.R

/**
 * Microphone foreground service scaffold for push-to-talk capture. The echo dev engine needs no
 * microphone, so this is not started yet — it establishes the Android 14-compliant FGS lifecycle
 * (immediate startForeground with a mic-type notification) that the real sherpa engine will use.
 *
 * Compliance note: the start must be user-initiated from the core's VISIBLE push-to-talk UI; a
 * `microphone` FGS cannot be started from the background on Android 14+.
 */
class VoiceCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // Best-effort: promote to a microphone foreground service. If the OS refuses the start (Android
    // 14+ forbids starting a mic FGS while ineligible, throwing from startForeground), we must NOT
    // crash — swallow it and stop. Capture still proceeds under the microphone capability the core
    // confers via BIND_INCLUDE_CAPABILITIES while it is in the foreground.
    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private companion object {
        const val TAG = "VoiceCaptureService"
        const val CHANNEL_ID = "voice_capture"
        const val NOTIFICATION_ID = 1001
    }
}
