package com.wkhan.hexis.voice.engine

import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttErrorType
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttPartial

/**
 * The recognition engine behind the addon. The bridge handler talks only to this interface, so a
 * different engine (e.g. a future streaming one) drops in with no change to the handler, the service,
 * or the contract. The shipping implementation is [WhisperSttEngine] (whisper.cpp, record-then-
 * transcribe); a canned echo stub lives in the test sources for exercising the pipeline offline.
 *
 * Implementations own the microphone and the model. They end with exactly one terminal
 * ([SttListener.onFinal] XOR [SttListener.onError]); a streaming implementation may also emit zero or
 * more [SttListener.onPartial] before it. The current batch engine emits no partials.
 */
interface SttEngine {
    fun capabilities(): SttCapabilities

    /** Begin recognizing for [request]; emit via [listener]; return a session id for stop/cancel. */
    fun startListening(request: ListenRequest, listener: SttListener): String

    fun stop(sessionId: String)

    fun cancel(sessionId: String)

    /** Free any heavyweight native resources (e.g. the loaded model). Default: nothing to release. */
    fun release() {}
}

interface SttListener {
    fun onPartial(partial: SttPartial)
    fun onFinal(result: SttFinal)
    fun onError(type: SttErrorType, message: String?)
}
