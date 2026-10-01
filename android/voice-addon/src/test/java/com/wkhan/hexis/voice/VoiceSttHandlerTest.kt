package com.wkhan.hexis.voice

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.BridgeErrorType
import com.wkhan.hexis.bridge.BridgeScopes
import com.wkhan.hexis.bridge.EnvelopeHeader
import com.wkhan.hexis.bridge.EventEnvelope
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.StreamSink
import com.wkhan.hexis.bridge.security.InMemoryTokenAuthority
import com.wkhan.hexis.bridge.security.VerifiedCaller
import com.wkhan.hexis.bridge.voice.Hotword
import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttPartial
import com.wkhan.hexis.bridge.voice.VoiceStt
import com.wkhan.hexis.voice.bridge.VoiceSttHandler
import com.wkhan.hexis.voice.engine.EchoSttEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 — the addon's real voice.stt handler wired to the echo engine, exercised through the bridge
 * dispatcher offline (no Android, no second APK). Swapping in sherpa-onnx must keep all of this green.
 */
class VoiceSttHandlerTest {

    private val core = VerifiedCaller("com.wkhan.hexis", uid = 10001, signatureTrusted = true)

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

    private fun newDispatcher(): Pair<BridgeDispatcher, InMemoryTokenAuthority> {
        val tokens = InMemoryTokenAuthority()
        return BridgeDispatcher(listOf(VoiceSttHandler(EchoSttEngine())), tokens) to tokens
    }

    private fun listenReq(token: String, hotwords: List<Hotword> = emptyList()) = RequestEnvelope(
        header = EnvelopeHeader(VoiceStt.CAPABILITY, VoiceStt.METHOD_START_LISTENING, token = token),
        payloadJson = BridgeCodec.encodeString(ListenRequest(hotwords = hotwords)),
    )

    @Test fun getCapabilities_reportsEchoEngineReady() {
        val (dispatcher, _) = newDispatcher()
        val resp = dispatcher.dispatchInvoke(
            RequestEnvelope(EnvelopeHeader(VoiceStt.CAPABILITY, VoiceStt.METHOD_GET_CAPABILITIES)),
            core,
        )
        assertTrue(resp.ok)
        val caps = BridgeCodec.decodeString<SttCapabilities>(resp.payloadJson!!)
        assertEquals("echo-dev", caps.engineId)
        assertTrue(caps.modelReady)
        assertTrue(caps.biasing)
    }

    @Test fun listen_withToken_streamsPartialsThenFinal() {
        val (dispatcher, tokens) = newDispatcher()
        val token = tokens.mint("com.wkhan.hexis", setOf(BridgeScopes.VOICE_STT_LISTEN))
        val sink = RecordingSink()
        dispatcher.dispatchStream(listenReq(token.value), core, sink)
        assertTrue(sink.partials.isNotEmpty())
        assertEquals(sink.partials.last(), BridgeCodec.decodeString<SttFinal>(sink.finalPayload!!).text)
    }

    @Test fun listen_surfacesHotwordHint_provingBiasingPlumbing() {
        val (dispatcher, tokens) = newDispatcher()
        val token = tokens.mint("com.wkhan.hexis", setOf(BridgeScopes.VOICE_STT_LISTEN))
        val sink = RecordingSink()
        dispatcher.dispatchStream(listenReq(token.value, listOf(Hotword("Groceries", 3.5f))), core, sink)
        val finalText = BridgeCodec.decodeString<SttFinal>(sink.finalPayload!!).text
        assertTrue("engine must receive the biasing hotword hint", finalText.contains("Groceries"))
    }

    @Test fun listen_withoutScope_isRefused() {
        val (dispatcher, tokens) = newDispatcher()
        val token = tokens.mint("com.wkhan.hexis", setOf(BridgeScopes.TASKS_READ))
        val sink = RecordingSink()
        dispatcher.dispatchStream(listenReq(token.value), core, sink)
        assertTrue(sink.partials.isEmpty())
        assertEquals(BridgeErrorType.UNAUTHORIZED, sink.error?.type)
    }
}
