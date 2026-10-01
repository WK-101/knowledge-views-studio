package com.wkhan.hexis.voice.bridge

import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.provider.BridgeProviderService
import com.wkhan.hexis.voice.VoiceAddon
import com.wkhan.hexis.voice.VoiceAddonSecurity
import com.wkhan.hexis.voice.engine.SherpaSttEngine

/**
 * The addon's bridge entry point. [BridgeProviderService] resolves and verifies the caller (Binder
 * UID -> package -> pinned core keyset) before anything runs; this subclass just supplies the
 * dispatcher (the voice.stt handler + the shared token authority) and the keyset.
 *
 * The engine is the real on-device [SherpaSttEngine]; when no model has been imported yet it reports
 * `modelReady = false` and fails listen requests with MODEL_NOT_AVAILABLE so the core can guide the
 * user to install one. ([com.wkhan.hexis.voice.engine.EchoSttEngine] remains for dev/tests.)
 */
class VoiceBridgeService : BridgeProviderService() {

    override val dispatcher: BridgeDispatcher by lazy {
        BridgeDispatcher(
            handlers = listOf(VoiceSttHandler(SherpaSttEngine(applicationContext))),
            tokens = VoiceAddon.tokenAuthority,
            requireSignatureTrust = VoiceAddonSecurity.requireSignatureTrust,
        )
    }

    override val pinnedCallerKeyset: Set<String> = VoiceAddonSecurity.pinnedCoreKeyset
}
