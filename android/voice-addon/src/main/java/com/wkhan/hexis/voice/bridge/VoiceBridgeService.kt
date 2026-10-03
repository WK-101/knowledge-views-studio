package com.wkhan.hexis.voice.bridge

import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.provider.BridgeProviderService
import com.wkhan.hexis.voice.VoiceAddon
import com.wkhan.hexis.voice.VoiceAddonSecurity
import com.wkhan.hexis.voice.engine.WhisperSttEngine

/**
 * The addon's bridge entry point. [BridgeProviderService] resolves and verifies the caller (Binder
 * UID -> package -> pinned core keyset) before anything runs; this subclass just supplies the
 * dispatcher (the voice.stt handler + the shared token authority) and the keyset.
 *
 * The engine is the on-device [WhisperSttEngine] (whisper.cpp, built from source); when no model has
 * been imported yet it reports `modelReady = false` and fails listen requests with MODEL_NOT_AVAILABLE
 * so the core can guide the user to install one.
 */
class VoiceBridgeService : BridgeProviderService() {

    override val dispatcher: BridgeDispatcher by lazy {
        BridgeDispatcher(
            handlers = listOf(VoiceSttHandler(WhisperSttEngine(applicationContext))),
            tokens = VoiceAddon.tokenAuthority(applicationContext),
            requireSignatureTrust = VoiceAddonSecurity.requireSignatureTrust,
        )
    }

    override val pinnedCallerKeyset: Set<String> = VoiceAddonSecurity.pinnedCoreKeyset
}
