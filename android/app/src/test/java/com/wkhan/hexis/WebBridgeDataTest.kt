package com.wkhan.hexis

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.BridgeErrorType
import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.Capabilities
import com.wkhan.hexis.bridge.EnvelopeHeader
import com.wkhan.hexis.bridge.EventEnvelope
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.StreamSink
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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The core's `data` capability, verified headlessly end-to-end through the real dispatcher (signature trust
 * relaxed) with an in-memory token authority and a fake facade. Covers reads, per-domain read/write scope
 * enforcement, unknown-domain handling, caller↔token subject binding, and the live-change stream gate.
 */
class WebBridgeDataTest {

    private val consumer = "com.wkhan.hexis.web"
    private val caller = VerifiedCaller(packageName = consumer, uid = 10_234, signatureTrusted = true)

    private val fakeTask = TaskDto(id = "t1", title = "Write the plan", completed = false)
    private val changeFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** A small functional fake: tasks list + a working `upsert`, plus the change flow for the stream tests. */
    private val source = object : DataSource {
        override suspend fun query(query: DataQuery): DataPage = when {
            query.domain == DataApi.DOMAIN_TASKS && query.op == DataApi.OP_LIST ->
                DataPage(payloadJson = BridgeCodec.encodeString(listOf(fakeTask)), total = 1)
            query.domain == DataApi.DOMAIN_NOTES -> // list + attachment ops all route under notes.read
                DataPage(payloadJson = "[]", total = 0)
            query.op == DataApi.OP_LIST &&
                query.domain in setOf(DataApi.DOMAIN_CALENDAR, DataApi.DOMAIN_TIME, DataApi.DOMAIN_HABITS) ->
                DataPage(payloadJson = "[]", total = 0) // breadth domains (W3) route through the same gate
            else -> throw UnsupportedDomainException("${query.domain}.${query.op}")
        }

        override suspend fun mutate(mutation: DataMutation): DataResult =
            if (mutation.domain == DataApi.DOMAIN_TASKS &&
                mutation.op in setOf(DataApi.OP_UPSERT, DataApi.OP_BULK)
            ) {
                DataResult(ok = true, payloadJson = """{"id":"t1"}""")
            } else {
                DataResult(ok = false, error = "unsupported")
            }

        override fun changes(): Flow<String> = changeFlow
    }

    private fun dispatcher(tokens: InMemoryTokenAuthority) =
        BridgeDispatcher(listOf(DataCapabilityHandler(source)), tokens, requireSignatureTrust = false)

    private fun queryTasks(token: String?): RequestEnvelope = RequestEnvelope(
        header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_QUERY, token = token),
        payloadJson = BridgeCodec.encodeString(DataQuery(domain = DataApi.DOMAIN_TASKS, op = DataApi.OP_LIST)),
    )

    private fun upsertTask(token: String?): RequestEnvelope = RequestEnvelope(
        header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_MUTATE, token = token),
        payloadJson = BridgeCodec.encodeString(DataMutation(domain = DataApi.DOMAIN_TASKS, op = DataApi.OP_UPSERT)),
    )

    // ---- reads + scope ------------------------------------------------------------------------------

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

    // ---- breadth domains (W3) -----------------------------------------------------------------------

    @Test fun calendarRead_withScope_ok() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_CALENDAR_READ))
        val req = RequestEnvelope(
            header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_QUERY, token = t.value),
            payloadJson = BridgeCodec.encodeString(DataQuery(domain = DataApi.DOMAIN_CALENDAR, op = DataApi.OP_LIST)),
        )
        assertTrue(dispatcher(tokens).dispatchInvoke(req, caller).ok)
    }

    @Test fun habitsRead_withoutScope_isUnauthorized() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ)) // no habits scope
        val req = RequestEnvelope(
            header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_QUERY, token = t.value),
            payloadJson = BridgeCodec.encodeString(DataQuery(domain = DataApi.DOMAIN_HABITS, op = DataApi.OP_LIST)),
        )
        val resp = dispatcher(tokens).dispatchInvoke(req, caller)
        assertFalse(resp.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, resp.error?.type)
    }

    @Test fun noteAttachments_useNotesReadScope() {
        val tokens = InMemoryTokenAuthority()
        val attReq = { token: String? ->
            RequestEnvelope(
                header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_QUERY, token = token),
                payloadJson = BridgeCodec.encodeString(
                    DataQuery(domain = DataApi.DOMAIN_NOTES, op = DataApi.OP_ATTACHMENTS, paramsJson = """{"id":"n1"}"""),
                ),
            )
        }
        // with notes.read → allowed
        val ok = tokens.mint(consumer, setOf(BridgeScopes.DATA_NOTES_READ))
        assertTrue(dispatcher(tokens).dispatchInvoke(attReq(ok.value), caller).ok)
        // with only tasks.read → denied
        val no = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ))
        val denied = dispatcher(tokens).dispatchInvoke(attReq(no.value), caller)
        assertFalse(denied.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, denied.error?.type)
    }

    // ---- writes (W2) --------------------------------------------------------------------------------

    @Test fun write_withWriteScope_succeeds() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_WRITE))
        val resp = dispatcher(tokens).dispatchInvoke(upsertTask(t.value), caller)
        assertTrue(resp.ok)
    }

    @Test fun write_withOnlyReadScope_isUnauthorized() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ)) // read can't write
        val resp = dispatcher(tokens).dispatchInvoke(upsertTask(t.value), caller)
        assertFalse(resp.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, resp.error?.type)
    }

    @Test fun bulk_requiresWriteScope() {
        val tokens = InMemoryTokenAuthority()
        val bulk = RequestEnvelope(
            header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_MUTATE, token = null),
            payloadJson = BridgeCodec.encodeString(DataMutation(domain = DataApi.DOMAIN_TASKS, op = DataApi.OP_BULK)),
        )
        // read-only token → denied
        val readTok = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ))
        val denied = dispatcher(tokens).dispatchInvoke(
            bulk.copy(header = bulk.header.copy(token = readTok.value)), caller,
        )
        assertFalse(denied.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, denied.error?.type)
        // write token → reaches the facade
        val writeTok = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_WRITE))
        val ok = dispatcher(tokens).dispatchInvoke(
            bulk.copy(header = bulk.header.copy(token = writeTok.value)), caller,
        )
        assertTrue(ok.ok)
    }

    // ---- live-change stream (W2) --------------------------------------------------------------------

    @Test fun changesStream_emitsTick_forGrantedDomain() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ))
        val events = CopyOnWriteArrayList<EventEnvelope>()
        val latch = CountDownLatch(1)
        val sink = recordingSink(events, latch)

        val handle = dispatcher(tokens).dispatchStream(changesRequest(t.value), caller, sink)
        assertTrue(handle.sessionId.isNotEmpty())

        // Wait until the handler's collector has subscribed before emitting (replay=0 drops earlier ticks).
        runBlocking { withTimeout(AWAIT_MS) { changeFlow.subscriptionCount.first { it > 0 } } }
        runBlocking { changeFlow.emit(DataApi.DOMAIN_TASKS) }

        assertTrue("expected a change tick", latch.await(AWAIT_MS, TimeUnit.MILLISECONDS))
        assertEquals(1, events.size)
        assertEquals(DataApi.EVENT_CHANGED, events.first().kind)
    }

    @Test fun changesStream_filtersUngrantedDomain() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ)) // tasks only
        val events = CopyOnWriteArrayList<EventEnvelope>()
        val sink = recordingSink(events, CountDownLatch(1))

        dispatcher(tokens).dispatchStream(changesRequest(t.value), caller, sink)
        runBlocking { withTimeout(AWAIT_MS) { changeFlow.subscriptionCount.first { it > 0 } } }
        runBlocking { changeFlow.emit(DataApi.DOMAIN_NOTES) } // not readable → must be filtered out
        Thread.sleep(SETTLE_MS)
        assertTrue("notes tick must not reach a tasks-only client", events.isEmpty())
    }

    @Test fun changesStream_withoutReadScope_errors() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_WRITE)) // write only, no read
        val errors = CopyOnWriteArrayList<BridgeError>()
        val sink = errorSink(errors)
        val handle = dispatcher(tokens).dispatchStream(changesRequest(t.value), caller, sink)
        assertEquals("", handle.sessionId)
        assertEquals(1, errors.size)
        assertEquals(BridgeErrorType.UNAUTHORIZED, errors.first().type)
    }

    @Test fun query_nonsenseDomain_viaStreamMethod_isUnsupported() {
        val tokens = InMemoryTokenAuthority()
        val t = tokens.mint(consumer, setOf(BridgeScopes.DATA_TASKS_READ))
        val errors = CopyOnWriteArrayList<BridgeError>()
        val sink = errorSink(errors)
        val req = RequestEnvelope(
            header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = "bogus", token = t.value),
        )
        val handle = dispatcher(tokens).dispatchStream(req, caller, sink)
        assertEquals("", handle.sessionId)
        assertEquals(BridgeErrorType.UNSUPPORTED, errors.firstOrNull()?.type)
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun changesRequest(token: String?): RequestEnvelope = RequestEnvelope(
        header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = DataApi.METHOD_CHANGES, token = token),
    )

    private fun recordingSink(events: MutableList<EventEnvelope>, latch: CountDownLatch) = object : StreamSink {
        override fun onEvent(event: EventEnvelope) { events.add(event); latch.countDown() }
        override fun onResult(payloadJson: String) = Unit
        override fun onError(error: BridgeError) = Unit
    }

    private fun errorSink(errors: MutableList<BridgeError>) = object : StreamSink {
        override fun onEvent(event: EventEnvelope) = Unit
        override fun onResult(payloadJson: String) = Unit
        override fun onError(error: BridgeError) { errors.add(error) }
    }

    private companion object {
        const val AWAIT_MS = 3_000L
        const val SETTLE_MS = 300L
    }
}
