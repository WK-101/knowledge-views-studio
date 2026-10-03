package com.wkhan.hexis.addon

import android.content.Context

import com.wkhan.hexis.bridge.BridgeCodec
import com.wkhan.hexis.bridge.BridgeError
import com.wkhan.hexis.bridge.EnvelopeHeader
import com.wkhan.hexis.bridge.HandshakeHello
import com.wkhan.hexis.bridge.HandshakeResult
import com.wkhan.hexis.bridge.RequestEnvelope
import com.wkhan.hexis.bridge.client.BridgeConnection
import com.wkhan.hexis.bridge.client.DiscoveredProvider
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.VoiceStt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A one-shot reader for a voice addon's self-description ([SttCapabilities]) — engine id/version and the
 * active on-device model name — for display in Settings. `getCapabilities` needs no scope, so no grant
 * token is required; the addon still verifies the caller's signature. Binds, asks once, unbinds.
 */
object VoiceCapabilitiesClient {

    suspend fun fetch(context: Context, provider: DiscoveredProvider): SttCapabilities? =
        withContext(Dispatchers.IO) {
            val conn = BridgeConnection(context.applicationContext)
            val ready = CompletableDeferred<Boolean>()
            conn.connect(
                provider,
                HandshakeHello(corePackage = context.packageName),
                object : BridgeConnection.Listener {
                    override fun onConnected(handshake: HandshakeResult) { ready.complete(true) }
                    override fun onDisconnected() { if (!ready.isCompleted) ready.complete(false) }
                    override fun onError(error: BridgeError) { if (!ready.isCompleted) ready.complete(false) }
                },
            )
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    if (ready.await() != true) return@withTimeoutOrNull null
                    val resp = conn.invoke(
                        RequestEnvelope(
                            EnvelopeHeader(
                                capabilityId = VoiceStt.CAPABILITY,
                                method = VoiceStt.METHOD_GET_CAPABILITIES,
                            ),
                        ),
                    )
                    val payload = resp.payloadJson
                    if (resp.ok && payload != null) {
                        runCatching { BridgeCodec.decodeString<SttCapabilities>(payload) }.getOrNull()
                    } else {
                        null
                    }
                }
            } finally {
                conn.close()
            }
        }

    private const val TIMEOUT_MS = 3000L
}
