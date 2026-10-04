package app.parley.rescue

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.edit
import app.parley.common.NotificationRequests
import app.parley.common.calls.CallerHaptics
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RescueRequest
import app.parley.common.calls.RescueWhen
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.telecom.RescueCall
import app.parley.telecom.RescueCaller
import app.parley.telecom.TelecomGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Rescue call ([RescuePlan]): rings now, or waits and rings later. What it rings is [RescueCall]'s; this side picks who
 * it shows (looked up as their real call would show) and keeps the one call waiting.
 *
 * The wait needs no permission. An inexact alarm ([AlarmManager.setAndAllowWhileIdle], no exact-alarm permission)
 * survives Parley being closed; for a short wait ([RescuePlan.keepsAwake]) Parley also keeps the phone awake and rings
 * on time itself. Whichever comes first rings it, once. A restart of the phone drops the wait.
 *
 * Kept in this phone's own preferences, never backed up: the call waiting, and the last choices on the screen. Nothing
 * about a rescue call that rang is kept anywhere.
 */
object RescueCalls {
    private const val PREFS = "rescue_call"
    private const val K_ID = "pending_id"
    private const val K_AT = "pending_at"
    private const val K_NAME = "pending_name"
    private const val K_NUMBER = "pending_number"
    private const val K_LAST_NAME = "last_name"
    private const val K_LAST_NUMBER = "last_number"
    private const val K_LAST_WHEN = "last_when"
    private const val K_LAST_MINUTE = "last_minute"
    private const val K_CLIP = "clip"
    private const val K_CLIP_NAME = "clip_name"
    internal const val EXTRA_ID = "rescue_id"

    /** A wake lock is held at most this much past the time, should ringing take a moment. */
    private const val AWAKE_GRACE_MS = 60_000L

    /** The screen's choices: who calls (a name, and the saved person's number), when, and the sound once answered. */
    data class Choices(
        val name: String = "",
        val number: String? = null,
        val whenChoice: RescueWhen = RescueWhen.IN_1,
        val minuteOfDay: Int = DEFAULT_MINUTE,
        val clip: String? = null,
        val clipName: String? = null,
    )

    /** What setting it up did. */
    enum class Outcome { RINGING, WAITING, REAL_CALL }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _pending = MutableStateFlow<RescueRequest?>(null)
    private var loaded = false
    private var timer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The call waiting to ring, if any (read from this phone's preferences the first time). */
    fun pending(context: Context): StateFlow<RescueRequest?> {
        if (!loaded) {
            loaded = true
            val p = prefs(context)
            val id = p.getString(K_ID, null)
            _pending.value = id?.let { RescueRequest(it, p.getString(K_NAME, null).orEmpty(), p.getString(K_NUMBER, null), p.getLong(K_AT, 0)) }
        }
        return _pending.asStateFlow()
    }

    fun choices(context: Context): Choices {
        val p = prefs(context)
        return Choices(
            name = p.getString(K_LAST_NAME, null).orEmpty(),
            number = p.getString(K_LAST_NUMBER, null),
            whenChoice = p.getString(K_LAST_WHEN, null)?.let { w -> RescueWhen.entries.firstOrNull { it.name == w } } ?: RescueWhen.IN_1,
            minuteOfDay = p.getInt(K_LAST_MINUTE, DEFAULT_MINUTE),
            clip = p.getString(K_CLIP, null),
            clipName = p.getString(K_CLIP_NAME, null),
        )
    }

    fun saveChoices(context: Context, c: Choices) {
        prefs(context).edit {
            putString(K_LAST_NAME, c.name)
            putString(K_LAST_NUMBER, c.number)
            putString(K_LAST_WHEN, c.whenChoice.name)
            putInt(K_LAST_MINUTE, c.minuteOfDay)
            putString(K_CLIP, c.clip)
            putString(K_CLIP_NAME, c.clipName)
        }
    }

    /** Rings now, or sets the call to ring later (replacing one already waiting). */
    suspend fun set(context: Context, c: Choices, nowMillis: Long = System.currentTimeMillis()): Outcome {
        val app = context.applicationContext
        saveChoices(app, c)
        cancel(app)
        val name = RescuePlan.shownName(c.name, null).orEmpty()
        val request = RescueRequest(UUID.randomUUID().toString(), name, c.number, RescuePlan.fireAt(c.whenChoice, nowMillis, c.minuteOfDay))
        if (c.whenChoice == RescueWhen.NOW) return if (ring(app, request)) Outcome.RINGING else Outcome.REAL_CALL
        store(app, request)
        wait(app, request, nowMillis)
        return Outcome.WAITING
    }

    /** The call waiting goes: nothing rings. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        pending(app)
        val p = _pending.value
        clear(app)
        if (p != null) alarmManager(app)?.cancel(alarmIntent(app, p.id))
    }

    /** An alarm or the in-app timer for [id] came: rings it once, if it is still the call waiting and not too late. */
    suspend fun due(context: Context, id: String, nowMillis: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val p = withContext(Dispatchers.Main.immediate) { pending(app).value }
        when (RescuePlan.onAlarm(p, id, nowMillis)) {
            RescuePlan.Due.RING -> {
                clear(app)
                p?.let { ring(app, it) }
            }
            RescuePlan.Due.EARLY -> p?.let { setAlarm(app, it) }
            RescuePlan.Due.STALE -> clear(app)
            RescuePlan.Due.GONE -> Unit
        }
    }

    /** Rings [request] now; false when a real call is up (it always wins, and nothing rings). */
    private suspend fun ring(app: Context, request: RescueRequest): Boolean {
        val choices = choices(app)
        val who = withContext(Dispatchers.IO) { caller(app, app.container, request, choices.clip) }
        return withContext(Dispatchers.Main.immediate) { RescueCall.start(app, who) }
    }

    /**
     * Who the call shows: for a saved person, what their real call would show (looked up now, so discreet mode and a
     * changed photo count) with their tone and vibration; for a name only, that name with the phone's default tone.
     * The lookups only read: nothing is noted, counted or logged.
     */
    private suspend fun caller(app: Context, c: DataContainer, r: RescueRequest, clip: String?): RescueCaller {
        val number = r.number?.takeIf { it.isNotBlank() } ?: return RescueCaller(name = RescuePlan.shownName(r.name, null), clip = clip)
        val shown = suspendRunCatching { TelecomGraph.dependencies.callerInfo(number, null) }.getOrNull()
        val hidesPrivate = suspendRunCatching { c.settings.current().hideVault }.getOrDefault(true)
        val tone = runCatching { c.contacts.lookup(number)?.customRingtone }.getOrNull()
            ?: if (hidesPrivate) null else suspendRunCatching { c.vault.lookup(number, PhoneEnv.countryIso(app))?.second?.customRingtone }.getOrNull()
            ?: runCatching { c.people.ringtoneForNumber(number) }.getOrNull()
        val pattern = shown?.vibration?.let(CallerHaptics::decode)?.let { CallerHaptics.repeating(it, shown.name) }
        // Nobody found for the number: the name typed, unless "Hide private contacts" hides who it is (a real call
        // from them would show the number only).
        val name = shown?.name ?: RescuePlan.shownName(r.name, null).takeIf { !hidesPrivate }
        return RescueCaller(
            name = name, number = number, label = shown?.label, photoUri = shown?.photoUri, backgroundUri = shown?.backgroundUri,
            contactId = shown?.contactId, lookupKey = shown?.lookupKey, subtitle = shown?.subtitle, pronouns = shown?.pronouns,
            ringtone = tone, vibration = pattern, clip = clip,
        )
    }

    private fun store(app: Context, r: RescueRequest) {
        pending(app)
        prefs(app).edit {
            putString(K_ID, r.id)
            putLong(K_AT, r.atMillis)
            putString(K_NAME, r.name)
            putString(K_NUMBER, r.number)
        }
        _pending.value = r
    }

    private fun clear(app: Context) {
        timer?.cancel()
        timer = null
        releaseWakeLock()
        prefs(app).edit {
            remove(K_ID)
            remove(K_AT)
            remove(K_NAME)
            remove(K_NUMBER)
        }
        _pending.value = null
    }

    /** The alarm, and for a short wait the phone kept awake and a timer of Parley's own. */
    private fun wait(app: Context, r: RescueRequest, nowMillis: Long) {
        setAlarm(app, r)
        val delayMs = r.atMillis - nowMillis
        if (!RescuePlan.keepsAwake(delayMs)) return
        runCatching {
            wakeLock = app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "parley:rescue")
                ?.apply { setReferenceCounted(false); acquire(delayMs + AWAKE_GRACE_MS) }
        }
        timer = scope.launch {
            delay(delayMs)
            due(app, r.id)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun setAlarm(app: Context, r: RescueRequest) {
        val am = alarmManager(app) ?: return
        // Inexact on purpose: Parley holds no exact-alarm permission.
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, alarmIntent(app, r.id)) }
    }

    private fun alarmManager(app: Context): AlarmManager? = app.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(app: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        app, NotificationRequests.RESCUE_ALARM,
        Intent(app, RescueAlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 18:00 until another time is chosen. */
    private const val DEFAULT_MINUTE = 18 * 60
}

/** A rescue call's alarm (not exported: only Parley's own PendingIntent reaches it). */
class RescueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(RescueCalls.EXTRA_ID) ?: return
        val done = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                RescueCalls.due(app, id)
            } finally {
                done.finish()
            }
        }
    }
}
