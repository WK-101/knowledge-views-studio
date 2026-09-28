package com.wkhan.hexis.bridge

import com.wkhan.hexis.bridge.security.InMemoryTokenAuthority
import com.wkhan.hexis.bridge.security.VerifiedCaller
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttPartial
import com.wkhan.hexis.bridge.voice.VoiceStt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 — the full provider-side path end to end via the loopback handler: signature gate, token +
 * scope authorization, capability routing, and streaming (partials then a single final).
 */
class BridgeDispatcherTest {

    private val trustedCore = VerifiedCaller("com.wkhan.hexis", uid = 10123, signatureTrusted = true)

    private class RecordingSink : StreamSink {
        val partials = mutableListOf<String>()
        var finalPayload: String? = null
        var error: BridgeError? = null
        override fun onEvent(event: EventEnvelope) {
            if (event.kind == VoiceStt.EVENT_PARTIAL) {
                partials += BridgeCodec.decodeString<SttPartial>(event.payloadJson).cumulativeText
            }
        }
        override fun onResult(payloadJson: String) { finalPayload = payloadJson }
        override fun onError(error: BridgeError) { this.error = error }
    }

    private fun req(method: String, token: String? = null) = RequestEnvelope(
        header = EnvelopeHeader(capabilityId = VoiceStt.CAPABILITY, method = method, token = token),
    )

    @Test fun getCapabilities_needsNoScope_andSucceeds() {
        val dispatcher = BridgeDispatcher(listOf(LoopbackVoiceHandler()), InMemoryTokenAuthority())
        val resp = dispatcher.dispatchInvoke(req(VoiceStt.METHOD_GET_CAPABILITIES), trustedCore)
        assertTrue(resp.ok)
    }

    @Test fun listen_withGrantedToken_streamsPartialsThenFinal() {
        val tokens = InMemoryTokenAuthority()
        val dispatcher = BridgeDispatcher(listOf(LoopbackVoiceHandler()), tokens)
        val token = tokens.mint("com.wkhan.hexis", setOf(BridgeScopes.VOICE_STT_LISTEN))
        val sink = RecordingSink()

        dispatcher.dispatchStream(req(VoiceStt.METHOD_START_LISTENING, token.value), trustedCore, sink)

        assertTrue(sink.partials.isNotEmpty())
        assertEquals("add milk to groceries", sink.partials.last())
        assertEquals("add milk to groceries", BridgeCodec.decodeString<SttFinal>(sink.finalPayload!!).text)
        assertNull(sink.error)
    }

    @Test fun listen_withoutToken_isRefusedWithoutStarting() {
        val dispatcher = BridgeDispatcher(listOf(LoopbackVoiceHandler()), InMemoryTokenAuthority())
        val sink = RecordingSink()

        dispatcher.dispatchStream(req(VoiceStt.METHOD_START_LISTENING, token = null), trustedCore, sink)

        assertTrue("no audio work must start without authorization", sink.partials.isEmpty())
        assertEquals(BridgeErrorType.UNAUTHENTICATED, sink.error?.type)
    }

    @Test fun listen_withTokenLackingScope_isUnauthorized() {
        val tokens = InMemoryTokenAuthority()
        val dispatcher = BridgeDispatcher(listOf(LoopbackVoiceHandler()), tokens)
        val token = tokens.mint("com.wkhan.hexis", setOf(BridgeScopes.TASKS_READ))
        val sink = RecordingSink()

        dispatcher.dispatchStream(req(VoiceStt.METHOD_START_LISTENING, token.value), trustedCore, sink)

        assertTrue(sink.partials.isEmpty())
        assertEquals(BridgeErrorType.UNAUTHORIZED, sink.error?.type)
    }

    @Test fun untrustedCaller_isRejected() {
        val dispatcher = BridgeDispatcher(listOf(LoopbackVoiceHandler()), InMemoryTokenAuthority())
        val untrusted = VerifiedCaller("com.evil.clone", uid = 10999, signatureTrusted = false)

        val resp = dispatcher.dispatchInvoke(req(VoiceStt.METHOD_GET_CAPABILITIES), untrusted)

        assertFalse(resp.ok)
        assertEquals(BridgeErrorType.UNAUTHENTICATED, resp.error?.type)
    }

    @Test fun unknownCapability_isUnsupported() {
        val dispatcher = BridgeDispatcher(listOf(LoopbackVoiceHandler()), InMemoryTokenAuthority())
        val resp = dispatcher.dispatchInvoke(
            RequestEnvelope(EnvelopeHeader(capabilityId = "does.not.exist", method = "x")),
            trustedCore,
        )
        assertEquals(BridgeErrorType.UNSUPPORTED, resp.error?.type)
    }
}
