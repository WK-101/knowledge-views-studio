package com.wkhan.hexis.voice.engine

import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttErrorType
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttPartial

/**
 * The recognition engine behind the addon. The bridge handler talks only to this interface, so the
 * real sherpa-onnx engine drops in later with no change to the handler, the service, or the contract.
 *
 * Implementations own the microphone and the model; they emit cumulative partials followed by exactly
 * one terminal ([SttListener.onFinal] XOR [SttListener.onError]).
 */
interface SttEngine {
    fun capabilities(): SttCapabilities

    /** Begin recognizing for [request]; emit via [listener]; return a session id for stop/cancel. */
    fun startListening(request: ListenRequest, listener: SttListener): String

    fun stop(sessionId: String)

    fun cancel(sessionId: String)
}

interface SttListener {
    fun onPartial(partial: SttPartial)
    fun onFinal(result: SttFinal)
    fun onError(type: SttErrorType, message: String?)
}
