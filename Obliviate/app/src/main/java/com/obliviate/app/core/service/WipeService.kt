package com.obliviate.app.core.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.obliviate.app.MainActivity
import com.obliviate.app.R
import com.obliviate.app.core.formatBytes
import com.obliviate.app.core.formatSpeed
import com.obliviate.app.core.wipe.FreeSpaceWiper
import com.obliviate.app.core.wipe.WipeConfig
import com.obliviate.app.core.wipe.WipeMethod
import com.obliviate.app.core.wipe.WipePhase
import com.obliviate.app.core.wipe.WipeProgress
import com.obliviate.app.core.wipe.WipeTarget
import com.obliviate.app.core.wipe.WipeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

class WipeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var lastProgress: WipeProgress? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel(CancellationException("Cancelled by user"))
                return START_NOT_STICKY
            }
            else -> startWipe(intent)
        }
        return START_NOT_STICKY
    }

    private fun startWipe(intent: Intent?) {
        if (job?.isActive == true) return
        if (intent == null) {
            stopSelf(); return
        }

        val target = WipeTarget.valueOf(intent.getStringExtra(EXTRA_TARGET) ?: WipeTarget.INTERNAL.name)
        val method = WipeMethod.valueOf(intent.getStringExtra(EXTRA_METHOD) ?: WipeMethod.RANDOM.name)
        val keep = intent.getLongExtra(EXTRA_KEEP, WipeConfig.DEFAULT_KEEP_FREE_BYTES)
        val config = WipeConfig(target, method, keep)

        val initial = WipeProgress(WipePhase.PREPARING, 1, method.passes, 0, 1, 0)
        lastProgress = initial
        _state.value = WipeUiState.Running(initial)

        startForegroundCompat(initial)
        acquireWakeLock()

        job = scope.launch {
            try {
                val result = FreeSpaceWiper.wipe(applicationContext, config) { p ->
                    lastProgress = p
                    _state.value = WipeUiState.Running(p)
                    updateNotification(p)
                }
                _state.value = WipeUiState.Done(
                    result.bytesOverwritten, result.passes, result.elapsedMs, target
                )
            } catch (c: CancellationException) {
                _state.value = WipeUiState.Cancelled(lastProgress?.bytesWritten ?: 0)
            } catch (e: Exception) {
                _state.value = WipeUiState.Failed(e.message ?: "Unknown error")
            } finally {
                releaseWakeLock()
                ServiceCompat.stopForeground(this@WipeService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ---- Notifications -------------------------------------------------------

    private fun startForegroundCompat(progress: WipeProgress) {
        val notification = buildNotification(progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)
        }
    }

    private fun updateNotification(progress: WipeProgress) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(progress))
    }

    private fun buildNotification(progress: WipeProgress) = run {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WipeService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val percent = (progress.fraction * 100).toInt()
        val indeterminate = progress.phase == WipePhase.PREPARING || progress.phase == WipePhase.DELETING
        val text = when (progress.phase) {
            WipePhase.PREPARING -> "Preparing…"
            WipePhase.FILLING ->
                "Pass ${progress.pass}/${progress.totalPasses} · " +
                    "${formatBytes(progress.bytesWritten)} · ${formatSpeed(progress.speedBytesPerSec)}"
            WipePhase.DELETING -> "Releasing space…"
            WipePhase.DONE -> "Finishing…"
        }

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wipe_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_wipe)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setContentIntent(openIntent)
            .addAction(0, "Cancel", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ---- Wake lock -----------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obliviate:wipe").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L) // 3h safety cap
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onTimeout(startId: Int) {
        // Foreground-service runtime limit reached (Android 14+): stop the job gracefully.
        job?.cancel(CancellationException("Foreground service timeout"))
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "wipe_progress"
        private const val NOTIF_ID = 1001

        private const val ACTION_START = "com.obliviate.app.action.START_WIPE"
        const val ACTION_CANCEL = "com.obliviate.app.action.CANCEL_WIPE"

        private const val EXTRA_TARGET = "extra_target"
        private const val EXTRA_METHOD = "extra_method"
        private const val EXTRA_KEEP = "extra_keep"

        private val _state = MutableStateFlow<WipeUiState>(WipeUiState.Idle)
        val state: StateFlow<WipeUiState> = _state.asStateFlow()

        fun reset() {
            if (_state.value !is WipeUiState.Running) _state.value = WipeUiState.Idle
        }

        fun start(context: Context, config: WipeConfig) {
            val intent = Intent(context, WipeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TARGET, config.target.name)
                putExtra(EXTRA_METHOD, config.method.name)
                putExtra(EXTRA_KEEP, config.keepFreeBytes)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, WipeService::class.java).apply { action = ACTION_CANCEL }
            context.startService(intent)
        }
    }
}
