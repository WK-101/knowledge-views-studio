package com.wkhan.hexis.voice.bridge

import com.wkhan.hexis.bridge.BridgeDispatcher
import com.wkhan.hexis.bridge.provider.BridgeProviderService
import com.wkhan.hexis.bridge.security.BridgeTrust
import com.wkhan.hexis.voice.VoiceAddon
import com.wkhan.hexis.voice.engine.WhisperSttEngine

/**
 * The addon's bridge entry point. [BridgeProviderService] resolves and verifies the caller (Binder
 * UID -> package -> pinned core keyset) before anything runs; this subclass just supplies the
 * dispatcher (the voice.stt handler + the shared token authority) and the keyset.
 *
 * The engine is the on-device [WhisperSttEngine] (whisper.cpp, built from source); when no model has
 * been imported yet it reports `modelReady = false` and fails listen requests with MODEL_NOT_AVAILABLE
 * so the core can guide the user to install one. The engine keeps its model warm only briefly after a
 * capture; here we also free it on memory pressure and teardown so the addon never sits on native RAM.
 */
class VoiceBridgeService : BridgeProviderService() {

    private val engine by lazy { WhisperSttEngine(applicationContext) }

    override val dispatcher: BridgeDispatcher by lazy {
        BridgeDispatcher(
            handlers = listOf(VoiceSttHandler(engine)),
            tokens = VoiceAddon.tokenAuthority(applicationContext),
            requireSignatureTrust = BridgeTrust.requireSignatureTrust(applicationContext),
        )
    }

    override val pinnedCallerKeyset: Set<String> = BridgeTrust.HEXIS_KEYSET

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) engine.release()
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}
