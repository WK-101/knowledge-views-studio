package com.wkhan.hexis.addon

import android.content.Context

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.EnvelopeHeader
import com.wkhan.hexis.bridge.EventEnvelope
import com.wkhan.hexis.bridge.HandshakeHello
import com.wkhan.hexis.bridge.HandshakeResult
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.Sensitivity
import com.wkhan.hexis.bridge.SessionControl
import com.wkhan.hexis.bridge.SessionOp
import com.wkhan.hexis.bridge.StreamSink
import com.wkhan.hexis.bridge.client.BridgeConnection
import com.wkhan.hexis.bridge.client.DiscoveredProvider
import com.wkhan.hexis.bridge.voice.Hotword
import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttMode
import com.wkhan.hexis.bridge.voice.SttPartial
import com.wkhan.hexis.bridge.voice.VoiceStt

import java.util.concurrent.Executors

/**
 * Drives a live `voice.stt` session over the bridge: bind the granted addon, open a listening stream,
 * and surface partials + the final transcript to a [Listener]. Only text crosses the bridge; the
 * microphone and audio stay inside the addon. Callbacks arrive on binder threads, so the listener
 * must marshal to the UI thread itself (a StateFlow write is fine).
 */
class VoiceCaptureController(private val appContext: Context) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var connection: BridgeConnection? = null
    @Volatile private var sessionId: String? = null

    fun start(
        provider: DiscoveredProvider,
        token: String,
        hotwords: List<String>,
        mode: SttMode,
        listener: Listener,
    ) {
        close()
        val conn = BridgeConnection(appContext)
        connection = conn
        conn.connect(
            provider,
            HandshakeHello(corePackage = appContext.packageName),
            object : BridgeConnection.Listener {
                override fun onConnected(handshake: HandshakeResult) {
                    // openStream is a binder call — keep it off the main/ServiceConnection thread.
                    io.execute {
                        val request = RequestEnvelope(
                            header = EnvelopeHeader(
                                capabilityId = VoiceStt.CAPABILITY,
                                method = VoiceStt.METHOD_START_LISTENING,
                                token = token,
                                sensitivity = Sensitivity.NO_PERSIST, // the hotword hints must not be persisted
                            ),
                            payloadJson = BridgeCodec.encodeString(
                                ListenRequest(mode = mode, hotwords = hotwords.map { Hotword(it) }),
                            ),
                        )
                        val handle = conn.openStream(request, sink(listener))
                        sessionId = handle.sessionId
                    }
                }

                override fun onDisconnected() = Unit

                override fun onError(error: BridgeError) {
                    listener.onError(error.message ?: error.type.name)
                }
            },
        )
    }

    /** Ask the addon to finalize the current utterance now (e.g. the user released push-to-talk). */
    fun stop() {
        val id = sessionId ?: return
        io.execute { connection?.control(SessionControl(id, SessionOp.STOP)) }
    }

    fun cancel() {
        val id = sessionId ?: return
        io.execute { connection?.control(SessionControl(id, SessionOp.CANCEL)) }
    }

    fun close() {
        val conn = connection
        connection = null
        sessionId = null
        io.execute { conn?.close() }
    }

    private fun sink(listener: Listener): StreamSink = object : StreamSink {
        override fun onEvent(event: EventEnvelope) {
            if (event.kind == VoiceStt.EVENT_PARTIAL) {
                listener.onPartial(BridgeCodec.decodeString<SttPartial>(event.payloadJson).cumulativeText)
            }
        }
        override fun onResult(payloadJson: String) {
            listener.onFinal(BridgeCodec.decodeString<SttFinal>(payloadJson).text)
        }
        override fun onError(error: BridgeError) {
            listener.onError(error.message ?: error.type.name)
        }
    }
}
