package com.wkhan.hexis.bridge

import kotlinx.serialization.Serializable

/** Protocol-level constants for the Hexis Bridge transport spine. */
object BridgeProtocol {
    const val VERSION = 1

    /** Service action a provider (addon) exposes so the core can discover it via queryIntentServices. */
    const val PROVIDER_ACTION = "com.wkhan.hexis.bridge.PROVIDER"

    /** `<meta-data>` key on the provider service: comma-separated capability ids it implements. */
    const val META_CAPABILITIES = "com.wkhan.hexis.bridge.capabilities"

    /** `<meta-data>` key on the provider service: integer protocol version it was built against. */
    const val META_PROTOCOL_VERSION = "com.wkhan.hexis.bridge.protocolVersion"
}

/**
 * How the core should treat a payload. NO_PERSIST instructs a peer to hold the data in memory only
 * (never write it to disk) — used, for example, for the ephemeral vocabulary hints the core sends
 * a voice addon.
 */
@Serializable
enum class Sensitivity { NORMAL, SENSITIVE, NO_PERSIST }

@Serializable
enum class BridgeErrorType {
    UNAUTHENTICATED,
    UNAUTHORIZED,
    UNSUPPORTED,
    RATE_LIMITED,
    UNAVAILABLE,
    BAD_REQUEST,
    CANCELLED,
    INTERNAL,
}

@Serializable
data class BridgeError(val type: BridgeErrorType, val message: String? = null)

/**
 * The fixed header carried by every request. The typed, capability-specific payload rides as a JSON
 * string in [RequestEnvelope.payloadJson], decoded by the capability contract on the other side.
 */
@Serializable
data class EnvelopeHeader(
    val capabilityId: String,
    val method: String,
    val protocolVersion: Int = BridgeProtocol.VERSION,
    val contractVersion: Int = 1,
    val token: String? = null,
    val nonce: String? = null,
    val idempotencyKey: String? = null,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
)

@Serializable
data class RequestEnvelope(val header: EnvelopeHeader, val payloadJson: String = "{}")

@Serializable
data class ResponseEnvelope(
    val ok: Boolean,
    val payloadJson: String? = null,
    val error: BridgeError? = null,
)

/** A non-terminal streaming event (e.g. a partial transcript). [kind] is defined by the capability. */
@Serializable
data class EventEnvelope(val kind: String, val payloadJson: String = "{}")

/** Bind-time introduction: the core states who it is; the provider replies with what it offers. */
@Serializable
data class HandshakeHello(
    val corePackage: String,
    val coreProtocolVersion: Int = BridgeProtocol.VERSION,
)

@Serializable
data class HandshakeResult(
    val providerPackage: String,
    val providerProtocolVersion: Int,
    val capabilities: List<String> = emptyList(),
    val providerVersion: String? = null,
)

/** Control operations for a live session opened via openStream. */
@Serializable
enum class SessionOp { STOP, CANCEL }

@Serializable
data class SessionControl(val sessionId: String, val op: SessionOp)

/** Returned (serialized) from openStream: the handle used to control the live session. */
@Serializable
data class SessionHandle(val sessionId: String)
