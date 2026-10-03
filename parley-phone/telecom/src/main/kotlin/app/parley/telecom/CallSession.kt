package app.parley.telecom

import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.MenuPress
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

    /** The caller lookup has finished (found someone or not), or there was nothing to look up (a hidden number). */
    var lookupDone = false

    /** A private contact the lookup didn't show (discreet mode): still a saved caller, never "unknown" to the speaker. */
    var savedPrivately = false

    /** I1: what Parley remembers about a number that isn't a contact, once looked up. */
    var numberMemory: NumberMemoryLine? = null

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

    /** I6: the digits sent in this call (outgoing, once connected), for menu memory when it ends. */
    val menuPresses = ArrayList<MenuPress>()

    // ---- Call facts (L2, L10) ----

    /** Wall-clock time the call was first seen (rang or was placed), for its quality facts. */
    var startedAt: Long = 0

    /** The subject the caller sent with the call, cleaned (null when none). */
    var subject: String? = null

    /** The caller marked the call urgent (Call Composer priority). */
    var urgent = false

    /** Went over Wi-Fi calling / was in HD voice at some point while connected (the flags clear as the call ends). */
    var wifiSeen = false
    var hdSeen = false

    /** The call came in as a video call; Parley answers it audio-only, and the call screen says so. */
    var videoOffered = false

    /** The SIM's name, remembered while connected. */
    var simLabel: String? = null

    // ---- Hold mode (I10) ----

    /** `elapsedRealtime` when "I'm on hold" started, or 0. */
    var holdModeSince = 0L

    /** The audio route before hold mode turned the speaker on, restored when it ends. */
    var routeBeforeHold: AudioRoute? = null

    // ---- Speaker on start, sending a call on ----

    /** "Start calls on speaker" has decided for this call (once: after that the Speaker button is the user's). */
    var speakerDecided = false

    /** Telecom has reported the audio routes since this call was added (earlier routes may be a headset long gone). */
    var routesReported = false

    /** The call was handed on (sent to another number): how it ended. */
    var handedOff: HandOff? = null
}

/** How a call was handed to someone else ([CallManager.deflect]). */
internal enum class HandOff { DEFLECTED }
