package com.wkhan.hexis.voice.bridge

import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.provider.BridgeProviderService
import com.wkhan.hexis.voice.VoiceAddon
import com.wkhan.hexis.voice.VoiceAddonSecurity
import com.wkhan.hexis.voice.engine.EchoSttEngine

/**
 * The addon's bridge entry point. [BridgeProviderService] resolves and verifies the caller (Binder
 * UID -> package -> pinned core keyset) before anything runs; this subclass just supplies the
 * dispatcher (the voice.stt handler + the shared token authority) and the keyset.
 *
 * Swapping [EchoSttEngine] for the real sherpa-onnx engine is the only change needed to go live.
 */
class VoiceBridgeService : BridgeProviderService() {

    override val dispatcher: BridgeDispatcher by lazy {
        BridgeDispatcher(
            handlers = listOf(VoiceSttHandler(EchoSttEngine())),
            tokens = VoiceAddon.tokenAuthority,
            requireSignatureTrust = VoiceAddonSecurity.requireSignatureTrust,
        )
    }

    override val pinnedCallerKeyset: Set<String> = VoiceAddonSecurity.pinnedCoreKeyset
}
