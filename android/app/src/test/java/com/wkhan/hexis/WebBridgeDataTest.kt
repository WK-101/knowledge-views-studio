package com.wkhan.hexis

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.BridgeErrorType
import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.Capabilities
import com.wkhan.hexis.bridge.EnvelopeHeader
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataPage
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.data.DataResult
import com.wkhan.hexis.bridge.data.TaskDto
import com.wkhan.hexis.bridge.security.InMemoryTokenAuthority
import com.wkhan.hexis.bridge.security.VerifiedCaller
import com.wkhan.hexis.webbridge.DataCapabilityHandler
import com.wkhan.hexis.webbridge.DataSource
import com.wkhan.hexis.webbridge.UnsupportedDomainException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W0 — the core's `data` capability, verified headlessly end-to-end through the real dispatcher (signature
 * trust relaxed) with an in-memory token authority and a fake facade. Covers the facade, per-domain scope
 * enforcement, unknown-domain handling, and caller↔token subject binding.
 */
class WebBridgeDataTest {

    private val consumer = "com.wkhan.hexis.web"
    private val caller = VerifiedCaller(packageName = consumer, uid = 10_234, signatureTrusted = true)

    private val fakeTask = TaskDto(id = "t1", title = "Write the plan", completed = false)

    private val source = object : DataSource {
        override suspend fun query(query: DataQuery): DataPage = when {
            query.domain == DataApi.DOMAIN_TASKS && query.op == DataApi.OP_LIST ->
                DataPage(payloadJson = BridgeCodec.encodeString(listOf(fakeTask)), total = 1)
            else -> throw UnsupportedDomainException("${query.domain}.${query.op}")
        }
        override suspend fun mutate(mutation: DataMutation): DataResult = DataResult(ok = false, error = "writes_not_enabled")
    }

    private fun dispatcher(tokens: InMemoryTokenAuthority) =
        BridgeDispatcher(listOf(DataCapabilityHandler(source)), tokens, requireSignatureTrust = false)

    private fun queryTasks(token: String?): RequestEnvelope = RequestEnvelope(
        header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_QUERY, token = token),
        payloadJson = BridgeCodec.encodeString(DataQuery(domain = DataApi.DOMAIN_TASKS, op = DataApi.OP_LIST)),
    )

    @Test fun grantedRead_returnsData() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ))
        val resp = dispatcher(tokens).dispatchInvoke(queryTasks(t.value), caller)
        assertTrue(resp.ok)
        val page = BridgeCodec.decodeString<DataPage>(resp.payloadJson!!)
        val tasks = BridgeCodec.decodeString<List<TaskDto>>(page.payloadJson)
        assertEquals(1, tasks.size)
        assertEquals("Write the plan", tasks.first().title)
    }

    @Test fun withoutScope_isUnauthorized() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_NOTES_READ)) // notes only, not tasks
        val resp = dispatcher(tokens).dispatchInvoke(queryTasks(t.value), caller)
        assertFalse(resp.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, resp.error?.type)
    }

    @Test fun noToken_isUnauthorized() {
        val tokens = InMemoryTokenAuthority()
        val resp = dispatcher(tokens).dispatchInvoke(queryTasks(null), caller)
        assertFalse(resp.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, resp.error?.type)
    }

    @Test fun anotherPackagesToken_isNotHonored() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint("com.evil.other", setOf(BridgeScopes.DATA_TASKS_READ)) // minted for a different pkg
        val resp = dispatcher(tokens).dispatchInvoke(queryTasks(t.value), caller) // presented by `consumer`
        assertFalse(resp.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, resp.error?.type)
    }

    @Test fun unknownDomain_isUnsupported() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, BridgeScopes.DATA_ALL)
        val req = RequestEnvelope(
            header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_QUERY, token = t.value),
            payloadJson = BridgeCodec.encodeString(DataQuery(domain = "secrets", op = DataApi.OP_LIST)),
        )
        val resp = dispatcher(tokens).dispatchInvoke(req, caller)
        assertFalse(resp.ok)
        assertEquals(BridgeErrorType.UNSUPPORTED, resp.error?.type)
    }

    @Test fun writes_areRejectedInW0() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, BridgeScopes.DATA_ALL)
        val req = RequestEnvelope(
            header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_MUTATE, token = t.value),
            payloadJson = BridgeCodec.encodeString(DataMutation(domain = DataApi.DOMAIN_TASKS, op = DataApi.OP_UPSERT)),
        )
        val resp = dispatcher(tokens).dispatchInvoke(req, caller)
        assertFalse(resp.ok) // facade returns writes_not_enabled in W0
    }
}
