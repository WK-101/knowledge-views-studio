package app.parley.telecom

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import app.parley.common.calls.CallHandOff
import app.parley.common.calls.CallSubject
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.EmergencyPolicy.Safeguard
import app.parley.common.calls.NetworkName

/**
 * What the call screen, notifications and observers see of a call ([CallUi]), built from Telecom's details and what
 * Parley knows in its [CallSession]; and the facts a call reports while it goes on, kept for when it ends.
 */
@Suppress("LongParameterList") // The collaborators whose answers the screen shows.
internal class CallUiMapper(
    private val live: LiveCalls,
    private val texts: CallTexts,
    private val accounts: CallAccounts,
    private val emergency: EmergencyCalls,
    private val drive: DriveGate,
    private val heldSince: (String) -> Long,
    private val context: () -> Context?,
) {
    fun toUi(call: Call): CallUi {
        val d = call.details
        val id = live.idOf(call)
        val s = live.sessionOrNull(id) ?: CallSession(id)
        val found = s.info
        val number = d.handle?.schemeSpecificPart
        val hidden = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val caps = d.callCapabilities
        fun can(c: Int) = (caps and c) != 0
        val conferenceable = call.conferenceableCalls.isNotEmpty()
        val state = mapState(call.stateCompat())
        // Before Telecom picks the account, the one Parley asked for is in the intent extras.
        val account = d.accountHandle ?: requestedAccount(d)
        return CallUi(
            id = id,
            state = state,
            number = number,
            hidden = hidden,
            name = found?.name ?: d.contactDisplayNameCompat() ?: d.callerDisplayName?.takeIf { it.isNotBlank() },
            savedCaller = savedCaller(found, d),
            label = found?.label,
            photoUri = found?.photoUri,
            backgroundUri = found?.backgroundUri,
            contactId = found?.contactId,
            lookupKey = found?.lookupKey,
            incoming = d.callDirection == Call.Details.DIRECTION_INCOMING,
            connectTimeMillis = d.connectTimeMillis,
            isConference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            children = call.children.map { toUi(it) },
            canHold = can(Call.Details.CAPABILITY_HOLD),
            canMerge = can(Call.Details.CAPABILITY_MERGE_CONFERENCE) || conferenceable,
            canSwap = can(Call.Details.CAPABILITY_SWAP_CONFERENCE),
            canMute = can(Call.Details.CAPABILITY_MUTE),
            canSeparate = can(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE),
            canDisconnectChild = can(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE),
            canRespondViaText = can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT),
            accountLabel = accounts.label(account),
            verification = verificationOf(call),
            disconnectReason = endedReason(s) ?: d.disconnectCause?.let { texts.disconnect(it) },
            postDialWait = s.postDial,
            silenced = s.silenced,
            silenceReason = silenceReasonOf(s),
            accountId = account?.id,
            heldSinceElapsed = heldSince(id),
            isEmergency = emergency.isCall(call, number),
            note = found?.note,
            lastCall = found?.lastCall,
            subtitle = found?.subtitle,
            context = found?.context,
            memory = found?.memory,
            memoryPrompt = found?.memoryPrompt == true,
            unknown = s.unknownCaller,
            location = if (s.unknownCaller) s.location?.ifEmpty { null } else null,
            verdict = s.outcome?.verdict,
            verdictWarn = s.outcome?.warn == true,
            noContact = s.noContact && found == null,
            accountNumber = accounts.number(account),
            fallbackTitle = texts.str(if (hidden) R.string.call_private_number else R.string.call_unknown).orEmpty(),
            systemSilenced = s.systemSilenced,
            blockingDecline = s.blockingDecline,
            hdAudio = d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO),
            wifi = d.hasProperty(Call.Details.PROPERTY_WIFI),
            videoAsVoice = s.videoOffered || incomingVideo(d),
            pronouns = found?.pronouns,
            nativeName = found?.nativeName,
            autoAnswerAt = if (state == CallState.RINGING) s.autoAnswerAt else 0,
            subject = s.subject,
            urgent = s.urgent,
            holdModeSince = s.holdModeSince,
            reputation = reputationTag(s, call, number, hidden),
            // Only ever set for a number the lookup found no contact for.
            numberMemory = s.numberMemory,
            driving = drivingNow(state),
            handOff = handOffFacts(call),
            neverCallsYou = neverCalls(s, call, number, hidden),
            networkName = networkNameOf(s, found, d, hidden),
            networkNameUnderSaved = networkNameUnderSaved(s, found, hidden),
        ).withRangThrough(s)
    }

    /** Why the call ended, when Parley ended it on purpose (a limit, sent to another number). */
    private fun endedReason(s: CallSession): String? = when {
        s.endedByLimit -> texts.str(R.string.call_limit_reached)
        s.handedOff == HandOff.DEFLECTED -> texts.str(R.string.handoff_ended_deflected)
        else -> null
    }

    /** The network's name, for a caller nobody saved (nor a private contact, even one discreet mode hides). */
    private fun networkNameOf(s: CallSession, found: CallerDisplay?, d: Call.Details, hidden: Boolean): String? =
        s.networkName.takeIf { !hidden && !savedCaller(found, d) && !s.savedPrivately }

    /**
     * The network's name under a saved caller's name, when the app allowed it for this caller ([CallerDisplay.networkNameUnder]:
     * the setting is on and the name may show) and it is a different name. The lock screen masks it with the name.
     */
    private fun networkNameUnderSaved(s: CallSession, found: CallerDisplay?, hidden: Boolean): String? =
        found?.takeIf { it.networkNameUnder && !hidden && !s.savedPrivately }
            ?.let { NetworkName.underSaved(it.name, s.networkName, NetworkName.Gate(enabled = true)) }

    /** The caller is a contact or a private contact (found by the lookup, or named by Telecom from the contacts). */
    private fun savedCaller(found: CallerDisplay?, d: Call.Details): Boolean = found != null || d.contactDisplayNameCompat() != null

    /** Why a call rings silently when it isn't a blocking rule: an allowance used up, or the drive profile (I11). */
    private fun silenceReasonOf(s: CallSession): String? = when {
        s.quotaSilenced -> texts.str(R.string.call_silenced_quota)
        drive.silencedHere(s.id) -> texts.str(R.string.drive_silenced)
        else -> null
    }

    /** I11: "Drive profile on" for a live call while the marked car is connected. */
    private fun drivingNow(state: CallState): Boolean =
        state != CallState.DISCONNECTED && state != CallState.DISCONNECTING && context()?.let { drive.driving(it) } == true

    /** "This number never calls you": only for an organisation the lookup found; never an emergency call. */
    private fun neverCalls(s: CallSession, call: Call, number: String?, hidden: Boolean) =
        s.neverCallsYou && s.info != null && !hidden && !emergency.isCall(call, number)

    /** I2's tag, for an unknown, visible, non-emergency caller only. */
    private fun reputationTag(s: CallSession, call: Call, number: String?, hidden: Boolean) =
        s.outcome?.reputation?.takeIf { !hidden && s.info == null && !emergency.isCall(call, number) }

    /** P1 while it rings; the "rang through" line says it better than the quiet "Allowed by …" tag (a warning stays). */
    private fun CallUi.withRangThrough(s: CallSession): CallUi {
        if (state != CallState.RINGING || s.silenced) return this
        val text = texts.rangThrough(s.outcome?.rangThrough) ?: return this
        return copy(rangThrough = text, rangThroughUnlocked = texts.expectedNote(s.outcome?.rangThrough), verdict = verdict.takeIf { verdictWarn })
    }

    /**
     * What a call reports while it goes on, kept for its facts: Wi-Fi calling, HD voice and the SIM while connected
     * (Telecom clears them as the call ends), and the caller's subject and priority, which some networks send late.
     */
    fun noteFacts(c: Call, s: CallSession, st: CallState) {
        val d = c.details
        // The network's caller name, for an incoming call: kept as the latest one sent.
        if (d.callDirection == Call.Details.DIRECTION_INCOMING) {
            NetworkName.clean(runCatching { d.callerDisplayName }.getOrNull(), runCatching { d.callerDisplayNamePresentation }.getOrDefault(0))
                ?.let { s.networkName = it }
        }
        // Kept once seen: answering audio-only turns the call's video state off.
        if (incomingVideo(d)) s.videoOffered = true
        if (st == CallState.ACTIVE || st == CallState.HOLDING) {
            if (d.hasProperty(Call.Details.PROPERTY_WIFI)) s.wifiSeen = true
            if (d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO)) s.hdSeen = true
            if (s.simLabel == null) s.simLabel = accounts.label(d.accountHandle)
        }
        if (s.subject == null) s.subject = CallSubject.clean(runCatching { d.extras?.getCharSequence(TelecomManager.EXTRA_CALL_SUBJECT) }.getOrNull())
            ?: CallSubject.clean(runCatching { d.intentExtras?.getCharSequence(TelecomManager.EXTRA_CALL_SUBJECT) }.getOrNull())
        if (!s.urgent && Build.VERSION.SDK_INT >= 31) {
            s.urgent = runCatching { d.extras?.getInt(TelecomManager.EXTRA_PRIORITY, TelecomManager.PRIORITY_NORMAL) == TelecomManager.PRIORITY_URGENT }
                .getOrDefault(false)
        }
    }

    /** What the network lets this ringing call do ("Send to another number"). */
    fun handOffFacts(c: Call): CallHandOff.Facts {
        val d = c.details
        val incoming = d.callDirection == Call.Details.DIRECTION_INCOMING
        return CallHandOff.Facts(
            ringing = mapState(c.stateCompat()) == CallState.RINGING,
            // During the emergency window too: the operator's call-back often comes from a hidden or unknown number.
            emergency = EmergencyPolicy.bypasses(Safeguard.HAND_OFF, emergency.facts(c, d.handle?.schemeSpecificPart, incoming)),
            conference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            canDeflect = (d.callCapabilities and Call.Details.CAPABILITY_SUPPORT_DEFLECT) != 0,
        )
    }
}
