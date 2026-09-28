package com.wkhan.hexis.bridge.provider

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.BridgeProtocol
import com.wkhan.hexis.bridge.EventEnvelope
import com.wkhan.hexis.bridge.HandshakeResult
import com.wkhan.hexis.bridge.IHexisBridge
import com.wkhan.hexis.bridge.IHexisBridgeCallback
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.ResponseEnvelope
import com.wkhan.hexis.bridge.SessionControl
import com.wkhan.hexis.bridge.StreamSink
import com.wkhan.hexis.bridge.security.SignatureVerifier
import com.wkhan.hexis.bridge.security.VerifiedCaller

/**
 * Base class an addon extends to become a bridge provider. It resolves and verifies the caller
 * (Binder UID -> owning package -> pinned signing keyset), then hands every request to [dispatcher].
 * Subclasses supply the dispatcher and the keyset of cores permitted to bind (normally just Hexis).
 *
 * This is the caller verification the reference transcriber (Scrib) omits: its exported service
 * trusts whoever binds. Ours does not act for an untrusted caller.
 */
abstract class BridgeProviderService : Service() {

    protected abstract val dispatcher: BridgeDispatcher

    /** SHA-256 signing-certificate digests of the core(s) permitted to bind this provider. */
    protected abstract val pinnedCallerKeyset: Set<String>

    private val stub = object : IHexisBridge.Stub() {
        override fun handshake(helloEnvelope: ByteArray): ByteArray {
            verifyCaller() // resolved for symmetry and future audit; the capability list is not secret
            val result = HandshakeResult(
                providerPackage = packageName,
                providerProtocolVersion = BridgeProtocol.VERSION,
                capabilities = dispatcher.capabilityIds(),
                providerVersion = versionName(),
            )
            return BridgeCodec.encode(result)
        }

        override fun invoke(requestEnvelope: ByteArray): ByteArray {
            val caller = verifyCaller()
            val request = BridgeCodec.decode<RequestEnvelope>(requestEnvelope)
            return BridgeCodec.encode(dispatcher.dispatchInvoke(request, caller))
        }

        override fun openStream(requestEnvelope: ByteArray, callback: IHexisBridgeCallback): ByteArray {
            val caller = verifyCaller()
            val request = BridgeCodec.decode<RequestEnvelope>(requestEnvelope)
            val handle = dispatcher.dispatchStream(request, caller, CallbackSink(callback))
            return BridgeCodec.encode(handle)
        }

        override fun controlSession(controlEnvelope: ByteArray) {
            val caller = verifyCaller()
            dispatcher.dispatchControl(BridgeCodec.decode<SessionControl>(controlEnvelope), caller)
        }
    }

    override fun onBind(intent: Intent?): IBinder = stub

    private fun verifyCaller(): VerifiedCaller {
        val uid = Binder.getCallingUid()
        val pkg = SignatureVerifier.packageForUid(this, uid)
            ?: return VerifiedCaller(packageName = "", uid = uid, signatureTrusted = false)
        val trusted = SignatureVerifier.isTrusted(this, pkg, pinnedCallerKeyset)
        return VerifiedCaller(pkg, uid, trusted)
    }

    private fun versionName(): String? =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
}

/** Adapts the oneway AIDL callback to the pure [StreamSink]. A dead client callback is swallowed. */
private class CallbackSink(private val cb: IHexisBridgeCallback) : StreamSink {
    override fun onEvent(event: EventEnvelope) {
        runCatching { cb.onEvent(BridgeCodec.encode(event)) }
    }
    override fun onResult(payloadJson: String) {
        runCatching { cb.onResult(BridgeCodec.encode(ResponseEnvelope(ok = true, payloadJson = payloadJson))) }
    }
    override fun onError(error: BridgeError) {
        runCatching { cb.onError(BridgeCodec.encode(error)) }
    }
}
