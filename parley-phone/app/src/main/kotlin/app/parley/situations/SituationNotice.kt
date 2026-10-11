package app.parley.situations

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.Schedule
import app.parley.common.situations.Situations
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.situations.SituationsController
import app.parley.work.PrivateNotice
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The silent ongoing notice while the Situation on now lets only some people ring ("Meeting is on · Others ring
 * silently until 11:00", with Turn off): the home screen's line and the tile are easy to forget outside Parley, and a
 * forgotten Meeting quietly sends the school and the doctor to missed calls. It makes no sound and has no badge; on the
 * lock screen it reads only "Situation on" (a Situation's own name can say where you are).
 */
object SituationNotice {
    const val ACTION_TURN_OFF = "app.parley.SITUATION_TURN_OFF"

    /** Shows, updates or takes away the notice for the Situation on now. Called after every change. */
    fun update(context: Context, sit: SituationsController) {
        val on = sit.active
        if (!Situations.silencesAnyone(on) || on == null) {
            cancel(context)
            return
        }
        val name = SituationTriggers.name(context, on)
        val state = sit.state.value
        val text = when (val end = Situations.noticeEnd(state, on, state.until?.let(::minuteOfDay))) {
            is Situations.NoticeEnd.At -> context.getString(R.string.sit_notice_text_until, Schedule.hm(end.minute))
            Situations.NoticeEnd.WhenTurnedOff -> context.getString(R.string.sit_notice_text)
            Situations.NoticeEnd.WhileTriggered -> context.getString(R.string.sit_notice_text_while)
        }
        val open = PrivateNotice.route(context, NotificationRequests.SITUATION_OPEN, IntentRoutes.ACTION_OPEN_SITUATIONS)
        val off = PendingIntent.getBroadcast(
            context, NotificationRequests.SITUATION_TURN_OFF,
            Intent(context, SituationNoticeReceiver::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = PrivateNotice.builder(
            context, NotificationChannels.SITUATION, R.drawable.ic_tile_situation,
            context.getString(R.string.sit_chip_on, name), context.getString(R.string.sit_notice_channel), text, open,
        )
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, context.getString(R.string.pin_off_confirm), off)
        PrivateNotice.post(context, NotificationIds.TAG_SITUATION, NotificationIds.SITUATION_ID, b)
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NotificationIds.TAG_SITUATION, NotificationIds.SITUATION_ID) }
    }

    private fun minuteOfDay(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
}

/**
 * Turn off on the Situation notice: puts back what was set before it, as the home screen's Turn off does. Not
 * exported; only Parley's own PendingIntent reaches it. Nothing about a contact changes, so it needs no unlock.
 */
class SituationNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SituationNotice.ACTION_TURN_OFF) return
        val app = context.applicationContext
        val done = goAsync()
        val c = app.container
        c.scope.launch {
            try {
                suspendRunCatching { c.situations.turnOff() }
                // Nothing was on any more (turned off elsewhere): the notice still goes.
                SituationNotice.update(app, c.situations)
            } finally {
                done.finish()
            }
        }
    }
}
