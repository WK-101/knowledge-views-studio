package com.wkhan.hexis.voice.bridge

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
import com.wkhan.hexis.bridge.SessionOp
import com.wkhan.hexis.bridge.StreamSink
import com.wkhan.hexis.bridge.security.VerifiedCaller
import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttErrorType
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttPartial
import com.wkhan.hexis.bridge.voice.VoiceStt
import com.wkhan.hexis.voice.engine.SttEngine
import com.wkhan.hexis.voice.engine.SttListener

/**
 * The `voice.stt` capability, engine-agnostic. It adapts the pure bridge contract to whatever
 * [SttEngine] is injected — today the on-device whisper.cpp engine ([WhisperSttEngine]) — so swapping
 * the engine never touches the bridge or the core. Only text crosses the bridge; audio stays in the
 * engine.
 *
 * Note: the contract models streaming partials ([EventEnvelope] of kind [VoiceStt.EVENT_PARTIAL]), but
 * the current record-then-transcribe engine emits none and ends with a single [onResult]; the partial
 * path is kept wired for a future streaming/VAD engine.
 */
class VoiceSttHandler(private val engine: SttEngine) : CapabilityHandler {

    override val capabilityId: String = VoiceStt.CAPABILITY

    override fun requiredScope(method: String): String? =
        if (method == VoiceStt.METHOD_START_LISTENING) BridgeScopes.VOICE_STT_LISTEN else null

    override fun invoke(request: RequestEnvelope, caller: VerifiedCaller): ResponseEnvelope =
        when (request.header.method) {
            VoiceStt.METHOD_GET_CAPABILITIES -> ResponseEnvelope(
                ok = true,
                payloadJson = BridgeCodec.encodeString(engine.capabilities()),
            )
            else -> ResponseEnvelope(
                ok = false,
                error = BridgeError(BridgeErrorType.UNSUPPORTED, request.header.method),
            )
        }

    override fun openStream(request: RequestEnvelope, caller: VerifiedCaller, sink: StreamSink): SessionHandle {
        val listenRequest = runCatching { BridgeCodec.decodeString<ListenRequest>(request.payloadJson) }
            .getOrElse {
                sink.onError(BridgeError(BridgeErrorType.BAD_REQUEST, "invalid ListenRequest"))
                return SessionHandle("")
            }
        val sessionId = engine.startListening(
            listenRequest,
            object : SttListener {
                override fun onPartial(partial: SttPartial) {
                    sink.onEvent(EventEnvelope(VoiceStt.EVENT_PARTIAL, BridgeCodec.encodeString(partial)))
                }
                override fun onFinal(result: SttFinal) {
                    sink.onResult(BridgeCodec.encodeString(result))
                }
                override fun onError(type: SttErrorType, message: String?) {
                    sink.onError(BridgeError(type.toBridgeErrorType(), message))
                }
            },
        )
        return SessionHandle(sessionId)
    }

    override fun control(control: SessionControl, caller: VerifiedCaller) {
        when (control.op) {
            SessionOp.STOP -> engine.stop(control.sessionId)
            SessionOp.CANCEL -> engine.cancel(control.sessionId)
        }
    }

    private fun SttErrorType.toBridgeErrorType(): BridgeErrorType = when (this) {
        SttErrorType.MODEL_NOT_AVAILABLE, SttErrorType.MIC_UNAVAILABLE -> BridgeErrorType.UNAVAILABLE
        SttErrorType.PERMISSION_DENIED -> BridgeErrorType.UNAUTHORIZED
        SttErrorType.UNSUPPORTED_LANGUAGE -> BridgeErrorType.UNSUPPORTED
        SttErrorType.CANCELLED -> BridgeErrorType.CANCELLED
        SttErrorType.INTERNAL -> BridgeErrorType.INTERNAL
    }
}
