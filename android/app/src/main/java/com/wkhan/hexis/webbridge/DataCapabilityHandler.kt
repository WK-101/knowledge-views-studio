package com.wkhan.hexis.webbridge

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.BridgeErrorType
import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.CapabilityHandler
import com.wkhan.hexis.bridge.EventEnvelope
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.ResponseEnvelope
import com.wkhan.hexis.bridge.SessionControl
import com.wkhan.hexis.bridge.SessionHandle
import com.wkhan.hexis.bridge.StreamSink
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.security.VerifiedCaller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    // One collector per open `changes` stream. Cancelled on control(STOP/CANCEL) — which the provider base
    // also routes on consumer-process death — so a dropped client never leaves a Flow collecting forever.
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Job>()

    override fun invoke(request: RequestEnvelope, caller: VerifiedCaller, grantedScopes: Set<String>): ResponseEnvelope =
        when (request.header.method) {
            DataApi.METHOD_QUERY -> handleQuery(request, caller, grantedScopes)
            DataApi.METHOD_MUTATE -> handleMutate(request, caller, grantedScopes)
            else -> ResponseEnvelope(ok = false, error = BridgeError(BridgeErrorType.UNSUPPORTED, request.header.method))
        }

    /**
     * The `changes` stream: emits a [DataApi.EVENT_CHANGED] tick (`{"domain":…}`) whenever a domain the
     * caller may read changes, so the consumer can live-refresh. Requires at least one read scope; each tick
     * is additionally filtered to domains in the caller's granted set, so a tasks-only grant never learns
     * that notes changed. The collector is cancelled on [control] (STOP/CANCEL) or consumer death.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override fun openStream(
        request: RequestEnvelope,
        caller: VerifiedCaller,
        sink: StreamSink,
        grantedScopes: Set<String>,
    ): SessionHandle {
        if (request.header.method != DataApi.METHOD_CHANGES) {
            sink.onError(BridgeError(BridgeErrorType.UNSUPPORTED, "no such stream: ${request.header.method}"))
            return SessionHandle("")
        }
        val readable = DOMAINS.filter { readScope(it) in grantedScopes }.toSet()
        if (readable.isEmpty()) {
            sink.onError(BridgeError(BridgeErrorType.UNAUTHORIZED, "no readable domain granted"))
            return SessionHandle("")
        }
        val sessionId = UUID.randomUUID().toString()
        val job = streamScope.launch {
            try {
                source.changes().collect { domain ->
                    if (domain in readable) {
                        sink.onEvent(EventEnvelope(DataApi.EVENT_CHANGED, BridgeCodec.encodeString(DomainTick(domain))))
                    }
                }
            } catch (t: Throwable) {
                runCatching { sink.onError(BridgeError(BridgeErrorType.INTERNAL, t.message ?: "stream error")) }
            } finally {
                sessions.remove(sessionId)
            }
        }
        sessions[sessionId] = job
        audit(caller.packageName, "changes:${readable.joinToString("+")}", "OPEN")
        return SessionHandle(sessionId)
    }

    override fun control(control: SessionControl, caller: VerifiedCaller) {
        sessions.remove(control.sessionId)?.cancel()
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
        DataApi.DOMAIN_CALENDAR -> BridgeScopes.DATA_CALENDAR_READ
        DataApi.DOMAIN_TIME -> BridgeScopes.DATA_TIME_READ
        DataApi.DOMAIN_HABITS -> BridgeScopes.DATA_HABITS_READ
        else -> null
    }

    private fun writeScope(domain: String): String? = when (domain) {
        DataApi.DOMAIN_TASKS -> BridgeScopes.DATA_TASKS_WRITE
        DataApi.DOMAIN_NOTES -> BridgeScopes.DATA_NOTES_WRITE
        DataApi.DOMAIN_CALENDAR -> BridgeScopes.DATA_CALENDAR_WRITE
        DataApi.DOMAIN_TIME -> BridgeScopes.DATA_TIME_WRITE
        DataApi.DOMAIN_HABITS -> BridgeScopes.DATA_HABITS_WRITE
        else -> null
    }

    private companion object {
        /** Domains the `changes` stream can tick for. */
        val DOMAINS = listOf(
            DataApi.DOMAIN_TASKS,
            DataApi.DOMAIN_NOTES,
            DataApi.DOMAIN_CALENDAR,
            DataApi.DOMAIN_TIME,
            DataApi.DOMAIN_HABITS,
        )
    }
}

/** The payload of a [DataApi.EVENT_CHANGED] tick. */
@kotlinx.serialization.Serializable
private data class DomainTick(val domain: String)
