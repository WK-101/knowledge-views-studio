package app.parley.situations

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.R
import app.parley.common.PolicyClock
import app.parley.common.calls.DriveProfile
import app.parley.common.calls.AssistedDial
import app.parley.common.situations.DeviceTrigger
import app.parley.common.situations.Situation
import app.parley.common.situations.SituationKind
import app.parley.common.situations.SituationSignals
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.DataContainer
import app.parley.telecom.CarAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * What switches Situations on and off by themselves, with no new permission: the connected audio outputs (a car's
 * hands-free and media links, Bluetooth headphones and speakers, by the product name Android gives without "Nearby
 * devices"), the drive profile's marked cars, car mode (Android Auto, a car dock), a SIM on another country's
 * network (what Assisted dialling reads; a flight landing changes the time zone, which looks again) and the time, or
 * the end chosen when one was switched on by hand. Parley looks again
 * when it starts, while it runs and an audio device comes or goes or car mode changes, before each incoming call is
 * screened (see [app.parley.data.CallScreener.beforeScreen]) and before an outgoing call picks its SIM, from the Quick
 * Settings tile, when the clock is set or the time zone changes ([SituationClockReceiver]: a flight lands, the job's
 * delay was counted in the old zone), and at the next window edge (one inexact WorkManager job, which survives a
 * reboot).
 */
object SituationTriggers {
    private const val WORK = "situation_window"

    /** Whether the window job may be queued (so a start with no window needs to cancel it). */
    private const val PREFS = "situation_triggers"
    private const val K_QUEUED = "queued"

    /** A device announces itself in steps (media first, then calls): look once it has settled. */
    private const val SETTLE_MS = 2_500L

    /** The job runs a little after the edge, so the window has surely begun or ended. */
    private const val AFTER_EDGE_MS = 5_000L

    /** True while [SituationWorker] runs: it schedules the next run itself, so a change it makes doesn't replace it. */
    @Volatile private var workerRunning = false

    private var pendingLook: Job? = null

    /**
     * Gives the container this phone's signals: at once, so a call screened right after start sees the car (nothing
     * is read from disk here).
     */
    fun provide(context: Context, c: DataContainer) {
        val app = context.applicationContext
        c.situationSignals = { signals(app, c) }
    }

    /** Wires the listeners and looks once. Called at app start, off the main thread (the list is read from disk). */
    fun install(context: Context, c: DataContainer) {
        val app = context.applicationContext
        c.situations.onChange = {
            refreshTile(app)
            SituationNotice.update(app, c.situations)
            if (!workerRunning) schedule(app, c)
        }
        // While Parley runs: audio devices coming and going, and car mode.
        runCatching {
            app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = lookSoon(c)
                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = lookSoon(c)
                },
                Handler(Looper.getMainLooper()),
            )
        }
        runCatching {
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = lookSoon(c)
                },
                IntentFilter().apply {
                    addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                    addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        c.scope.launch {
            suspendRunCatching { c.situations.reconcile() }
            // After a reboot the notice is gone while the Situation is still on.
            SituationNotice.update(app, c.situations)
            schedule(app, c)
        }
    }

    /** Looks at the triggers once things have settled (several callbacks in a row make one look). */
    private fun lookSoon(c: DataContainer) {
        if (!c.situations.watching()) return
        pendingLook?.cancel()
        pendingLook = c.scope.launch {
            delay(SETTLE_MS)
            // A look of its own: a later callback cancels the wait, never a switch under way.
            c.scope.launch { suspendRunCatching { c.situations.reconcile() } }
        }
    }

    /** What the triggers see now. Binder calls only (no disk): fine on the call path. */
    fun signals(context: Context, c: DataContainer): SituationSignals {
        val connected = CarAudio.connected(context)
        val carMode = runCatching {
            context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        }.getOrDefault(false)
        val car = carMode || DriveProfile.connectedCar(c.driveProfile.config.value, connected) != null
        return SituationSignals(
            clock = PolicyClock.of(System.currentTimeMillis()),
            car = car,
            audioNames = connected.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.distinct(),
            bluetoothAudio = connected.isNotEmpty(),
            roaming = c.situations.list.value.any { it.device == DeviceTrigger.ROAMING } && abroad(c),
        )
    }

    /**
     * A SIM is on another country's network (what Assisted dialling reads; national roaming isn't "abroad"). Read only
     * when a Situation turns on abroad: binder calls only, no disk.
     */
    private fun abroad(c: DataContainer): Boolean =
        runCatching { c.roaming.simStates(c.sims.accounts()).any(AssistedDial::abroad) }.getOrDefault(false)

    /** The one job for the next window edge; none when no Situation has a window. */
    fun schedule(context: Context, c: DataContainer, fromWorker: Boolean = false) {
        val now = System.currentTimeMillis()
        schedule(context, c.situations.nextChange(now), fromWorker, now)
    }

    /**
     * Schedules the job for window edge [next] (null: none). This runs at every process start, a ringing call's
     * included, and most phones have no Situation with a window: then WorkManager (its database and scheduler) isn't
     * even started, unless a job is known to be queued from before ([K_QUEUED], true until first known).
     */
    internal fun schedule(
        context: Context,
        next: Long?,
        fromWorker: Boolean,
        now: Long = System.currentTimeMillis(),
        workManager: () -> WorkManager? = { runCatching { WorkManager.getInstance(context) }.getOrNull() },
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (next == null) {
            if (fromWorker || !prefs.getBoolean(K_QUEUED, true)) return
            val wm = workManager() ?: return
            wm.cancelUniqueWork(WORK)
            prefs.edit().putBoolean(K_QUEUED, false).apply()
            return
        }
        val wm = workManager() ?: return
        wm.enqueueUniqueWork(
            WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SituationWorker>().setInitialDelay((next - now).coerceAtLeast(0) + AFTER_EDGE_MS, TimeUnit.MILLISECONDS).build(),
        )
        prefs.edit().putBoolean(K_QUEUED, true).apply()
    }

    /** The worker's run: switch as the triggers say, then schedule the next edge. */
    suspend fun onWindowEdge(context: Context) {
        val c = context.container
        workerRunning = true
        try {
            c.situations.reconcile()
        } finally {
            workerRunning = false
            refreshTile(context)
            // The change listener may not be wired yet in a process the job started: the notice follows here too.
            SituationNotice.update(context.applicationContext, c.situations)
            schedule(context, c, fromWorker = true)
        }
    }

    fun refreshTile(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, SituationTileService::class.java)) }
    }

    /** A Situation's name: the one given to it, else a built-in one's own. */
    fun name(context: Context, s: Situation): String = s.name.trim().ifEmpty {
        context.getString(
            when (s.kind) {
                SituationKind.DRIVING -> R.string.sit_name_driving
                SituationKind.MEETING -> R.string.sit_name_meeting
                SituationKind.NIGHT -> R.string.sit_name_night
                SituationKind.TRAVELLING -> R.string.sit_name_travelling
                SituationKind.CUSTOM -> R.string.sit_name_unnamed
            },
        )
    }
}

/**
 * The clock was set or the time zone changed: windows are wall-clock times, so look now and count the next edge again.
 * Both broadcasts reach a receiver declared in the manifest with no permission; nothing is read unless a Situation has
 * a window or is on.
 */
class SituationClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val app = context.applicationContext
        val done = goAsync()
        val c = app.container
        c.scope.launch(Dispatchers.IO) {
            try {
                suspendRunCatching { c.situations.reconcile() }
                SituationTriggers.schedule(app, c)
                SituationTriggers.refreshTile(app)
            } finally {
                done.finish()
            }
        }
    }
}

/** Runs at a Situation window's edge (WorkManager keeps it across reboots). */
class SituationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            SituationTriggers.onWindowEdge(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        return Result.success()
    }
}
