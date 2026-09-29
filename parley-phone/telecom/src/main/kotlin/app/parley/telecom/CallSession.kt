package app.parley.telecom

import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingtoneSource

/**
 * Everything the call path tracks about one call, in one object. [CallManager] keeps one per call id and drops it as
 * a whole when the call leaves Telecom, so a new flag can't be forgotten in the clean-up. Main thread only.
 */
internal class CallSession(val id: String) {
    /** What the caller lookup found (null: not yet, or nobody). */
    var info: CallerDisplay? = null

    /** The lookup finished and found no contact or private contact (the post-call card offers Save and Block). */
    var noContact = false

    /** An incoming call from someone who isn't a contact (unknown-caller ringtone, "where from"). */
    var unknownCaller = false

    /** "Where is this number from", once the geocoder answered. */
    var location: String? = null

    // ---- Screening ----

    /**
     * Screening hasn't answered yet: nothing is shown for the call meanwhile (at most
     * [ScreeningCoordinator.SCREEN_TIMEOUT_MS], then it fails open and rings).
     */
    var screening = false

    /** The add → first notification trace section is open. */
    var noticeTraced = false

    /** What screening decided: the verdict for the caller card and how to ring. */
    var outcome: ScreenOutcome? = null

    /** "Block & decline" is still writing the rule (no answering from Parley meanwhile). */
    var blockingDecline = false

    // ---- Ringing ----

    /** Wall-clock time the call started ringing (incoming calls only). */
    var ringStartedAt: Long? = null

    /** The ringer's state when the call started ringing, completed when it ends. */
    var ringFacts: RingFacts? = null

    /** Parley keeps this call quiet (screening, allowance, Ignore, Block & decline). */
    var silenced = false

    /** Quiet because the caller's allowance is used up. */
    var quotaSilenced = false

    /** The user pressed Ignore. */
    var ignoredByUser = false

    /** The user silenced it with a hardware key: the spoken name stops too. */
    var systemSilenced = false

    /** Rang at full volume ("Ring loud"). */
    var loud = false

    /** When it is answered automatically (`elapsedRealtime`, 0: not armed), and whether the user cancelled that. */
    var autoAnswerAt: Long = 0
    var autoAnswerCancelled = false

    /** The tone Parley played for it, and what that tone was. */
    var tonePlayed: Pair<RingtoneSource, String?>? = null

    /** Where the audio went when it was answered (read again once the route settles). */
    var answeredRoute: Pair<AnswerRoute, String?>? = null

    // ---- The user's actions and how it ended ----

    /** The user ended or cancelled it (never a "failure"). */
    var userEnded = false

    /** Answered from Parley (the answer buzz confirmed it, so no connect buzz). */
    var answeredByUser = false

    /** Ended because its time limit ran out. */
    var endedByLimit = false

    /** Post-dial digits waiting for the user ("Send 1234?"). */
    var postDial: String? = null

    /** The token of the DTMF tone playing now (a stop for another key's tone never touches it). */
    var dtmfToken: Long? = null
}
