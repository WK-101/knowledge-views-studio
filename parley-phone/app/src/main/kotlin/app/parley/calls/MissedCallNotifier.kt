package app.parley.calls

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.CallLog.Calls
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.MainActivity
import app.parley.MissedCallActionReceiver
import app.parley.R
import app.parley.common.NotificationPrivacy
import app.parley.common.PhoneNumbers
import app.parley.common.calls.DndState
import app.parley.common.calls.MissedCall
import app.parley.common.calls.MissedCaller
import app.parley.common.calls.MissedCalls
import app.parley.common.calls.MissedReAlert
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.ui.Bidi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parley's missed-call notifications (V2, V3). Telecom delegates them to the default dialer and only says how many
 * there are and the latest number; the details come from the call log:
 * - one notification per caller with a count, grouped under a summary when several people called;
 * - the contact photo, the SIM (dual-SIM phones), the time, and "Why didn't it ring?" from the stored screening
 *   decision and ring facts ("Silenced: off hours", "Didn't ring: phone on silent");
 * - Call back, Message on… and (for numbers that aren't contacts) Block;
 * - F14: a private contact's name never shows in discreet mode, and the lock screen only gets "Missed call".
 * With "Remind me of missed calls" on, the newest one alerts again every few minutes until it's seen (V3).
 */
object MissedCallNotifier {
    const val CHANNEL = "missed_calls_v1"
    const val ID = 4712
    private const val CHILD_BASE = 4720
    private const val GROUP = "app.parley.MISSED_CALLS"
    private const val PREFS = "parley_missed_realert"

    /** Shows (or clears) the missed-call notifications. [telecomCount] and [latestNumber] come from Telecom. */
    suspend fun show(context: Context, telecomCount: Int, latestNumber: String?, reAlert: Boolean = false) = withContext(Dispatchers.IO) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (telecomCount <= 0) {
            cancelAll(context)
            stopReAlert(context)
            return@withContext
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.missed_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val c = context.container
        val unseen = unseenMissed(context)
        // The call log can lag behind Telecom's broadcast: then show what Telecom told us.
        val callers = MissedCalls.group(unseen).ifEmpty {
            val n = latestNumber.orEmpty()
            listOf(MissedCaller(if (n.isBlank()) MissedCalls.HIDDEN else n, n, n.isBlank(), telecomCount, System.currentTimeMillis(), System.currentTimeMillis(), null))
        }
        val total = maxOf(telecomCount, callers.sumOf { it.count })
        val sims = runCatching { c.sims.accounts() }.getOrDefault(emptyList())
        val simLabels = if (sims.size > 1) sims.associate { it.id to it.label } else emptyMap()
        val hideVault = c.settings.current().hideVault
        val shown = callers.take(MissedCalls.MAX_CHILDREN)
        val grouped = shown.size > 1
        val screened = runCatching { c.blocks.screenedSince((callers.minOf { it.first }) - 15 * 60_000L) }.getOrDefault(emptyList())
        val nmc = NotificationManagerCompat.from(context)
        val details = shown.map { caller -> describe(context, c, caller, hideVault, simLabels, screened) }

        shown.forEachIndexed { i, caller ->
            val d = details[i]
            val id = if (grouped) CHILD_BASE + i else ID
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(app.parley.ui.R.drawable.ic_stat_missed)
                .setContentTitle(d.title)
                .setContentText(d.line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(d.line, d.why).joinToString("\n")))
                .setWhen(caller.latest)
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setContentIntent(openRecents(context))
                .setAutoCancel(true)
                .setNumber(caller.count)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, if (grouped) total else caller.count))
                .setDeleteIntent(broadcast(context, if (grouped) MissedCallActionReceiver.ACTION_DISMISSED_ONE else MissedCallActionReceiver.ACTION_CLEAR, null, 20 + i))
            d.photo?.let { b.setLargeIcon(it) }
            // I6: job or "who is this" (private version only; never for private contacts in discreet mode).
            if (!caller.hidden && caller.number.isNotBlank()) app.parley.data.people.CallerCards.missedCallLine(c, caller.number, hideVault)?.let { b.setSubText(it) }
            // Only the newest caller makes a sound (or the re-alert); the others arrive quietly.
            if (i > 0) b.setSilent(true)
            if (grouped) b.setGroup(GROUP).setSortKey("%02d".format(java.util.Locale.ROOT, i))
            if (!caller.hidden && caller.number.isNotBlank()) {
                // One-ring scams and premium lines: no one-tap call back from the notification (B10); the app asks first.
                val risky = runCatching { c.dialGuard.check(caller.number).any { it.severe } }.getOrDefault(false)
                if (!risky) b.addAction(0, context.getString(R.string.missed_call_back), broadcast(context, MissedCallActionReceiver.ACTION_CALL_BACK, caller.number, 30 + i, id))
                b.addAction(
                    0, context.getString(R.string.missed_message_on),
                    PendingIntent.getActivity(
                        context, 40 + i, app.parley.messaging.MessageOn.intent(context, caller.number, caller.accountId),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                if (!d.isContact) b.addAction(blockAction(context, caller.number, 50 + i, id))
            }
            try {
                nmc.notify(id, b.build())
            } catch (_: SecurityException) {
            }
        }
        // Children that are no longer needed (a caller whose calls were seen, or a single caller now).
        (0 until MissedCalls.MAX_CHILDREN).filter { !grouped || it >= shown.size }.forEach { nm.cancel(CHILD_BASE + it) }
        if (grouped) {
            val inbox = NotificationCompat.InboxStyle()
            details.forEach { inbox.addLine(it.inboxLine) }
            if (callers.size > shown.size) inbox.setSummaryText(context.getString(R.string.missed_more, callers.size - shown.size))
            val title = summaryTitle(context, total, callers.size)
            val summary = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(app.parley.ui.R.drawable.ic_stat_missed)
                .setContentTitle(title)
                .setContentText(details.joinToString(", ") { it.title })
                .setStyle(inbox.setBigContentTitle(title))
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setContentIntent(openRecents(context))
                .setAutoCancel(true)
                .setNumber(total)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context, total))
                .setDeleteIntent(broadcast(context, MissedCallActionReceiver.ACTION_CLEAR, null, 13))
            try {
                nmc.notify(ID, summary.build())
            } catch (_: SecurityException) {
            }
        }
        scheduleReAlert(context, c, callers.first().latest, reAlert)
    }

    private class Shown(val title: String, val line: String, val why: String?, val inboxLine: String, val photo: Bitmap?, val isContact: Boolean)

    private suspend fun describe(
        context: Context, c: DataContainer, caller: MissedCaller, hideVault: Boolean, simLabels: Map<String, String>,
        screened: List<app.parley.data.db.BlockedCallEntity>,
    ): Shown {
        val number = caller.number.takeIf { !caller.hidden && it.isNotBlank() }
        val contact = number?.let { runCatching { c.contacts.lookup(it) }.getOrNull() }
        val vaultName = if (contact == null && number != null) runCatching { c.vault.lookup(number)?.second?.name }.getOrNull() else null
        // F14: a private contact's name never shows in discreet mode.
        val name = NotificationPrivacy.missedCallName(contact?.name, vaultName, hideVault, number)
            ?.let { if (it == number) Bidi.ltr(it) else it } ?: context.getString(R.string.main_private_number)
        val time = DateUtils.formatDateTime(context, caller.latest, DateUtils.FORMAT_SHOW_TIME)
        val sim = caller.accountId?.let { simLabels[it] }
        val sep = context.getString(R.string.main_separator)
        val first = if (caller.count > 1) context.resources.getQuantityString(R.plurals.missed_count_last, caller.count, caller.count, time) else context.getString(R.string.missed_one_at, time)
        val line = listOfNotNull(first, sim).joinToString(sep)
        // "Why didn't it ring?": Parley's own reason first (a silence rule), then the ringer's state.
        val verdict = number?.let { n ->
            val iso = PhoneEnv.countryIso(context, caller.accountId)
            screened.firstOrNull { e ->
                !e.allowed && e.action == "SILENCE" && e.number != null && kotlin.math.abs(e.time - caller.latest) < 5 * 60_000L &&
                    PhoneNumbers.same(e.number, n, iso)
            }?.verdict?.let { v -> app.parley.blocking.BlockingText.verdict(context, v) }
        }
        val facts = runCatching { c.ringFacts.near(number, caller.latest) }.getOrNull()
        val why = app.parley.ui.calls.RingText.whyNoRing(context.resources, facts, verdict)
        val photo = contact?.photoUri?.let { loadCircle(context, it) }
        val inboxLine = (if (caller.count > 1) context.getString(R.string.missed_name_count, name, caller.count) else name) + sep + time + (sim?.let { sep + it } ?: "")
        // Discreet mode: "Block" depends on phone contacts only, so its absence never reveals a private contact.
        return Shown(name, line, why, inboxLine, photo, isContact = contact != null || (vaultName != null && !hideVault))
    }

    /** Unseen missed calls, newest first (what Telecom counts: missed, new and not read). */
    private fun unseenMissed(context: Context): List<MissedCall> = try {
        context.contentResolver.query(
            Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, "50").build(),
            arrayOf(Calls.NUMBER, Calls.DATE, Calls.PHONE_ACCOUNT_ID, Calls.NUMBER_PRESENTATION),
            "${Calls.TYPE} = ? AND ${Calls.NEW} = 1 AND (${Calls.IS_READ} = 0 OR ${Calls.IS_READ} IS NULL)",
            arrayOf(Calls.MISSED_TYPE.toString()),
            "${Calls.DATE} DESC",
        )?.use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    val n = cur.getString(0).orEmpty()
                    val account = cur.getString(2)
                    val hidden = cur.getInt(3) != Calls.PRESENTATION_ALLOWED || n.isBlank()
                    add(MissedCall(n, cur.getLong(1), account, hidden, if (hidden) MissedCalls.HIDDEN else PhoneNumbers.lineKey(n, PhoneEnv.countryIso(context, account))))
                }
            }
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun publicVersion(context: Context, count: Int) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(app.parley.ui.R.drawable.ic_stat_missed)
        .setContentTitle(title(context, count))
        .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
        .setNumber(count)
        .build()

    private fun openRecents(context: Context) = PendingIntent.getActivity(
        context, 10,
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_MISSED).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, action: String, number: String?, req: Int, notificationId: Int = ID) = PendingIntent.getBroadcast(
        context, req,
        Intent(context, MissedCallActionReceiver::class.java).setAction(action).putExtra("number", number).putExtra(MissedCallActionReceiver.EXTRA_ID, notificationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * "Block" needs the phone unlocked: on Android 12+ the system asks before sending the broadcast; before that it opens
     * the rule editor (an activity, which the lock screen only starts after unlocking).
     */
    private fun blockAction(context: Context, number: String, req: Int, notificationId: Int): NotificationCompat.Action {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return NotificationCompat.Action.Builder(0, context.getString(R.string.main_block), broadcast(context, MissedCallActionReceiver.ACTION_BLOCK, number, req, notificationId))
                .setAuthenticationRequired(true).build()
        }
        val pi = PendingIntent.getActivity(
            context, req,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_POST_CALL)
                .putExtra(MainActivity.EXTRA_POST_CALL_ACTION, "BLOCK").putExtra(MainActivity.EXTRA_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.main_block), pi).build()
    }

    /** "Missed call" or "3 missed calls" (the words of [MissedCalls.title], localised). */
    private fun title(context: Context, count: Int): String =
        if (count <= 1) context.getString(R.string.missed_title_one) else context.resources.getQuantityString(R.plurals.missed_title_many, count, count)

    /** "5 missed calls from 3 callers". */
    private fun summaryTitle(context: Context, total: Int, callers: Int): String =
        if (callers <= 1) title(context, total) else context.resources.getQuantityString(R.plurals.missed_summary, total, total, callers)

    fun cancelAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ID)
        (0 until MissedCalls.MAX_CHILDREN).forEach { nm.cancel(CHILD_BASE + it) }
    }

    /** Any missed-call notification still on screen. */
    fun anyShowing(context: Context, childrenOnly: Boolean = false): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { (!childrenOnly && it.id == ID) || it.id in CHILD_BASE until CHILD_BASE + MissedCalls.MAX_CHILDREN }
    }.getOrDefault(false)

    /** A small round contact photo for the notification's large icon. */
    private fun loadCircle(context: Context, uri: String): Bitmap? = runCatching {
        val src = context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } ?: return null
        val size = minOf(src.width, src.height).coerceAtMost(256)
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        out
    }.getOrNull()

    // ---------------------------------------------------------------- V3: re-alert

    /**
     * Schedules the next re-alert (inexact, allowed while idle: no exact-alarm permission). A new missed call starts
     * the count again; the schedule stops after [MissedReAlert.MAX_ALERTS] or [MissedReAlert.MAX_SPAN_MS].
     */
    private fun scheduleReAlert(context: Context, c: DataContainer, latestMissed: Long, afterReAlert: Boolean) {
        val minutes = c.callExtras.config.value.missedReAlertMinutes
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (minutes <= 0) {
            stopReAlert(context)
            return
        }
        val sameCall = prefs.getLong(KEY_FOR, -1) == latestMissed
        val count = if (sameCall) prefs.getInt(KEY_COUNT, 0) + (if (afterReAlert) 1 else 0) else 0
        prefs.edit().putLong(KEY_FOR, latestMissed).putInt(KEY_COUNT, count).apply()
        val at = MissedReAlert.nextAt(minutes, latestMissed, count, System.currentTimeMillis())
        if (at == null) {
            cancelAlarm(context)
            return
        }
        runCatching { context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context)) }
    }

    /** Called when the re-alert alarm fires. */
    suspend fun onReAlertDue(context: Context) {
        val c = context.container
        // Turned off since the alarm was set.
        if (c.callExtras.config.value.missedReAlertMinutes <= 0) {
            stopReAlert(context)
            return
        }
        val unseen = unseenMissed(context)
        val dnd = when (runCatching { context.getSystemService(NotificationManager::class.java).currentInterruptionFilter }.getOrNull()) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> DndState.OFF
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndState.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndState.ALARMS
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndState.TOTAL_SILENCE
            else -> DndState.UNKNOWN
        }
        when (MissedReAlert.step(unseen.isNotEmpty(), anyShowing(context), dnd)) {
            MissedReAlert.Step.ALERT -> show(context, unseen.size, unseen.firstOrNull()?.number, reAlert = true)
            // Do Not Disturb: stay quiet now, but keep counting towards the limit.
            MissedReAlert.Step.SKIP -> scheduleReAlert(context, c, unseen.maxOf { it.date }, afterReAlert = true)
            MissedReAlert.Step.STOP -> stopReAlert(context)
        }
    }

    /** Stops re-alerting (the calls were seen, the notification dismissed, or the setting turned off). */
    fun stopReAlert(context: Context) {
        cancelAlarm(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun cancelAlarm(context: Context) {
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context)) }
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 60, Intent(context, MissedReAlertReceiver::class.java).setAction(ACTION_REALERT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val KEY_FOR = "for"
    private const val KEY_COUNT = "count"
    const val ACTION_REALERT = "app.parley.MISSED_REALERT"
}

/** The re-alert alarm (V3). Not exported. */
class MissedReAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MissedCallNotifier.ACTION_REALERT) return
        val pending = goAsync()
        context.container.scope.launch {
            try {
                MissedCallNotifier.onReAlertDue(context)
            } finally {
                pending.finish()
            }
        }
    }
}
