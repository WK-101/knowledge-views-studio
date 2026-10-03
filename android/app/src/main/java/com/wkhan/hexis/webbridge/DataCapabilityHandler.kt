package com.wkhan.hexis.webbridge

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.BridgeErrorType
import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.CapabilityHandler
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.ResponseEnvelope
import com.wkhan.hexis.bridge.SessionHandle
import com.wkhan.hexis.bridge.StreamSink
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.security.VerifiedCaller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The core's `data` capability handler. The dispatcher has already verified the caller's signing keyset and
 * that the token is valid; this handler then enforces the **per-domain, read-vs-write scope** against the
 * caller's granted scope set (`data` returns no single `requiredScope`, so the gate can't do it alone) and
 * runs the [DataSource] facade off the binder thread. Every access is reported via [audit].
 *
 * It implements the scope-aware 3-arg forms of [CapabilityHandler]; the dispatcher always calls those.
 */
class DataCapabilityHandler(
    private val source: DataSource,
    private val audit: (callerPackage: String, detail: String, outcome: String) -> Unit = { _, _, _ -> },
) : CapabilityHandler {

    override val capabilityId: String = DataApi.CAPABILITY

    // Authorization depends on the request payload (domain + read/write), so no single gate scope.
    override fun requiredScope(method: String): String? = null

    override fun invoke(request: RequestEnvelope, caller: VerifiedCaller, grantedScopes: Set<String>): ResponseEnvelope =
        when (request.header.method) {
            DataApi.METHOD_QUERY -> handleQuery(request, caller, grantedScopes)
            DataApi.METHOD_MUTATE -> handleMutate(request, caller, grantedScopes)
            else -> ResponseEnvelope(ok = false, error = BridgeError(BridgeErrorType.UNSUPPORTED, request.header.method))
        }

    // W0 has no streaming surface (live updates land in W2 via repository Flows → openStream events).
    override fun openStream(
        request: RequestEnvelope,
        caller: VerifiedCaller,
        sink: StreamSink,
        grantedScopes: Set<String>,
    ): SessionHandle {
        sink.onError(BridgeError(BridgeErrorType.UNSUPPORTED, "data streaming not available"))
        return SessionHandle("")
    }

    // Guard-clause returns read clearest here; the broad catch is deliberate — an IPC/facade boundary must
    // turn any failure into a BridgeError, never crash the provider.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun handleQuery(request: RequestEnvelope, caller: VerifiedCaller, scopes: Set<String>): ResponseEnvelope {
        val query = runCatching { BridgeCodec.decodeString<DataQuery>(request.payloadJson) }.getOrElse {
            return bad("invalid DataQuery")
        }
        val scope = readScope(query.domain)
            ?: return deny(caller, "${query.domain}.${query.op}", BridgeErrorType.UNSUPPORTED, "unknown domain")
        if (scope !in scopes) {
            return deny(caller, "${query.domain}.${query.op}", BridgeErrorType.UNAUTHORIZED, "scope not granted: $scope")
        }
        return try {
            val page = runBlocking(Dispatchers.IO) { source.query(query) }
            audit(caller.packageName, "${query.domain}.${query.op}", "OK")
            ResponseEnvelope(ok = true, payloadJson = BridgeCodec.encodeString(page))
        } catch (e: UnsupportedDomainException) {
            deny(caller, "${query.domain}.${query.op}", BridgeErrorType.UNSUPPORTED, e.message ?: "unsupported")
        } catch (t: Throwable) {
            deny(caller, "${query.domain}.${query.op}", BridgeErrorType.INTERNAL, t.message ?: "error")
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun handleMutate(request: RequestEnvelope, caller: VerifiedCaller, scopes: Set<String>): ResponseEnvelope {
        val mutation = runCatching { BridgeCodec.decodeString<DataMutation>(request.payloadJson) }.getOrElse {
            return bad("invalid DataMutation")
        }
        val scope = writeScope(mutation.domain)
            ?: return deny(caller, "${mutation.domain}.${mutation.op}", BridgeErrorType.UNSUPPORTED, "unknown domain")
        if (scope !in scopes) {
            return deny(caller, "${mutation.domain}.${mutation.op}", BridgeErrorType.UNAUTHORIZED, "scope not granted: $scope")
        }
        return try {
            val result = runBlocking(Dispatchers.IO) { source.mutate(mutation) }
            audit(caller.packageName, "${mutation.domain}.${mutation.op}", if (result.ok) "OK" else "FAIL")
            ResponseEnvelope(
                ok = result.ok,
                payloadJson = BridgeCodec.encodeString(result),
                error = result.error?.let { BridgeError(BridgeErrorType.BAD_REQUEST, it) },
            )
        } catch (t: Throwable) {
            deny(caller, "${mutation.domain}.${mutation.op}", BridgeErrorType.INTERNAL, t.message ?: "error")
        }
    }

    private fun deny(caller: VerifiedCaller, detail: String, type: BridgeErrorType, msg: String): ResponseEnvelope {
        audit(caller.packageName, detail, type.name)
        return ResponseEnvelope(ok = false, error = BridgeError(type, msg))
    }

    private fun bad(msg: String) = ResponseEnvelope(ok = false, error = BridgeError(BridgeErrorType.BAD_REQUEST, msg))

    private fun readScope(domain: String): String? = when (domain) {
        DataApi.DOMAIN_TASKS -> BridgeScopes.DATA_TASKS_READ
        DataApi.DOMAIN_NOTES -> BridgeScopes.DATA_NOTES_READ
        "calendar" -> BridgeScopes.DATA_CALENDAR_READ
        "time" -> BridgeScopes.DATA_TIME_READ
        "habits" -> BridgeScopes.DATA_HABITS_READ
        else -> null
    }

    private fun writeScope(domain: String): String? = when (domain) {
        DataApi.DOMAIN_TASKS -> BridgeScopes.DATA_TASKS_WRITE
        DataApi.DOMAIN_NOTES -> BridgeScopes.DATA_NOTES_WRITE
        "calendar" -> BridgeScopes.DATA_CALENDAR_WRITE
        "time" -> BridgeScopes.DATA_TIME_WRITE
        "habits" -> BridgeScopes.DATA_HABITS_WRITE
        else -> null
    }
}
