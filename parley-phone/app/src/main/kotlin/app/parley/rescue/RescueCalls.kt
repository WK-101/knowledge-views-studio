package app.parley.rescue

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import app.parley.common.NotificationRequests
import app.parley.common.calls.CallerHaptics
import app.parley.common.catching
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RescueRequest
import app.parley.common.calls.RescueWhen
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.security.Concealment
import app.parley.data.security.RecordCrypto
import app.parley.telecom.RescueCall
import app.parley.telecom.RescueCaller
import app.parley.telecom.TelecomGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Rescue call ([RescuePlan]): rings now, or waits and rings later. What it rings is [RescueCall]'s; this side picks who
 * it shows (looked up as their real call would show) and keeps the one call waiting.
 *
 * The wait needs no permission. An inexact alarm ([AlarmManager.setAndAllowWhileIdle], no exact-alarm permission)
 * survives Parley being closed; for a short wait ([RescuePlan.keepsAwake]) Parley also keeps the phone awake and rings
 * on time itself. Whichever comes first rings it, once. A restart of the phone drops the wait: Parley holds no
 * permission to hear of restarts, so the call waiting is kept with the phone's boot count and dropped when read back
 * after one ([RescuePlan.stillWaiting]), never shown as waiting when it can't ring. Within the same boot (Parley was
 * updated or stopped, which also drops alarms) it is set again whenever it is read back.
 *
 * Kept in this phone's own preferences, never backed up: the call waiting, and the last choices on the screen. Who
 * calls (the name, the number) and the sound are sealed with the small-records key, never stored as plain text; values an
 * older version stored plain are sealed the first time they are read. Nothing about a rescue call that rang is kept
 * anywhere.
 *
 * A duress unlock hides it ([hiding]): the screen shows no call waiting and none of the last choices, and what is chosen
 * meanwhile isn't remembered. A call that was waiting still rings at its time: someone may be counting on it.
 */
object RescueCalls {
    private const val PREFS = "rescue_call"
    private const val K_ID = "pending_id"
    private const val K_AT = "pending_at"
    private const val K_NAME = "pending_name"
    private const val K_NUMBER = "pending_number"
    private const val K_BOOT = "pending_boot"
    private const val K_LAST_NAME = "last_name"
    private const val K_LAST_NUMBER = "last_number"
    private const val K_LAST_WHEN = "last_when"
    private const val K_LAST_MINUTE = "last_minute"
    private const val K_CLIP = "clip"
    private const val K_CLIP_NAME = "clip_name"

    /** What says who calls, or what is heard: sealed at rest. */
    private val SEALED = listOf(K_NAME, K_NUMBER, K_LAST_NAME, K_LAST_NUMBER, K_CLIP, K_CLIP_NAME)
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

    // The timer only waits, so it needs no immediate dispatch (and follows the main thread tests put in place).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _pending = MutableStateFlow<RescueRequest?>(null)
    private var loaded = false
    private var timer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The id being rung right now: the alarm and the timer may both come for it, and it rings once. Main thread. */
    private var ringing: String? = null

    /** A call waiting was dropped as it was read back ([RescuePlan.stillWaiting]); the screen says so once. */
    private var dropped = false

    /** The wall clock; tests drive it with virtual time. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /** Android's boot count (-1 when unknown); it needs no permission. Tests replace it. */
    @VisibleForTesting
    internal var bootCount: (Context) -> Int = { c ->
        runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    }

    /** Who the call shows, looked up off the main thread; tests replace it, so the ringing itself is real. */
    @VisibleForTesting
    internal var lookUp: suspend (Context, RescueRequest, String?) -> RescueCaller = { app, r, clip ->
        withContext(Dispatchers.IO) { caller(app, app.container, r, clip) }
    }

    /** Whether a duress unlock hides things now; tests replace it. */
    @VisibleForTesting
    internal var hiding: () -> Boolean = { Concealment.hiding }

    /** Seals a value with the small-records key; null when it can't be sealed right now. Tests replace it. */
    @VisibleForTesting
    internal var seal: (Context, String) -> String? = { app, text -> RecordCrypto.get(app).let { c -> c.sealText(text)?.takeIf(c::isSealed) } }

    /** Values stored plain by an older version were looked at (this process). */
    private var migrated = false

    private fun prefs(context: Context): SharedPreferences {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!migrated) {
            migrated = true
            sealPlain(context.applicationContext, p)
        }
        return p
    }

    /** Seals what an older version stored plain; a value that can't be sealed yet stays as it is until the next start. */
    private fun sealPlain(app: Context, p: SharedPreferences) {
        val crypto = RecordCrypto.get(app)
        val plain = SEALED.mapNotNull { k -> p.getString(k, null)?.takeIf { it.isNotEmpty() && !crypto.isSealed(it) }?.let { k to it } }
        if (plain.isEmpty()) return
        p.edit { plain.forEach { (k, v) -> seal(app, v)?.let { putString(k, it) } } }
    }

    /** [value] sealed into [key]; nothing at all when it can't be sealed now (never plain). */
    private fun SharedPreferences.Editor.putSealed(app: Context, key: String, value: String?) {
        val sealed = value?.takeIf { it.isNotEmpty() }?.let { seal(app, it) }
        if (sealed == null) remove(key) else putString(key, sealed)
    }

    private fun SharedPreferences.opened(app: Context, key: String): String? = RecordCrypto.get(app).openText(getString(key, null))

    /**
     * The call waiting to ring, if any (read from this phone's preferences the first time). One that can no longer
     * ring (the phone restarted since, or its time is long past) is dropped as it is read; one that can has its alarm
     * set again, since an update or a stop of Parley drops alarms too.
     */
    fun pending(context: Context): StateFlow<RescueRequest?> {
        if (!loaded) {
            loaded = true
            val app = context.applicationContext
            val p = prefs(app)
            val r = p.getString(K_ID, null)?.let { RescueRequest(it, p.opened(app, K_NAME).orEmpty(), p.opened(app, K_NUMBER), p.getLong(K_AT, 0)) }
            when {
                r == null -> Unit
                RescuePlan.stillWaiting(r, p.getInt(K_BOOT, -1), bootCount(app), clock()) -> {
                    _pending.value = r
                    setAlarm(app, r)
                }
                else -> {
                    clear(app)
                    dropped = true
                }
            }
        }
        return _pending.asStateFlow()
    }

    /**
     * The call waiting as the screen may show it: none while a duress unlock hides things (it still rings, see
     * [RescueCalls]).
     */
    fun shown(context: Context): Flow<RescueRequest?> =
        combine(pending(context), Concealment.state) { p, _ -> p.takeUnless { hiding() } }

    /**
     * For the screen as it opens: drops a call waiting that can no longer ring and sets the alarm of one that can.
     * True when a call that was waiting was dropped (here or as it was read back), so the screen can say none is.
     */
    fun refresh(context: Context): Boolean {
        val app = context.applicationContext
        pending(app)
        val r = _pending.value
        if (r != null) {
            if (RescuePlan.stillWaiting(r, bootCount(app), bootCount(app), clock())) {
                setAlarm(app, r)
            } else {
                cancel(app)
                dropped = true
            }
        }
        // While hiding, "none is waiting" would tell that one was.
        return (dropped && !hiding()).also { dropped = false }
    }

    /** The last choices on the screen; the plain defaults while a duress unlock hides things. */
    fun choices(context: Context): Choices {
        if (hiding()) return Choices()
        val app = context.applicationContext
        val p = prefs(app)
        return Choices(
            name = p.opened(app, K_LAST_NAME).orEmpty(),
            number = p.opened(app, K_LAST_NUMBER),
            whenChoice = p.getString(K_LAST_WHEN, null)?.let { w -> RescueWhen.entries.firstOrNull { it.name == w } } ?: RescueWhen.IN_1,
            minuteOfDay = p.getInt(K_LAST_MINUTE, DEFAULT_MINUTE),
            clip = p.opened(app, K_CLIP),
            clipName = p.opened(app, K_CLIP_NAME),
        )
    }

    /** Remembers [c] for next time; not while a duress unlock hides things (the stored ones stay as they were). */
    fun saveChoices(context: Context, c: Choices) {
        if (hiding()) return
        val app = context.applicationContext
        prefs(app).edit {
            putSealed(app, K_LAST_NAME, c.name)
            putSealed(app, K_LAST_NUMBER, c.number)
            putString(K_LAST_WHEN, c.whenChoice.name)
            putInt(K_LAST_MINUTE, c.minuteOfDay)
            putSealed(app, K_CLIP, c.clip)
            putSealed(app, K_CLIP_NAME, c.clipName)
        }
    }

    /**
     * Rings now, or sets the call to ring later (replacing one already waiting). While a duress unlock hides things,
     * ringing now leaves the hidden call waiting as it was.
     */
    suspend fun set(context: Context, c: Choices, nowMillis: Long = clock()): Outcome {
        val app = context.applicationContext
        saveChoices(app, c)
        val now = c.whenChoice == RescueWhen.NOW
        if (!now || !hiding()) cancel(app)
        val name = RescuePlan.shownName(c.name, null).orEmpty()
        val request = RescueRequest(UUID.randomUUID().toString(), name, c.number, RescuePlan.fireAt(c.whenChoice, nowMillis, c.minuteOfDay))
        if (now) return if (ring(app, request, c.clip)) Outcome.RINGING else Outcome.REAL_CALL
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

    /**
     * An alarm or the in-app timer for [id] came: rings it once, if it is still the call waiting and not too late.
     * The call waiting is cleared only once the ringing has started (or a real call kept it from ringing), and the
     * ringing can't be cancelled midway: clearing it never stops the call it is starting.
     */
    suspend fun due(context: Context, id: String, nowMillis: Long = clock()): Unit = withContext(Dispatchers.Main.immediate) {
        val app = context.applicationContext
        val p = pending(app).value
        when (RescuePlan.onAlarm(p, id, nowMillis)) {
            RescuePlan.Due.RING -> if (p != null && ringing != id) {
                ringing = id
                try {
                    withContext(NonCancellable) { ring(app, p) }
                } finally {
                    ringing = null
                    if (_pending.value?.id == id) clear(app)
                    alarmManager(app)?.cancel(alarmIntent(app, id))
                }
            }
            RescuePlan.Due.EARLY -> p?.let { setAlarm(app, it) }
            RescuePlan.Due.STALE -> clear(app)
            RescuePlan.Due.GONE -> Unit
        }
        Unit
    }

    /**
     * Rings [request] now; false when a real call is up (it always wins, and nothing rings). [clip]: the sound chosen
     * with it, else the remembered one (read even while hiding: the call is the same whatever shows).
     */
    private suspend fun ring(app: Context, request: RescueRequest, clip: String? = storedClip(app)): Boolean {
        val who = lookUp(app, request, clip)
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
        // Their own tone (a contact's, or a private contact's unless those are hidden), else their label's.
        val own = catching { c.contacts.lookup(number)?.customRingtone }.getOrNull()
        val privateTone = if (own != null || hidesPrivate) {
            null
        } else {
            suspendRunCatching { c.vault.lookup(number, PhoneEnv.countryIso(app))?.second?.customRingtone }.getOrNull()
        }
        val tone = own ?: privateTone ?: catching { c.people.ringtoneForNumber(number) }.getOrNull()
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

    private fun storedClip(app: Context): String? = prefs(app).opened(app, K_CLIP)

    /**
     * Stores the call waiting. Who calls is sealed, or left out when it can't be sealed now: the call then shows who it
     * is only while Parley stays open, and still rings at its time.
     */
    private fun store(app: Context, r: RescueRequest) {
        pending(app)
        prefs(app).edit {
            putString(K_ID, r.id)
            putLong(K_AT, r.atMillis)
            putSealed(app, K_NAME, r.name)
            putSealed(app, K_NUMBER, r.number)
            putInt(K_BOOT, bootCount(app))
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
            remove(K_BOOT)
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
            // From here on this timer is the ringing itself: clearing the call waiting must not cancel it.
            if (timer === coroutineContext[Job]) timer = null
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

    /** As if Parley's process had just started: the call waiting is read back from storage next time. */
    @VisibleForTesting
    internal fun forgetForTest() {
        timer?.cancel()
        timer = null
        releaseWakeLock()
        loaded = false
        migrated = false
        dropped = false
        ringing = null
        _pending.value = null
    }

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
