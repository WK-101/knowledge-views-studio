package app.parley.telecom

import android.content.Context
import android.telecom.Call
import android.telecom.DisconnectCause
import android.text.format.DateUtils
import app.parley.common.RangThrough
import app.parley.common.RangThroughKind
import app.parley.common.calls.DropKind
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.FailureKind

/**
 * The words the call screen shows about a call: why it rang through, why it dropped, failed or ended. In the user's
 * language; null before the first call gave Parley a [context].
 */
internal class CallTexts(private val context: () -> Context?) {
    fun str(res: Int): String? = context()?.getString(res)

    /** P1: "Rang through: called twice in 3 min", "Rang through: expecting a call", … */
    fun rangThrough(r: RangThrough?): String? {
        val ctx = context()
        if (r == null || ctx == null) return null
        val res = ctx.resources
        return when (r.kind) {
            RangThroughKind.REPEAT_CALLER ->
                if (r.calls <= 2) res.getString(R.string.call_rang_repeat_twice, r.minutes) else res.getString(R.string.call_rang_repeat, r.calls, r.minutes)
            RangThroughKind.EXPECTING -> res.getString(expecting(r.expected))
            RangThroughKind.ALLOW_RULE -> allowRule(ctx, r)
            RangThroughKind.LABEL -> r.name?.let { res.getString(R.string.call_rang_label, it) } ?: res.getString(R.string.call_rang_allowed)
            RangThroughKind.DIALLED -> res.getString(R.string.call_rang_dialled)
            RangThroughKind.ANSWERED -> res.getString(R.string.call_rang_answered)
        }
    }

    /** I7: what turned "Expecting a call" on; the note's name shows only while unlocked (see [expectedNote]). */
    private fun expecting(source: ExpectedSource?): Int = when (source) {
        ExpectedSource.NOTE -> R.string.call_rang_expecting_notes
        ExpectedSource.TO_CALL -> R.string.call_rang_expecting_to_call
        ExpectedSource.DELIVERY_QR -> R.string.call_rang_expecting_delivery
        null -> R.string.call_rang_expecting
    }

    /** I7: "Rang through: expecting a call (note on Dentist)", for the unlocked screen only; null for anything else. */
    fun expectedNote(r: RangThrough?): String? {
        val ctx = context()
        if (r?.kind != RangThroughKind.EXPECTING || r.expected != ExpectedSource.NOTE || ctx == null) return null
        val name = r.name?.takeIf { it.isNotBlank() } ?: return null
        return ctx.getString(R.string.call_rang_expecting_note_on, name)
    }

    /** "Rang through: allowed until 18:40" for a temporary rule, else the rule's name. */
    private fun allowRule(ctx: Context, r: RangThrough): String {
        val res = ctx.resources
        val until = r.until
        val name = r.name
        return when {
            until != null -> {
                val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(until)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY
                res.getString(R.string.call_rang_until, DateUtils.formatDateTime(ctx, until, flags))
            }
            name != null -> res.getString(R.string.call_rang_rule, name)
            else -> res.getString(R.string.call_rang_allowed)
        }
    }

    /** "Lost signal · Wi-Fi calling · Work": why a call dropped and what it was on. */
    fun drop(kind: DropKind, s: CallSession): String? {
        val reason = when (kind) {
            DropKind.LOST_SIGNAL -> str(R.string.call_drop_lost_signal)
            DropKind.WIFI_LOST -> str(R.string.call_drop_wifi_lost)
            DropKind.NO_SERVICE -> str(R.string.call_drop_no_service)
            DropKind.NETWORK -> str(R.string.call_drop_network)
        }
        val wifi = str(R.string.incall_wifi_calling)?.takeIf { s.wifiSeen && kind != DropKind.WIFI_LOST }
        return listOfNotNull(reason, wifi, s.simLabel).joinToString(str(R.string.tc_separator) ?: " · ").ifBlank { null }
    }

    fun disconnect(c: DisconnectCause): String? = when (c.code) {
        DisconnectCause.LOCAL, DisconnectCause.REMOTE -> null
        DisconnectCause.BUSY -> str(R.string.call_disconnect_busy)
        DisconnectCause.MISSED -> str(R.string.call_disconnect_missed)
        DisconnectCause.REJECTED -> str(R.string.call_disconnect_declined)
        DisconnectCause.ERROR -> c.label?.toString()?.ifBlank { null } ?: str(R.string.call_disconnect_failed)
        else -> c.label?.toString()?.ifBlank { null }
    }

    fun failure(f: FailureKind, call: Call): String? = when (f) {
        FailureKind.AIRPLANE_MODE -> str(R.string.call_failed_airplane)
        FailureKind.NO_SIM_SELECTED -> str(R.string.call_failed_no_sim)
        FailureKind.BUSY -> str(R.string.call_disconnect_busy)
        FailureKind.OTHER -> call.details.disconnectCause?.let { c -> (c.description ?: c.label)?.toString()?.takeIf { it.isNotBlank() } }
            ?: str(R.string.call_failed_generic)
    }
}
