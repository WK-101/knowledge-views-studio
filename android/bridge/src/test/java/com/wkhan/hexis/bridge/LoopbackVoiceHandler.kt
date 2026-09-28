package com.wkhan.hexis.bridge

import com.wkhan.hexis.bridge.security.VerifiedCaller
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttPartial
import com.wkhan.hexis.bridge.voice.VoiceStt

/**
 * In-process fake `voice.stt` provider. It lets the whole bus (gate + authorization + dispatch +
 * streaming) be exercised in a plain JVM test with no second APK and no Android IPC hop — the one
 * hop unit tests could never cover anyway. Promotes to a shared testFixture in Phase 5.
 */
class LoopbackVoiceHandler(
    private val transcript: String = "add milk to groceries",
) : CapabilityHandler {

    override val capabilityId: String = VoiceStt.CAPABILITY

    override fun requiredScope(method: String): String? =
        if (method == VoiceStt.METHOD_START_LISTENING) BridgeScopes.VOICE_STT_LISTEN else null

    override fun invoke(request: RequestEnvelope, caller: VerifiedCaller): ResponseEnvelope =
        when (request.header.method) {
            VoiceStt.METHOD_GET_CAPABILITIES -> ResponseEnvelope(
                ok = true,
                payloadJson = BridgeCodec.encodeString(
                    SttCapabilities(
                        engineId = "loopback",
                        engineVersion = "0",
                        supportedLanguages = listOf("en"),
                        streaming = true,
                        biasing = true,
                        modelReady = true,
                    ),
                ),
            )
            else -> ResponseEnvelope(
                ok = false,
                error = BridgeError(BridgeErrorType.UNSUPPORTED, request.header.method),
            )
        }

    override fun openStream(request: RequestEnvelope, caller: VerifiedCaller, sink: StreamSink): SessionHandle {
        val cumulative = StringBuilder()
        for (word in transcript.split(" ")) {
            if (cumulative.isNotEmpty()) cumulative.append(' ')
            cumulative.append(word)
            sink.onEvent(EventEnvelope(VoiceStt.EVENT_PARTIAL, BridgeCodec.encodeString(SttPartial(cumulative.toString()))))
        }
        sink.onResult(BridgeCodec.encodeString(SttFinal(text = transcript, confidence = 0.95f)))
        return SessionHandle("loopback-1")
    }
}
