package app.parley.telecom

import android.content.res.Resources
import app.parley.common.spam.RepReason
import app.parley.common.spam.RepSignal
import app.parley.common.spam.Reputation

/** I2: the "Why?" of a sales-line tag in the app's language (the call screen, Recents and number history share it). */
object ReputationText {
    fun reasons(res: Resources, rep: Reputation): List<String> = rep.reasons.map { reason(res, it) }

    fun reason(res: Resources, r: RepReason): String {
        fun plural(id: Int) = res.getQuantityString(id, r.count, r.count)
        return when (r.signal) {
            RepSignal.SHORT_RINGS -> plural(R.plurals.rep_reason_short_rings)
            RepSignal.ALWAYS_DECLINED -> plural(R.plurals.rep_reason_always_declined)
            RepSignal.SHORT_HANGUPS -> plural(R.plurals.rep_reason_short_hangups)
            RepSignal.NEVER_ANSWERED -> plural(R.plurals.rep_reason_never_answered)
            RepSignal.NO_VOICEMAIL -> res.getString(R.string.rep_reason_no_voicemail)
            RepSignal.ODD_HOURS -> plural(R.plurals.rep_reason_odd_hours)
            RepSignal.RANGE_BURST -> plural(R.plurals.rep_reason_range_burst)
            RepSignal.RANGE_UNANSWERED -> plural(R.plurals.rep_reason_range_unanswered)
            RepSignal.RANGE_BLOCKED -> plural(R.plurals.rep_reason_range_blocked)
        }
    }
}
