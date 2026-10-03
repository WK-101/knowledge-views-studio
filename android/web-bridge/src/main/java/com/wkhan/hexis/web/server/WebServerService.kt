package com.wkhan.hexis.web.server

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

import com.wkhan.hexis.web.R
import com.wkhan.hexis.web.bridge.DataBridgeClient
import com.wkhan.hexis.web.net.LanAddress
import com.wkhan.hexis.web.pairing.PairingStore

/**
 * Hosts the local [WebServer] as a user-visible `dataSync` foreground service with a persistent "serving
 * at …" notification (and a Stop action). Tries a short list of ports. Publishes the live URL so the
 * control screen can render the pairing QR.
 */
class WebServerService : Service() {

    private var server: WebServer? = null
    private var dataClient: DataBridgeClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        val aead = PairingStore(this).aeadKey()
        val client = DataBridgeClient(applicationContext).also { dataClient = it }
        runCatching { client.connect() } // best-effort; queries report "not connected" until granted

        val port = PORTS.firstNotNullOfOrNull { candidate ->
            runCatching { WebServer(applicationContext, candidate, aead, client).also { it.start() } to candidate }
                .getOrNull()
        }
        if (port == null) {
            Log.w(TAG, "could not bind any port")
            stopSelf()
            return START_NOT_STICKY
        }
        server = port.first
        runningPort = port.second
        running = true

        ensureChannel()
        val url = urlFor(port.second)
        startForegroundCompat(buildNotification(url))
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        runCatching { server?.stop() }
        runCatching { dataClient?.close() }
        server = null
        dataClient = null
        running = false
        runningPort = 0
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground refused", it) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(url: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, WebServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.server_running_title))
            .setContentText(url)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, getString(R.string.server_stop), stopPi).build())
            .build()
    }

    companion object {
        private const val TAG = "WebServerService"
        private const val CHANNEL = "web_server"
        private const val NOTIF_ID = 3100
        const val ACTION_STOP = "com.wkhan.hexis.web.STOP"
        private val PORTS = listOf(8787, 8788, 8089, 8080)

        @Volatile var running = false
            private set

        @Volatile var runningPort = 0
            private set

        fun urlFor(port: Int): String {
            val ip = LanAddress.ipv4() ?: "127.0.0.1"
            return "http://$ip:$port/"
        }

        /** The live base URL while running, else null. */
        fun currentUrl(): String? = if (running && runningPort > 0) urlFor(runningPort) else null

        fun start(context: Context) {
            val intent = Intent(context, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WebServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
