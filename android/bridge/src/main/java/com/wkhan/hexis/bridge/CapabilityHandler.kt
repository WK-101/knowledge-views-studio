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

    /**
     * A single scope the dispatcher checks at the gate for a given method, or null if the method needs no
     * single fixed scope. A capability whose authorization depends on the request *payload* (e.g. `data`,
     * where the required scope is per domain + read/write) returns null here and instead checks
     * [grantedScopes] itself inside [invoke] / [openStream].
     */
    fun requiredScope(method: String): String? = null

    // A handler implements EXACTLY ONE of each pair below:
    //  • the 2-arg form when a single [requiredScope] already covers authorization (e.g. voice.stt), or
    //  • the 3-arg form when it must check the caller's granted scope set per request (e.g. data).
    // The dispatcher always calls the 3-arg form; its default delegates to the 2-arg one, and the 2-arg
    // default throws so a scope-checking handler isn't silently called without its scopes.

    fun invoke(request: RequestEnvelope, caller: VerifiedCaller): ResponseEnvelope =
        error("CapabilityHandler must implement invoke(request, caller) or invoke(request, caller, grantedScopes)")

    fun invoke(request: RequestEnvelope, caller: VerifiedCaller, grantedScopes: Set<String>): ResponseEnvelope =
        invoke(request, caller)

    /** Start a streaming session; emit via [sink]; return a handle used to control it. */
    fun openStream(request: RequestEnvelope, caller: VerifiedCaller, sink: StreamSink): SessionHandle =
        error("CapabilityHandler must implement openStream(...) or openStream(..., grantedScopes)")

    fun openStream(
        request: RequestEnvelope,
        caller: VerifiedCaller,
        sink: StreamSink,
        grantedScopes: Set<String>,
    ): SessionHandle = openStream(request, caller, sink)

    /** Control a live session (STOP / CANCEL). Default: no-op. */
    fun control(control: SessionControl, caller: VerifiedCaller) {}
}
