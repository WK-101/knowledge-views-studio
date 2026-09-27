package com.wkhan.hexis.bridge

import com.wkhan.hexis.bridge.voice.Hotword
import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttMode
import com.wkhan.hexis.bridge.voice.VoiceStt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 — proves the bridge envelope + typed capability payloads round-trip, that the wire form
 * stays lean, and that the append-only evolution rule (tolerate unknown keys) actually holds. Pure
 * JVM, no Android dependencies.
 */
class BridgeCodecTest {

    @Test fun requestEnvelope_roundTrips_withTypedVoicePayload() {
        val listen = ListenRequest(
            languageHint = "en",
            mode = SttMode.COMMAND,
            hotwords = listOf(Hotword("Groceries", 3.5f), Hotword("Zephyr")),
            silenceTimeoutMs = 1500,
        )
        val env = RequestEnvelope(
            header = EnvelopeHeader(
                capabilityId = VoiceStt.CAPABILITY,
                method = VoiceStt.METHOD_START_LISTENING,
                token = "tok-123",
            ),
            payloadJson = BridgeCodec.encodeString(listen),
        )

        val back = BridgeCodec.decode<RequestEnvelope>(BridgeCodec.encode(env))

        assertEquals(VoiceStt.CAPABILITY, back.header.capabilityId)
        assertEquals(VoiceStt.METHOD_START_LISTENING, back.header.method)
        assertEquals(BridgeProtocol.VERSION, back.header.protocolVersion)
        assertEquals("tok-123", back.header.token)

        val payload = BridgeCodec.decodeString<ListenRequest>(back.payloadJson)
        assertEquals(listen, payload)
        assertEquals("Groceries", payload.hotwords.first().text)
        assertEquals(3.5f, payload.hotwords.first().score, 0.0f)
    }

    @Test fun explicitNulls_areOmitted_soTheWireStaysLean() {
        val header = EnvelopeHeader(capabilityId = "voice.stt", method = "x") // token/nonce/... left null
        val text = BridgeCodec.encodeString(header)
        assertFalse("null optional fields must not be serialized", text.contains("token"))
        assertFalse(text.contains("nonce"))
        assertFalse(text.contains("idempotencyKey"))
    }

    @Test fun unknownKeys_areTolerated_forAppendOnlyEvolution() {
        // A newer peer adds a field an older peer has never heard of; decoding must not throw.
        val futureJson = """{"ok":true,"payloadJson":"{}","futureField":42}"""
        val resp = BridgeCodec.decodeString<ResponseEnvelope>(futureJson)
        assertTrue(resp.ok)
    }

    @Test fun responseEnvelope_carriesTypedError() {
        val resp = ResponseEnvelope(
            ok = false,
            error = BridgeError(BridgeErrorType.UNAUTHORIZED, "missing scope"),
        )
        val back = BridgeCodec.decode<ResponseEnvelope>(BridgeCodec.encode(resp))
        assertFalse(back.ok)
        assertEquals(BridgeErrorType.UNAUTHORIZED, back.error?.type)
        assertNull(back.payloadJson)
    }
}
