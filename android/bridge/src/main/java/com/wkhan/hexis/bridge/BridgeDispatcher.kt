package com.wkhan.hexis.bridge

import com.wkhan.hexis.bridge.security.TokenAuthority
import com.wkhan.hexis.bridge.security.TokenVerdict
import com.wkhan.hexis.bridge.security.VerifiedCaller

/**
 * Transport-agnostic routing + authorization for a provider. Pure Kotlin and fully unit-testable
 * without Android: the AIDL service is a thin adapter that resolves the caller, then calls in here.
 *
 * Every request passes the same gate — signature trust, capability existence, then token + scope —
 * before a handler ever runs, so authorization lives in one place for all capabilities.
 */
class BridgeDispatcher(
    handlers: List<CapabilityHandler>,
    private val tokens: TokenAuthority,
    private val requireSignatureTrust: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val handlers: Map<String, CapabilityHandler> = handlers.associateBy { it.capabilityId }

    fun capabilityIds(): List<String> = handlers.keys.sorted()

    fun dispatchInvoke(request: RequestEnvelope, caller: VerifiedCaller): ResponseEnvelope {
        gate(request.header, caller)?.let { return ResponseEnvelope(ok = false, error = it) }
        return try {
            handlers.getValue(request.header.capabilityId).invoke(request, caller, scopesOf(request.header, caller))
        } catch (t: Throwable) {
            ResponseEnvelope(ok = false, error = BridgeError(BridgeErrorType.INTERNAL, t.message))
        }
    }

    fun dispatchStream(request: RequestEnvelope, caller: VerifiedCaller, sink: StreamSink): SessionHandle {
        gate(request.header, caller)?.let {
            sink.onError(it)
            return SessionHandle("")
        }
        return try {
            handlers.getValue(request.header.capabilityId).openStream(request, caller, sink, scopesOf(request.header, caller))
        } catch (t: Throwable) {
            sink.onError(BridgeError(BridgeErrorType.INTERNAL, t.message))
            SessionHandle("")
        }
    }

    /**
     * The scope set the caller's token carries — so a payload-authorized capability (e.g. `data`, whose
     * methods declare no single [CapabilityHandler.requiredScope]) can check per request. Bound to the
     * authenticated caller: scopes are returned only when the live token's subject is this caller's package,
     * so one trusted peer can never present another's token value to borrow its scopes.
     */
    private fun scopesOf(header: EnvelopeHeader, caller: VerifiedCaller): Set<String> {
        val token = tokens.resolve(header.token) ?: return emptySet()
        return if (token.subjectPackage == caller.packageName) token.scopes else emptySet()
    }

    fun dispatchControl(control: SessionControl, caller: VerifiedCaller) {
        // Control is best-effort; a handler ignores a session it does not own.
        handlers.values.forEach { runCatching { it.control(control, caller) } }
    }

    /** Returns a [BridgeError] if the request must be refused, or null if it may proceed. */
    private fun gate(header: EnvelopeHeader, caller: VerifiedCaller): BridgeError? {
        if (requireSignatureTrust && !caller.signatureTrusted) {
            return BridgeError(BridgeErrorType.UNAUTHENTICATED, "caller signature not trusted")
        }
        val handler = handlers[header.capabilityId]
            ?: return BridgeError(BridgeErrorType.UNSUPPORTED, "unknown capability: ${header.capabilityId}")
        val scope = handler.requiredScope(header.method) ?: return null
        return when (tokens.verify(header.token, caller.packageName, scope, clock())) {
            TokenVerdict.OK -> null
            TokenVerdict.MISSING_SCOPE -> BridgeError(BridgeErrorType.UNAUTHORIZED, "token lacks scope: $scope")
            else -> BridgeError(BridgeErrorType.UNAUTHENTICATED, "invalid or missing token")
        }
    }
}
