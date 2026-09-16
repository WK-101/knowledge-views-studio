package com.cairn.reader.domain.transcript

/**
 * An on-device speech-to-text engine that turns local audio into a [Transcript] with no network and
 * no third party — the private-by-design counterpart to fetching a publisher's captions. Cairn ships
 * the abstraction and the model plumbing; a concrete engine (e.g. a whisper.cpp JNI bridge) provides
 * the inference.
 */
interface SpeechToTextEngine {
    /** Whether this build/device can actually run inference (native library present, ABI supported).
     *  When false the UI offers captions only and explains that the speech pack isn't available. */
    fun isSupported(): Boolean

    /** Whether the model this engine needs has been downloaded to the device. */
    suspend fun isModelReady(): Boolean

    /**
     * Transcribe a local audio file entirely on-device. [onProgress] reports 0f..1f. Returns null if
     * the engine can't run here (unsupported build or no model). Never sends audio off the device.
     */
    suspend fun transcribe(audioPath: String, language: String?, onProgress: (Float) -> Unit): Transcript?
}
