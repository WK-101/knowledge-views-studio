package app.parley.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import app.parley.common.Verification
import app.parley.common.calls.EndCode

/**
 * What [CallManager]'s collaborators may see of the live calls. They hold no calls or sessions of their own: the
 * registry stays in [CallManager], which hands them this view.
 */
internal interface LiveCalls {
    /** Every call Telecom reported, in the order it did (children of a conference are under their parent). */
    val calls: List<Call>

    /** The audio routes Telecom last reported. */
    val audio: AudioUi

    fun idOf(call: Call): String

    /** A call or a conference's child by id; null once it left Telecom. */
    fun find(id: String): Call?

    /** The call's session, made when first asked. */
    fun session(id: String): CallSession

    fun sessionOrNull(id: String): CallSession?

    fun isEmergencyCall(call: Call, number: String?): Boolean

    /** Asks Telecom to move the audio (tests listen instead). */
    fun requestRoute(route: AudioRoute)

    /** Sends the calls' current state to the screens and observers. */
    fun publish()
}

internal val DIALLING_STATES = setOf(CallState.NEW, CallState.DIALING, CallState.CONNECTING)
internal val FRONT_STATES = DIALLING_STATES + CallState.ACTIVE
internal val BUSY_STATES = FRONT_STATES + setOf(CallState.RINGING, CallState.SELECT_ACCOUNT)
internal val ANSWERED_STATES = setOf(CallState.ACTIVE, CallState.HOLDING)
internal val ENDING_STATES = setOf(CallState.DISCONNECTING, CallState.DISCONNECTED)

@Suppress("DEPRECATION") // Call.getState is the only one before Android 12.
internal fun Call.stateCompat(): Int = if (Build.VERSION.SDK_INT >= 31) details.state else state

internal fun Call.Details.contactDisplayNameCompat(): String? =
    if (Build.VERSION.SDK_INT >= 30) contactDisplayName?.takeIf { it.isNotBlank() } else null

internal fun mapState(s: Int): CallState = when (s) {
    Call.STATE_NEW -> CallState.NEW
    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallState.RINGING
    Call.STATE_DIALING -> CallState.DIALING
    Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> CallState.CONNECTING
    Call.STATE_ACTIVE -> CallState.ACTIVE
    Call.STATE_HOLDING -> CallState.HOLDING
    Call.STATE_DISCONNECTING -> CallState.DISCONNECTING
    Call.STATE_DISCONNECTED -> CallState.DISCONNECTED
    Call.STATE_SELECT_PHONE_ACCOUNT -> CallState.SELECT_ACCOUNT
    else -> CallState.OTHER
}

internal fun verificationOf(call: Call): Verification = when {
    Build.VERSION.SDK_INT < 30 -> Verification.NOT_VERIFIED
    call.details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_PASSED -> Verification.PASSED
    call.details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED -> Verification.FAILED
    else -> Verification.NOT_VERIFIED
}

/** Before Telecom picks the account, the one Parley asked for is in the intent extras. */
@Suppress("DEPRECATION") // The typed getter needs Android 13; this runs on older phones too.
internal fun requestedAccount(d: Call.Details): PhoneAccountHandle? = try {
    if (Build.VERSION.SDK_INT >= 33) d.intentExtras?.getParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle::class.java)
    else d.intentExtras?.getParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE)
} catch (_: Exception) {
    null
}

/** An incoming call offered with video (answered audio-only all the same). */
internal fun incomingVideo(d: Call.Details): Boolean = d.callDirection == Call.Details.DIRECTION_INCOMING && VideoProfile.isVideo(d.videoState)

internal fun endCode(c: DisconnectCause?): EndCode? = when (c?.code) {
    null -> null
    DisconnectCause.LOCAL -> EndCode.LOCAL
    DisconnectCause.REMOTE -> EndCode.REMOTE
    DisconnectCause.BUSY -> EndCode.BUSY
    DisconnectCause.ERROR, DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> EndCode.ERROR
    DisconnectCause.RESTRICTED -> EndCode.RESTRICTED
    DisconnectCause.CANCELED -> EndCode.CANCELED
    DisconnectCause.MISSED -> EndCode.MISSED
    DisconnectCause.REJECTED -> EndCode.REJECTED
    DisconnectCause.OTHER -> EndCode.OTHER
    DisconnectCause.UNKNOWN -> EndCode.UNKNOWN
    else -> null // answered elsewhere, pulled: not a failure
}
