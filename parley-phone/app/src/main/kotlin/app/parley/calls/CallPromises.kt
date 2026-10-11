package app.parley.calls

import android.content.Context
import android.text.format.DateFormat
import app.parley.R
import app.parley.common.PhoneIdentity
import app.parley.common.calls.CallbackWatch
import app.parley.common.calls.PromiseTimes
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallSource
import app.parley.common.catching
import app.parley.common.situations.SituationCause
import app.parley.data.DataContainer
import app.parley.data.NumberInfo
import app.parley.data.PhoneEnv
import app.parley.data.people.NumberOwners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Promise a time" and "They'll call me back": both keep their record on the person's To call item (a
 * [app.parley.common.calls.PromisedTime], or a [ToCallSource.CALLBACK] item with its expected window), so the list,
 * its reminder and the call screen read one place.
 */
object CallPromises {
    /** Their time zone, when the number tells it (offline). */
    fun theirZone(context: Context, number: String, accountId: String?): ZoneId? =
        runCatching { NumberInfo.timeZone(number, PhoneEnv.countryIso(context, accountId)) }.getOrNull()

    /** When the Situation on now ends, for "when you're free" (null when none is on, or it has no end). */
    fun freeAt(c: DataContainer, now: Long = System.currentTimeMillis()): Long? = catching {
        val s = c.situations
        val state = s.state.value
        val active = state.activeId?.let { id -> s.list.value.firstOrNull { it.id == id } } ?: return@catching null
        PromiseTimes.freeAt(
            state.until, scheduled = state.cause == SituationCause.SCHEDULE, active.schedule, now, ZoneId.systemDefault(),
        )
    }.getOrNull()

    /**
     * Keeps the promise of [at] to [number] on To call (the reminder at that time) and returns the message to send,
     * said in [saidIn] when their clock differs; null when nothing could be saved (no number, or the list can't be read
     * right now).
     */
    suspend fun promise(context: Context, number: String, accountId: String?, at: Long, saidIn: ZoneId?, now: Long = System.currentTimeMillis()): String? {
        val record = PromiseTimes.record(at, now, saidIn)
        val ok = catching { ToCallReminders.remind(context, number, accountId, at, ToCallSource.REMINDER, now, promised = record) }.getOrDefault(false)
        return if (ok) message(context, at, now, saidIn) else null
    }

    /** "I'll call you back at 18:00." in their words: tomorrow or a weekday when it isn't today for them. */
    fun message(context: Context, at: Long, now: Long, saidIn: ZoneId?): String {
        val zone = saidIn ?: ZoneId.systemDefault()
        val tz = TimeZone.getTimeZone(zone)
        val clock = DateFormat.getTimeFormat(context).apply { timeZone = tz }.format(Date(at))
        val time = if (saidIn != null) context.getString(R.string.promise_sms_your_time, clock) else clock
        return when (PromiseTimes.daysAhead(at, now, zone)) {
            0L -> context.getString(R.string.promise_sms_today, time)
            1L -> context.getString(R.string.promise_sms_tomorrow, time)
            else -> context.getString(
                R.string.promise_sms_day, SimpleDateFormat("EEEE", Locale.getDefault()).apply { timeZone = tz }.format(Date(at)), time,
            )
        }
    }

    /**
     * "They'll call me back" by [by]: a To call item due then ("hasn't called back" once it is), and a window that lets
     * the call ring and keeps "never calls you" quiet until then. [anyCaller] lets numbers nobody saved ring too. False
     * when nothing could be saved.
     */
    suspend fun expectCallback(
        context: Context, c: DataContainer, number: String, accountId: String?, by: Long, anyCaller: Boolean, now: Long = System.currentTimeMillis(),
    ): Boolean {
        val iso = PhoneEnv.countryIso(context, accountId)
        val key = PhoneIdentity.key(number, iso)
        if (!ToCallReminders.canRemind(key, number)) return false
        val zone = theirZone(context, number, accountId)?.id
        catching { ToCallReminders.update(context) { ToCall.remind(it, key, number, by, now, ToCallSource.CALLBACK, zone = zone, accountId = accountId) } }
        if (!c.toCall.available) return false
        val (name, private) = ExpectedCallHints.caller(c, number)
        if (!c.familySafety.load()) return false
        return c.familySafety.putWindow(CallbackWatch.window(number, key, now, by, anyCaller, name, private), now)
    }

    /**
     * For the call screen: the time you told [number] you'd call, while that call is still owed. Never a private
     * contact's while private contacts are hidden (discreet mode, a duress unlock), where the caller is a plain number.
     */
    suspend fun promisedAt(c: DataContainer, number: String, accountId: String?): Long? = withContext(Dispatchers.IO) {
        val iso = PhoneEnv.countryIso(c.appContext, accountId)
        val key = PhoneIdentity.key(number, iso)
        if (key.isEmpty()) return@withContext null
        val state = catching { c.toCall.load() }.getOrNull() ?: return@withContext null
        val p = ToCall.promisedTo(state, key) ?: return@withContext null
        if (c.privacy.now().privateHidden) {
            // Unsure counts as private: the line stays off.
            val found = catching { c.numberOwners.findIn(number, iso, NumberOwners.Use.CALL_PATH) }.getOrNull()
            if (found?.notPrivate != true) return@withContext null
        }
        p.at
    }
}
