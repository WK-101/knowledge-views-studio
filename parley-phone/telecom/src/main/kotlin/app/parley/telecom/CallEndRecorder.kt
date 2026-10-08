package app.parley.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.parley.common.BlockAction
import app.parley.common.Decision
import app.parley.common.calls.CallQualityCodec
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.DropKind
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.EndFacts
import app.parley.common.calls.FailureKind
import app.parley.common.calls.NetworkName
import app.parley.common.calls.RingEnd
import app.parley.common.calls.RingFacts

/**
 * What Parley keeps when a call ends: its end facts (for the failure banner), its quality facts, how it rang, the
 * allowance ledger and the emergency window. Each report fails on its own without touching the call path.
 */
internal class CallEndRecorder(
    private val deps: () -> TelecomDependencies,
    private val context: () -> Context?,
    private val texts: CallTexts,
    private val emergency: EmergencyCalls,
) {
    /** What's known about a call that just left Telecom; [lastLiveState] is the last state the book saw it in. */
    fun endFacts(call: Call, ended: CallUi, s: CallSession, lastLiveState: CallState?): EndFacts = EndFacts(
        outgoing = !ended.incoming,
        connected = ended.connectTimeMillis > 0,
        code = endCode(call.details.disconnectCause),
        endedInSimPicker = lastLiveState == CallState.SELECT_ACCOUNT,
        userEnded = s.userEnded,
        airplaneMode = context()?.let { c ->
            runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 }.getOrDefault(false)
        } ?: false,
        emergency = ended.isEmergency,
        hasNumber = !ended.hidden && !ended.number.isNullOrBlank(),
    )

    /** L2: the call's quality facts, for the number history and the quality diary (returned too). Never for emergency calls. */
    fun quality(call: Call, ended: CallUi, s: CallSession, drop: DropKind?, cause: DisconnectCause?): CallQualityFacts? {
        if (ended.isEmergency || ended.isConference || s.startedAt == 0L) return null
        val now = System.currentTimeMillis()
        val talked = if (ended.connectTimeMillis > 0) ((now - ended.connectTimeMillis) / 1000).coerceAtLeast(0) else 0
        val holding = if (s.holdModeSince > 0) (SystemClock.elapsedRealtime() - s.holdModeSince).coerceAtLeast(0) else 0
        // A failed outgoing call keeps its cause and how quickly it failed too: a number that's no longer in service.
        val failed = !ended.incoming && ended.connectTimeMillis <= 0 && ended.failure != null
        val facts = CallQualityFacts(
            startedAt = s.startedAt,
            incoming = ended.incoming,
            durationSec = talked,
            connected = ended.connectTimeMillis > 0,
            sim = s.simLabel ?: ended.accountLabel,
            wifi = s.wifiSeen,
            hd = s.hdSeen,
            end = endCode(cause),
            cause = CallQualityCodec.causeName(cause?.reason)?.takeIf { drop != null || failed },
            drop = drop,
            subject = s.subject,
            simId = ended.accountId,
            endedAfterSec = if (failed) ((now - s.startedAt) / 1000).coerceAtLeast(0) else null,
            holdSec = (s.holdModeTotalMs + holding) / 1000,
            // The radar only trusts "unassigned" for a number in national form when it knows the phone was at home.
            roaming = roaming(call, failed),
            offline = offline(ended.failure, failed),
        )
        runCatching { deps().onCallQuality(ended.number.takeIf { !ended.hidden }, facts) }
        // A case file keeps the call with its hold time and, for a call you placed, the menu keys (minus anything secret).
        val number = ended.number?.takeIf { !ended.hidden && it.isNotBlank() } ?: return facts
        val keys = if (ended.incoming) emptyList() else s.menuPresses.toList()
        runCatching { deps().onCaseCall(number, ended.accountId, facts, keys) }
        return facts
    }

    /** Placed in airplane mode or without a SIM chosen: the phone's side of a failure. */
    private fun offline(f: FailureKind?, failed: Boolean): Boolean = failed && (f == FailureKind.AIRPLANE_MODE || f == FailureKind.NO_SIM_SELECTED)

    /**
     * Whether the network of the call's SIM is roaming now, or null when that can't be told: the SIM isn't found (on
     * Android 10 a dual-SIM phone can't map a call's account to its SIM, so only the default one's roaming counts,
     * and only to say yes). Only for a [failed] call. No permission beyond reading the phone state is needed: Parley
     * is the phone app and holds it, and a SecurityException all the same is caught (unknown).
     */
    @SuppressLint("MissingPermission")
    private fun roaming(call: Call, failed: Boolean): Boolean? {
        if (!failed) return null
        val c = context() ?: return null
        return runCatching {
            val tm = c.getSystemService(TelephonyManager::class.java) ?: return@runCatching null
            val handle = call.details.accountHandle
            val sub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && handle != null) {
                tm.getSubscriptionId(handle).takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            } else {
                null
            }
            if (sub != null) tm.createForSubscriptionId(sub).isNetworkRoaming else tm.isNetworkRoaming.takeIf { it }
        }.getOrNull()
    }

    /**
     * After the call left: how it rang (for "Why did my phone ring?"), the emergency window from the end of the call,
     * the allowance ledger (every connected call except emergency ones, never limited, so never counted), and the end
     * itself.
     */
    fun ended(call: Call, ended: CallUi, s: CallSession) {
        s.ringStartedAt?.let { started ->
            val connected = ended.connectTimeMillis > 0
            val rang = ((if (connected) ended.connectTimeMillis else System.currentTimeMillis()) - started).coerceAtLeast(0)
            runCatching { deps().onRingFinished(ended.number, started, rang, connected) }
            s.ringFacts?.let { f ->
                runCatching { deps().onRingFacts(ended.number.takeIf { !ended.hidden }, finishRingFacts(s, call, f, rang, connected)) }
            }
        }
        // The window started when the call was added; it runs for its full length from the end of the call too.
        val ctx = context()
        if (ctx != null && EmergencyPolicy.startsWindow(emergency.facts(call, ended.number, ended.incoming), ended.incoming)) {
            runCatching { ScreeningGuard.noteEmergencyCall(ctx) }
        }
        if (ended.connectTimeMillis > 0 && !ended.isEmergency && !ended.isConference) {
            val talkedSec = ((System.currentTimeMillis() - ended.connectTimeMillis) / 1000).coerceAtLeast(0)
            runCatching {
                deps().onCallUsage(ended.number.takeIf { !ended.hidden }, call.details.accountHandle?.id, ended.incoming, ended.connectTimeMillis, talkedSec)
            }
        }
        rememberNetworkName(call, ended, s)
        runCatching { deps().onCallEnded(ended.number, ended.incoming, ended.connectTimeMillis) }
    }

    /**
     * The name the network sent with an incoming call (answered, missed, declined or blocked by Parley), for Recents and
     * the number's page afterwards: the call log has no place for it. The app keeps it only while "Remember names from the
     * network" is on (for an unsaved number, a contact's or an archived contact's), never for a private contact's.
     */
    private fun rememberNetworkName(call: Call, ended: CallUi, s: CallSession) {
        val number = ended.number?.takeIf { ended.incoming && !ended.hidden && it.isNotBlank() && !ended.isEmergency } ?: return
        val d = call.details
        val name = s.networkName
            ?: NetworkName.clean(runCatching { d.callerDisplayName }.getOrNull(), runCatching { d.callerDisplayNamePresentation }.getOrDefault(0))
            ?: return
        val at = s.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        runCatching { deps().onNetworkName(number, name, d.accountHandle?.id ?: ended.accountId, at) }
    }

    /**
     * Completes the ring facts captured when the call started ringing: which tone played, whether Parley kept it
     * quiet and why, and how the call ended (the rules are [RingEnd]'s).
     */
    private fun finishRingFacts(s: CallSession, call: Call, f: RingFacts, rang: Long, connected: Boolean): RingFacts {
        val o = s.outcome
        val block = o?.decision as? Decision.Block
        val cause = call.details.disconnectCause?.code
        val end = RingEnd.of(
            RingEnd.Facts(
                silenced = s.silenced,
                quotaSilenced = s.quotaSilenced,
                ignoredByUser = s.ignoredByUser,
                blocked = block != null,
                blockedReject = block?.action == BlockAction.REJECT,
                tonePlayed = s.tonePlayed,
                connected = connected,
                disconnect = when (cause) {
                    DisconnectCause.ANSWERED_ELSEWHERE, DisconnectCause.CALL_PULLED -> RingEnd.Disconnect.ANSWERED_ELSEWHERE
                    DisconnectCause.REJECTED -> RingEnd.Disconnect.REJECTED
                    else -> RingEnd.Disconnect.OTHER
                },
            ),
        )
        val silencedBy = when (end.silence) {
            null -> null
            RingEnd.SilenceReason.QUOTA -> texts.str(R.string.call_silenced_quota)
            RingEnd.SilenceReason.RULES -> o?.verdict?.takeIf { it.isNotBlank() } ?: texts.str(R.string.call_silenced_rules)
            RingEnd.SilenceReason.IGNORED -> texts.str(R.string.call_silenced_ignored)
            RingEnd.SilenceReason.OTHER -> texts.str(R.string.call_silenced)
        }
        val route = if (connected) s.answeredRoute else null
        return f.copy(
            ringMillis = rang,
            ringtone = end.ringtone,
            ringtoneDetail = end.ringtoneDetail,
            silencedBy = silencedBy,
            ringLoud = s.loud,
            outcome = end.outcome,
            answeredRoute = route?.first,
            answeredDevice = route?.second,
        )
    }
}
