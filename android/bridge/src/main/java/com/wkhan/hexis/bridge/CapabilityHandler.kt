package com.wkhan.hexis.bridge

import com.wkhan.hexis.bridge.security.VerifiedCaller

/**
 * Sink for a streaming session: zero or more [onEvent] callbacks, then EXACTLY ONE terminal —
 * [onResult] XOR [onError]. The Android transport adapts the oneway AIDL callback to this interface.
 */
interface StreamSink {
    fun onEvent(event: EventEnvelope)
    fun onResult(payloadJson: String)
    fun onError(error: BridgeError)
}

/**
 * A capability implementation. A new addon capability implements this; the dispatcher and the
 * transport spine do not change. Handlers receive an already-verified caller and should not block
 * the calling binder thread — offload long or streaming work to their own executor and emit via the
 * sink.
 */
interface CapabilityHandler {
    val capabilityId: String

    /** The scope a given method requires, or null if it needs none. */
    fun requiredScope(method: String): String? = null

    fun invoke(request: RequestEnvelope, caller: VerifiedCaller): ResponseEnvelope

    /** Start a streaming session; emit via [sink]; return a handle used to control it. */
    fun openStream(request: RequestEnvelope, caller: VerifiedCaller, sink: StreamSink): SessionHandle

    /** Control a live session (STOP / CANCEL). Default: no-op. */
    fun control(control: SessionControl, caller: VerifiedCaller) {}
}
