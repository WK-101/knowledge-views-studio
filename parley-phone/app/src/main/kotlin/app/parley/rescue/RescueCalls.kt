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
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RescueRequest
import app.parley.common.calls.RescueWhen
import app.parley.common.catching
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.security.Privacy
import app.parley.data.security.RecordCrypto
import app.parley.telecom.RescueCall
import app.parley.telecom.RescueCaller
import app.parley.telecom.TelecomGraph
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Rescue call ([RescuePlan]): rings now, or waits and rings later. What it rings is [RescueCall]'s; this side picks who
 * it shows (looked up as their real call would show) and keeps the call waiting (two at most, see below).
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
 * A duress unlock hides it ([hiding]): the screen shows none of the last choices, and what is chosen meanwhile isn't
 * remembered. A call that was waiting still rings at its time (someone may be counting on it) and is never shown,
 * replaced or cancelled while hiding: a call set then for later waits beside it ([RescuePlan.Slot]), and the screen
 * shows and cancels only that one. Each call rings as it was set, with its own sound.
 */
object RescueCalls {
    private const val PREFS = "rescue_call"
    private const val K_ID = "pending_id"
    private const val K_AT = "pending_at"
    private const val K_NAME = "pending_name"
    private const val K_NUMBER = "pending_number"
    private const val K_BOOT = "pending_boot"

    /** The sound a call waiting rings with, kept with it; [K_OWN_CLIP] says it was (a call set before then has none). */
    private const val K_PENDING_CLIP = "pending_clip"
    private const val K_OWN_CLIP = "pending_clip_set"
    private const val K_LAST_NAME = "last_name"
    private const val K_LAST_NUMBER = "last_number"
    private const val K_LAST_WHEN = "last_when"
    private const val K_LAST_MINUTE = "last_minute"
    private const val K_CLIP = "clip"
    private const val K_CLIP_NAME = "clip_name"

    /** The second call's keys are the first's with this in front. */
    private const val SECOND_PREFIX = "second_"

    /** What says who calls, or what is heard: sealed at rest. */
    private val SEALED = listOf(K_NAME, K_NUMBER, K_PENDING_CLIP).flatMap { listOf(it, SECOND_PREFIX + it) } +
        listOf(K_LAST_NAME, K_LAST_NUMBER, K_CLIP, K_CLIP_NAME)
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

    /** One call waiting: where it is kept, its alarm, and while it is near, the timer and wake lock that ring it on time. */
    private class Waiting(val slot: RescuePlan.Slot, val prefix: String, val alarmCode: Int) {
        val request = MutableStateFlow<RescueRequest?>(null)

        /** The sound it rings with, as read back or set. */
        var clip: String? = null
        var timer: Job? = null
        var wakeLock: PowerManager.WakeLock? = null
        fun key(k: String) = prefix + k
    }

    private val first = Waiting(RescuePlan.Slot.FIRST, "", NotificationRequests.RESCUE_ALARM)
    private val second = Waiting(RescuePlan.Slot.SECOND, SECOND_PREFIX, NotificationRequests.RESCUE_ALARM_SECOND)
    private val all = listOf(first, second)
    private fun of(slot: RescuePlan.Slot) = if (slot == RescuePlan.Slot.FIRST) first else second

    // The timer only waits, so it needs no immediate dispatch (and follows the main thread tests put in place).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var loaded = false

    /** The id being rung right now: the alarm and the timer may both come for it, and it rings once. Main thread. */
    private var ringing: String? = null

    /** A call waiting was dropped as it was read back ([RescuePlan.stillWaiting]); the screen says so once. */
    @Volatile
    private var dropped = false

    /** The wall clock; tests drive it with virtual time. */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /** Where stored values are read, sealed and opened (the small-records key can take a moment); tests replace it. */
    @VisibleForTesting
    internal var io: CoroutineDispatcher = Dispatchers.IO

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
    internal var hiding: () -> Boolean = { Privacy.duressOnly().hiding }

    /** Seals a value with the small-records key; null when it can't be sealed right now. Tests replace it. */
    @VisibleForTesting
    internal var seal: (Context, String) -> String? = { app, text -> RecordCrypto.get(app).let { c -> c.sealText(text)?.takeIf(c::isSealed) } }

    /** Values stored plain by an older version were looked at (this process). */
    @Volatile
    private var migrated = false

    @Synchronized
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
     * Reads the calls waiting back the first time (this opens sealed values: call it off the main thread, as [due],
     * [shown] and [refresh] do). One that can no longer ring (the phone restarted since, or its time is long past) is
     * dropped as it is read; one that can has its alarm set again, since an update or a stop of Parley drops alarms too.
     */
    @Synchronized
    private fun load(app: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(app)
        all.forEach { w ->
            val r = p.getString(w.key(K_ID), null)?.let {
                RescueRequest(it, p.opened(app, w.key(K_NAME)).orEmpty(), p.opened(app, w.key(K_NUMBER)), p.getLong(w.key(K_AT), 0))
            }
            when {
                r == null -> Unit
                RescuePlan.stillWaiting(r, p.getInt(w.key(K_BOOT), -1), bootCount(app), clock()) -> {
                    // A call set before its sound was kept with it rings with the remembered one, as it always did.
                    w.clip = if (p.getBoolean(w.key(K_OWN_CLIP), false)) p.opened(app, w.key(K_PENDING_CLIP)) else p.opened(app, K_CLIP)
                    w.request.value = r
                    setAlarm(app, w, r)
                }
                else -> {
                    clear(app, w)
                    dropped = true
                }
            }
        }
    }

    /** The first call waiting to ring, if any (the one a duress unlock hides; see [load]). */
    fun pending(context: Context): StateFlow<RescueRequest?> {
        load(context.applicationContext)
        return first.request.asStateFlow()
    }

    /** The call set while a duress unlock hid the first, if any. */
    @VisibleForTesting
    internal fun second(context: Context): StateFlow<RescueRequest?> {
        load(context.applicationContext)
        return second.request.asStateFlow()
    }

    /** The call waiting as the screen may show it ([RescuePlan.shown]); read back off the main thread. */
    fun shown(context: Context): Flow<RescueRequest?> = flow {
        load(context.applicationContext)
        emitAll(combine(first.request, second.request, Privacy.duressChanges) { a, b, _ -> RescuePlan.shown(hiding(), a, b) })
    }.flowOn(io)

    /**
     * For the screen as it opens (off the main thread): drops a call waiting that can no longer ring and sets the
     * alarm of one that can. True when a call that was waiting was dropped (here or as it was read back), so the
     * screen can say none is.
     */
    fun refresh(context: Context): Boolean {
        val app = context.applicationContext
        load(app)
        all.forEach { w ->
            val r = w.request.value ?: return@forEach
            if (RescuePlan.stillWaiting(r, bootCount(app), bootCount(app), clock())) {
                setAlarm(app, w, r)
            } else {
                cancel(app, w)
                dropped = true
            }
        }
        // While hiding, "none is waiting" would tell that one was.
        return (dropped && !hiding()).also { dropped = false }
    }

    /** The last choices on the screen; the plain defaults while a duress unlock hides things. Opens sealed values. */
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

    /** Whether a call waiting will play [clip] once answered: its permission to read the sound must stay. */
    fun clipInUse(clip: String?): Boolean = clip != null && all.any { it.request.value != null && it.clip == clip }

    /**
     * Rings now, or sets the call to ring later, replacing what [RescuePlan.replaces] says: outside a duress session
     * every call waiting, while hiding only one set later while hiding (the hidden call keeps waiting as it was).
     */
    suspend fun set(context: Context, c: Choices, nowMillis: Long = clock()): Outcome {
        val app = context.applicationContext
        val now = c.whenChoice == RescueWhen.NOW
        val hidingNow = hiding()
        withContext(io) {
            load(app)
            saveChoices(app, c)
            RescuePlan.replaces(hidingNow, now).forEach { cancel(app, of(it)) }
        }
        val name = RescuePlan.shownName(c.name, null).orEmpty()
        val request = RescueRequest(UUID.randomUUID().toString(), name, c.number, RescuePlan.fireAt(c.whenChoice, nowMillis, c.minuteOfDay))
        if (now) return if (ring(app, request, c.clip)) Outcome.RINGING else Outcome.REAL_CALL
        val w = of(RescuePlan.slotFor(hidingNow))
        withContext(io) { store(app, w, request, c.clip) }
        wait(app, w, request, nowMillis)
        return Outcome.WAITING
    }

    /** "Cancel" on the screen: the calls waiting it speaks for ([RescuePlan.cancels]) go, and nothing of them rings. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        load(app)
        RescuePlan.cancels(hiding()).forEach { cancel(app, of(it)) }
    }

    private fun cancel(app: Context, w: Waiting) {
        val p = w.request.value
        clear(app, w)
        if (p != null) alarmManager(app)?.cancel(alarmIntent(app, w, p.id))
    }

    /**
     * An alarm or the in-app timer for [id] came: rings it once, if it is still a call waiting and not too late.
     * The call waiting is cleared only once the ringing has started (or a real call kept it from ringing), and the
     * ringing can't be cancelled midway: clearing it never stops the call it is starting. What is stored is read off
     * the main thread; it rings with the sound kept with it.
     */
    suspend fun due(context: Context, id: String, nowMillis: Long = clock()) {
        val app = context.applicationContext
        withContext(io) { load(app) }
        withContext(Dispatchers.Main.immediate) {
            val w = all.firstOrNull { it.request.value?.id == id } ?: first
            val p = w.request.value
            when (RescuePlan.onAlarm(p, id, nowMillis)) {
                RescuePlan.Due.RING -> if (p != null && ringing != id) {
                    ringing = id
                    try {
                        withContext(NonCancellable) { ring(app, p, w.clip) }
                    } finally {
                        ringing = null
                        if (w.request.value?.id == id) clear(app, w)
                        alarmManager(app)?.cancel(alarmIntent(app, w, id))
                    }
                }
                RescuePlan.Due.EARLY -> p?.let { setAlarm(app, w, it) }
                RescuePlan.Due.STALE -> clear(app, w)
                RescuePlan.Due.GONE -> Unit
            }
            Unit
        }
    }

    /** Rings [request] now with [clip] (the sound chosen with it); false when a real call is up (it always wins, and nothing rings). */
    private suspend fun ring(app: Context, request: RescueRequest, clip: String?): Boolean {
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
        val hidesPrivate = c.privacy.now().privateHidden
        // Their own tone (a contact's, or a private contact's unless those are hidden), else their label's.
        val found = catching { c.numberOwners.find(number, null) }.getOrNull()
        val own = found?.contact?.customRingtone
        val privateTone = if (own != null || hidesPrivate) null else found?.private?.second?.customRingtone
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

    /**
     * Stores a call waiting with its sound. Who calls and the sound are sealed, or left out when they can't be sealed
     * now: the call then shows who it is, and plays its sound, only while Parley stays open, and still rings at its time.
     */
    private fun store(app: Context, w: Waiting, r: RescueRequest, clip: String?) {
        prefs(app).edit {
            putString(w.key(K_ID), r.id)
            putLong(w.key(K_AT), r.atMillis)
            putSealed(app, w.key(K_NAME), r.name)
            putSealed(app, w.key(K_NUMBER), r.number)
            putSealed(app, w.key(K_PENDING_CLIP), clip)
            putBoolean(w.key(K_OWN_CLIP), true)
            putInt(w.key(K_BOOT), bootCount(app))
        }
        w.clip = clip
        w.request.value = r
    }

    private fun clear(app: Context, w: Waiting) {
        w.timer?.cancel()
        w.timer = null
        releaseWakeLock(w)
        prefs(app).edit {
            listOf(K_ID, K_AT, K_NAME, K_NUMBER, K_PENDING_CLIP, K_OWN_CLIP, K_BOOT).forEach { remove(w.key(it)) }
        }
        w.clip = null
        w.request.value = null
    }

    /** The alarm, and for a short wait the phone kept awake and a timer of Parley's own. */
    private fun wait(app: Context, w: Waiting, r: RescueRequest, nowMillis: Long) {
        setAlarm(app, w, r)
        val delayMs = r.atMillis - nowMillis
        if (!RescuePlan.keepsAwake(delayMs)) return
        runCatching {
            w.wakeLock = app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "parley:rescue")
                ?.apply { setReferenceCounted(false); acquire(delayMs + AWAKE_GRACE_MS) }
        }
        w.timer = scope.launch {
            delay(delayMs)
            // From here on this timer is the ringing itself: clearing the call waiting must not cancel it.
            if (w.timer === coroutineContext[Job]) w.timer = null
            due(app, r.id)
        }
    }

    private fun releaseWakeLock(w: Waiting) {
        w.wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        w.wakeLock = null
    }

    private fun setAlarm(app: Context, w: Waiting, r: RescueRequest) {
        val am = alarmManager(app) ?: return
        // Inexact on purpose: Parley holds no exact-alarm permission.
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, alarmIntent(app, w, r.id)) }
    }

    private fun alarmManager(app: Context): AlarmManager? = app.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(app: Context, w: Waiting, id: String): PendingIntent = PendingIntent.getBroadcast(
        app, w.alarmCode,
        Intent(app, RescueAlarmReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** As if Parley's process had just started: the calls waiting are read back from storage next time. */
    @VisibleForTesting
    internal fun forgetForTest() {
        all.forEach { w ->
            w.timer?.cancel()
            w.timer = null
            releaseWakeLock(w)
            w.clip = null
            w.request.value = null
        }
        loaded = false
        migrated = false
        dropped = false
        ringing = null
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
