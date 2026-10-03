package com.wkhan.hexis.web.bridge

import android.content.Context

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.Capabilities
import com.wkhan.hexis.bridge.EnvelopeHeader
import com.wkhan.hexis.bridge.HandshakeHello
import com.wkhan.hexis.bridge.HandshakeResult
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.ResponseEnvelope
import com.wkhan.hexis.bridge.client.BridgeConnection
import com.wkhan.hexis.bridge.client.BridgeDiscovery
import com.wkhan.hexis.bridge.data.DataApi
import com.wkhan.hexis.bridge.data.DataMutation
import com.wkhan.hexis.bridge.data.DataQuery
import com.wkhan.hexis.bridge.security.BridgeTrust

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The addon's client to the CORE's `data` provider. Discovers the core (by the pinned Hexis keyset),
 * binds it over the bridge, and runs `data` queries/mutations carrying the grant token from [GrantStore].
 * Holds no data itself — it is a pass-through the web server calls per request.
 */
class DataBridgeClient(private val appContext: Context) {

    private val grants = GrantStore(appContext)

    @Volatile private var connection: BridgeConnection? = null
    @Volatile private var connected = false

    /** Is a trusted core `data` provider installed to connect to? */
    fun providerAvailable(): Boolean =
        BridgeDiscovery.providersFor(appContext, Capabilities.DATA, BridgeTrust.HEXIS_KEYSET).isNotEmpty()

    /** Bind the core provider (blocking, bounded). Returns true once the handshake completes. */
    @Synchronized
    fun connect(): Boolean {
        if (connected && connection != null) return true
        val provider = BridgeDiscovery.providersFor(appContext, Capabilities.DATA, BridgeTrust.HEXIS_KEYSET)
            .firstOrNull() ?: return false
        val conn = BridgeConnection(appContext)
        val ready = CompletableDeferred<Boolean>()
        conn.connect(
            provider,
            HandshakeHello(corePackage = appContext.packageName),
            object : BridgeConnection.Listener {
                override fun onConnected(handshake: HandshakeResult) { if (!ready.isCompleted) ready.complete(true) }
                override fun onDisconnected() { connected = false; if (!ready.isCompleted) ready.complete(false) }
                override fun onError(error: BridgeError) { if (!ready.isCompleted) ready.complete(false) }
            },
        )
        val ok = runBlocking { withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() } == true }
        connection = if (ok) conn else { conn.close(); null }
        connected = ok
        return ok
    }

    /** Run a read. Returns the response envelope (payloadJson is a serialized [com.wkhan.hexis.bridge.data.DataPage]). */
    fun query(query: DataQuery): ResponseEnvelope = invoke(DataApi.METHOD_QUERY, BridgeCodec.encodeString(query))

    /** Run a write (W2+). payloadJson is a serialized [com.wkhan.hexis.bridge.data.DataResult]. */
    fun mutate(mutation: DataMutation): ResponseEnvelope = invoke(DataApi.METHOD_MUTATE, BridgeCodec.encodeString(mutation))

    private fun invoke(method: String, payloadJson: String): ResponseEnvelope {
        val conn = connection ?: if (connect()) connection!! else return unavailable()
        val request = RequestEnvelope(
            header = EnvelopeHeader(capabilityId = Capabilities.DATA, method = method, token = grants.token),
            payloadJson = payloadJson,
        )
        return runCatching { conn.invoke(request) }.getOrElse { unavailable() }
    }

    fun close() {
        connection?.close()
        connection = null
        connected = false
    }

    private fun unavailable() = ResponseEnvelope(
        ok = false,
        error = BridgeError(com.wkhan.hexis.bridge.BridgeErrorType.UNAVAILABLE, "core not connected"),
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4000L
    }
}
