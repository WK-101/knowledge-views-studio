package app.parley.telecom

import android.telecom.Call
import android.telecom.TelecomManager
import app.parley.common.calls.SpeakerDefault
import app.parley.common.calls.SpeakerOnStart

/**
 * Settings › Calls › "Start calls on speaker" ([SpeakerOnStart]): decided once per call, as soon as it is answered or
 * dialled and Telecom has reported the audio routes for it; the Speaker button stays the user's after that.
 */
internal class SpeakerStart(private val live: LiveCalls, private val deps: () -> TelecomDependencies, private val ready: () -> Boolean) {
    fun decide() {
        if (!ready()) return
        val top = live.calls.filter { it.parent == null }
        if (top.isEmpty()) return
        val choice = runCatching { deps().speakerDefault() }.getOrDefault(SpeakerDefault.OFF)
        val audio = live.audio
        top.forEach { c ->
            val s = live.sessionOrNull(live.idOf(c))?.takeIf { !it.speakerDecided } ?: return@forEach
            val facts = facts(c, s, choice, audio, otherCall = top.any { it != c && mapState(it.stateCompat()) !in ENDING_STATES })
            when (if (facts == null) SpeakerOnStart.Step.LEAVE else SpeakerOnStart.step(facts)) {
                SpeakerOnStart.Step.WAIT -> Unit
                SpeakerOnStart.Step.LEAVE -> s.speakerDecided = true
                SpeakerOnStart.Step.TURN_ON -> {
                    s.speakerDecided = true
                    audio.routes.firstOrNull { it.type == RouteType.SPEAKER }?.let { live.requestRoute(it) }
                }
            }
        }
    }

    /** What [SpeakerOnStart] decides on for [c]; null once the call is ending (nothing left to decide). */
    private fun facts(c: Call, s: CallSession, choice: SpeakerDefault, audio: AudioUi, otherCall: Boolean): SpeakerOnStart.Facts? {
        val st = mapState(c.stateCompat())
        if (st in ENDING_STATES) return null
        val d = c.details
        val number = d.handle?.schemeSpecificPart
        val hidden = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || number.isNullOrBlank()
        val incoming = d.callDirection == Call.Details.DIRECTION_INCOMING
        // Routes known before this call existed (or only half reported: a current route, no list yet) can't decide.
        val routesKnown = s.routesReported && audio.current != null && audio.routes.isNotEmpty()
        return SpeakerOnStart.Facts(
            choice = choice,
            started = if (incoming) st == CallState.ACTIVE else st in DIAL_STATES,
            emergency = live.isEmergencyCall(c, number),
            savedCaller = when {
                s.info != null || s.savedPrivately || d.contactDisplayNameCompat() != null -> true
                hidden || s.lookupDone -> false
                else -> null
            },
            otherCall = otherCall,
            route = if (routesKnown) route(audio.current?.type) else SpeakerOnStart.Route.UNKNOWN,
            speakerAvailable = audio.routes.any { it.type == RouteType.SPEAKER },
            holdMode = s.holdModeSince != 0L,
            conference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE) || c.children.isNotEmpty(),
        )
    }

    private fun route(type: RouteType?): SpeakerOnStart.Route = when (type) {
        null -> SpeakerOnStart.Route.UNKNOWN
        RouteType.EARPIECE -> SpeakerOnStart.Route.EARPIECE
        RouteType.SPEAKER -> SpeakerOnStart.Route.SPEAKER
        else -> SpeakerOnStart.Route.HEADSET
    }

    private companion object {
        /** An outgoing call starts on the speaker while it is dialled (the ringback is heard there too). */
        val DIAL_STATES = setOf(CallState.DIALING, CallState.CONNECTING, CallState.ACTIVE)
    }
}
