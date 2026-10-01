package com.wkhan.hexis.voice.engine

import com.wkhan.hexis.bridge.voice.ListenRequest
import com.wkhan.hexis.bridge.voice.SttCapabilities
import com.wkhan.hexis.bridge.voice.SttFinal
import com.wkhan.hexis.bridge.voice.SttPartial

/**
 * Dev engine: no microphone, no model. It emits a canned transcript as cumulative partials then a
 * final, so the end-to-end bridge + (future) core analysis pipeline can be exercised offline and
 * on-device before sherpa-onnx is wired in. It also surfaces the first biasing hotword in its output
 * to prove the core -> engine vocabulary-hint plumbing reaches the engine.
 */
class EchoSttEngine : SttEngine {

    override fun capabilities(): SttCapabilities = SttCapabilities(
        engineId = "echo-dev",
        engineVersion = "0",
        supportedLanguages = listOf("en"),
        streaming = true,
        biasing = true,
        modelReady = true,
    )

    override fun startListening(request: ListenRequest, listener: SttListener): String {
        val sessionId = "echo-${System.nanoTime()}"
        val phrase = cannedPhrase(request)
        val cumulative = StringBuilder()
        for (word in phrase.split(" ")) {
            if (cumulative.isNotEmpty()) cumulative.append(' ')
            cumulative.append(word)
            listener.onPartial(SttPartial(cumulative.toString()))
        }
        listener.onFinal(SttFinal(text = phrase, confidence = 1f))
        return sessionId
    }

    override fun stop(sessionId: String) = Unit

    override fun cancel(sessionId: String) = Unit

    private fun cannedPhrase(request: ListenRequest): String {
        val hinted = request.hotwords.firstOrNull()?.text
        return if (hinted != null) "add buy milk to $hinted" else "add buy milk tomorrow at 5 pm"
    }
}
