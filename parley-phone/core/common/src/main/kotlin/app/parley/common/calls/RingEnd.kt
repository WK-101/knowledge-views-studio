package app.parley.common.calls

/**
 * How an incoming call's ringing ended, from what the call path recorded about it: which tone played, why Parley
 * kept it quiet and what became of the call. Pure, so the rules behind "Why did my phone ring, or not?" are tested
 * without a Telecom call.
 */
object RingEnd {
    /** Why Parley kept a call quiet; the app words it (a rule's own verdict is shown as it is). */
    enum class SilenceReason { QUOTA, RULES, IGNORED, OTHER }

    /** How the call left Telecom, as far as the ring facts care. */
    enum class Disconnect { ANSWERED_ELSEWHERE, REJECTED, OTHER }

    data class Facts(
        val silenced: Boolean,
        val quotaSilenced: Boolean,
        val ignoredByUser: Boolean,
        /** Screening blocked the call: rejected ([blockedReject]) or silenced. */
        val blocked: Boolean,
        val blockedReject: Boolean,
        /** The tone Parley played (and what it was), or null when Parley didn't play one. */
        val tonePlayed: Pair<RingtoneSource, String?>?,
        val connected: Boolean,
        val disconnect: Disconnect,
    )

    data class Result(val silence: SilenceReason?, val ringtone: RingtoneSource, val ringtoneDetail: String?, val outcome: RingOutcome)

    fun of(f: Facts): Result {
        val silence = when {
            !f.silenced -> null
            f.quotaSilenced -> SilenceReason.QUOTA
            f.blocked && !f.blockedReject -> SilenceReason.RULES
            f.ignoredByUser -> SilenceReason.IGNORED
            else -> SilenceReason.OTHER
        }
        val tone = when {
            f.blocked -> RingtoneSource.NONE to null
            // "Ignore" after the tone started: the tone that played still counts.
            f.tonePlayed != null -> f.tonePlayed
            silence != null && !f.ignoredByUser -> RingtoneSource.NONE to null
            else -> RingtoneSource.SYSTEM to null
        }
        val outcome = when {
            f.connected -> RingOutcome.ANSWERED
            f.blocked && f.blockedReject -> RingOutcome.BLOCKED
            f.disconnect == Disconnect.ANSWERED_ELSEWHERE -> RingOutcome.ANSWERED_ELSEWHERE
            f.disconnect == Disconnect.REJECTED -> RingOutcome.DECLINED
            else -> RingOutcome.MISSED
        }
        return Result(silence, tone.first, tone.second, outcome)
    }
}
