package com.cairn.reader.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.cairn.reader.MainActivity
import com.cairn.reader.util.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps read-aloud ([TtsReader]) alive when the app is backgrounded and
 * puts it on the lock screen / notification shade with transport controls, via a platform
 * [MediaSession] + a MediaStyle notification. It mirrors [TtsReader]'s state — it never owns
 * playback, so the in-app MiniPlayer and this notification always agree — and stops itself the
 * moment playback ends. Audio focus is requested so a call or another player pauses us politely.
 *
 * Uses the platform media APIs (API 21+, fine at minSdk 26) rather than androidx.media to avoid an
 * extra dependency. Every lifecycle step logs to the diagnostics file (temporary, v3.96) so an
 * on-device run confirms the service starts foreground, takes focus, and tears down cleanly.
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject lateinit var tts: TtsReader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var startedForeground = false
    /** Playback (TTS) begins a beat after the service starts — the engine warms up async — so the
     *  first state emission is still idle. We only tear down on an *inactive* state once playback has
     *  actually been active, otherwise we'd kill the service before it ever plays. */
    private var everActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.diag("PlaybackService onCreate")
        createChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        session = MediaSession(this, "cairn-listen").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { AppLog.diag("MediaSession onPlay"); if (!tts.state.value.playing) tts.togglePlayPause() }
                override fun onPause() { AppLog.diag("MediaSession onPause"); if (tts.state.value.playing) tts.togglePlayPause() }
                override fun onSkipToNext() { AppLog.diag("MediaSession onSkipToNext"); tts.skipNext() }
                override fun onSkipToPrevious() { AppLog.diag("MediaSession onSkipToPrevious"); tts.skipPrevious() }
                override fun onStop() { AppLog.diag("MediaSession onStop"); tts.stop() }
            })
            isActive = true
        }
        // Promote to the foreground immediately, so the startForegroundService() contract is
        // satisfied the instant we're created — independent of the async TTS warm-up, the first
        // state emission, and onStartCommand ordering (the race that crashed the app before).
        promoteToForeground(tts.state.value)
        // Mirror playback state into the session + notification; stop ourselves once it ends.
        tts.state.onEach { render(it) }.launchIn(scope)
        // Watchdog: if playback never actually starts (e.g. the TTS engine fails to init), don't sit
        // as a stuck foreground service — give up after a short grace window.
        scope.launch {
            delay(12_000)
            if (!everActive) {
                AppLog.diag("PlaybackService: no playback within grace window → stopping")
                stopForegroundAndSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> tts.togglePlayPause()
            ACTION_NEXT -> tts.skipNext()
            ACTION_PREV -> tts.skipPrevious()
            ACTION_STOP -> tts.stop()
        }
        // Must reach foreground promptly after startForegroundService, so post a notification now.
        promoteToForeground(tts.state.value)
        return START_NOT_STICKY
    }

    private fun render(s: TtsReader.State) {
        if (!s.active) {
            // Only tear down once playback has actually been active. On the initial idle emission
            // (during TTS warm-up) this must NOT stop the service, or we'd violate the foreground
            // contract and Android would kill the app.
            if (everActive) {
                AppLog.diag("PlaybackService: playback ended → stopping")
                abandonFocus()
                stopForegroundAndSelf()
            }
            return
        }
        everActive = true
        if (s.playing) requestFocus()
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.trackTitle.ifBlank { "Listening" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Cairn")
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, s.trackCount.toLong())
                .build(),
        )
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (s.playing) 1f else 0f,
                )
                .build(),
        )
        promoteToForeground(s)
    }

    private fun promoteToForeground(s: TtsReader.State) {
        val notification = buildNotification(s)
        if (!startedForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            startedForeground = true
            AppLog.diag("PlaybackService startForeground (type=mediaPlayback)")
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(s: TtsReader.State): Notification {
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = Notification.MediaStyle()
            .setMediaSession(session?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(s.trackTitle.ifBlank { "Listening" })
            .setContentText(if (s.trackCount > 1) "Track ${s.trackIndex + 1} of ${s.trackCount}" else "Read aloud")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(s.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(
                if (s.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .setStyle(style)
            .build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                AppLog.diag("Audio focus change: $change")
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        if (tts.state.value.playing) tts.togglePlayPause()
                    else -> Unit
                }
            }
            .build()
        focusRequest = req
        val result = am.requestAudioFocus(req)
        AppLog.diag("requestAudioFocus result=$result")
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        startedForeground = false
        stopSelf()
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Read-aloud playback controls"
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onDestroy() {
        AppLog.diag("PlaybackService onDestroy")
        abandonFocus()
        session?.isActive = false
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 42
        private const val ACTION_TOGGLE = "com.cairn.reader.action.TOGGLE"
        private const val ACTION_NEXT = "com.cairn.reader.action.NEXT"
        private const val ACTION_PREV = "com.cairn.reader.action.PREV"
        private const val ACTION_STOP = "com.cairn.reader.action.STOP"

        /** Start (or wake) the playback service. Safe to call whenever read-aloud begins from the UI. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            }.onFailure { AppLog.w("PlaybackService start failed", it) }
        }
    }
}
