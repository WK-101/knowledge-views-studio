package com.wkhan.hexis.bridge.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.BridgeErrorType
import com.wkhan.hexis.bridge.BridgeProtocol
import com.wkhan.hexis.bridge.EventEnvelope
import com.wkhan.hexis.bridge.HandshakeHello
import com.wkhan.hexis.bridge.HandshakeResult
import com.wkhan.hexis.bridge.IHexisBridge
import com.wkhan.hexis.bridge.IHexisBridgeCallback
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.ResponseEnvelope
import com.wkhan.hexis.bridge.SessionControl
import com.wkhan.hexis.bridge.SessionHandle
import com.wkhan.hexis.bridge.StreamSink

/**
 * Client-side binding to a discovered provider. Bind is asynchronous (see [Listener]); [invoke] and
 * [openStream] are binder calls and must run off the main thread. One connection per provider; call
 * [close] when done. The visible Bridge Registry (Phase 1) wraps this in the core's coroutine layer.
 */
class BridgeConnection(private val context: Context) {

    interface Listener {
        fun onConnected(handshake: HandshakeResult)
        fun onDisconnected()
        fun onError(error: BridgeError)
    }

    @Volatile private var binder: IHexisBridge? = null
    private var connection: ServiceConnection? = null

    fun connect(provider: DiscoveredProvider, hello: HandshakeHello, listener: Listener) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val b = IHexisBridge.Stub.asInterface(service)
                binder = b
                try {
                    val result = BridgeCodec.decode<HandshakeResult>(b.handshake(BridgeCodec.encode(hello)))
                    listener.onConnected(result)
                } catch (t: Throwable) {
                    listener.onError(BridgeError(BridgeErrorType.UNAVAILABLE, t.message))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                listener.onDisconnected()
            }
        }
        connection = conn
        val intent = Intent(BridgeProtocol.PROVIDER_ACTION)
            .setClassName(provider.packageName, provider.className)
        val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            listener.onError(BridgeError(BridgeErrorType.UNAVAILABLE, "bindService refused"))
        }
    }

    fun invoke(request: RequestEnvelope): ResponseEnvelope {
        val b = binder ?: return ResponseEnvelope(
            ok = false,
            error = BridgeError(BridgeErrorType.UNAVAILABLE, "not connected"),
        )
        return BridgeCodec.decode(b.invoke(BridgeCodec.encode(request)))
    }

    fun openStream(request: RequestEnvelope, sink: StreamSink): SessionHandle {
        val b = binder ?: run {
            sink.onError(BridgeError(BridgeErrorType.UNAVAILABLE, "not connected"))
            return SessionHandle("")
        }
        val cb = object : IHexisBridgeCallback.Stub() {
            override fun onEvent(eventEnvelope: ByteArray) {
                runCatching { sink.onEvent(BridgeCodec.decode<EventEnvelope>(eventEnvelope)) }
            }
            override fun onResult(resultEnvelope: ByteArray) {
                runCatching { sink.onResult(BridgeCodec.decode<ResponseEnvelope>(resultEnvelope).payloadJson ?: "{}") }
            }
            override fun onError(errorEnvelope: ByteArray) {
                runCatching { sink.onError(BridgeCodec.decode<BridgeError>(errorEnvelope)) }
            }
        }
        return BridgeCodec.decode(b.openStream(BridgeCodec.encode(request), cb))
    }

    fun control(control: SessionControl) {
        binder?.controlSession(BridgeCodec.encode(control))
    }

    fun close() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        binder = null
    }
}
