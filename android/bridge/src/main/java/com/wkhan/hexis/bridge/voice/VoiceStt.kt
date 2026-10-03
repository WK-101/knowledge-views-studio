package com.wkhan.hexis.bridge.voice

import kotlinx.serialization.Serializable

/**
 * The `voice.stt` capability contract, v1.
 *
 * Only text ever crosses the bridge; the microphone and the raw audio buffer stay inside the addon.
 * The core drives [METHOD_START_LISTENING] over openStream: the addon captures the mic itself and
 * streams partial transcripts, then a single final result. Analysis (intent + slot parsing) happens
 * in the core, not here.
 */
object VoiceStt {
    const val CAPABILITY = "voice.stt"
    const val CONTRACT_VERSION = 1

    const val METHOD_GET_CAPABILITIES = "getCapabilities"
    const val METHOD_START_LISTENING = "startListening"

    /** EventEnvelope.kind for a streaming partial transcript (payload = [SttPartial]). */
    const val EVENT_PARTIAL = "partial"
}

@Serializable
data class SttCapabilities(
    val engineId: String,
    val engineVersion: String,
    val supportedLanguages: List<String> = emptyList(),
    val streaming: Boolean = true,
    val biasing: Boolean = false,
    val modelReady: Boolean = false,
    /** Name of the active on-device model (e.g. "ggml-base.en-q5_1.bin"), for the core to display. */
    val modelName: String? = null,
)

@Serializable
enum class SttMode { COMMAND, DICTATION }

/**
 * A per-utterance recognition hint. Ephemeral by design: the core sends the user's own vocabulary
 * (project / tag / context names) with each listen request so the engine can bias toward them, and
 * the addon must not persist it (envelope Sensitivity = NO_PERSIST).
 */
@Serializable
data class Hotword(val text: String, val score: Float = 1.5f)

@Serializable
data class ListenRequest(
    val languageHint: String? = null,
    val mode: SttMode = SttMode.COMMAND,
    val hotwords: List<Hotword> = emptyList(),
    val silenceTimeoutMs: Int = 0,
)

@Serializable
data class SttPartial(val cumulativeText: String)

@Serializable
data class SttFinal(
    val text: String,
    val confidence: Float = -1f,
    val tokenConfidences: List<Float> = emptyList(),
)

@Serializable
enum class SttErrorType {
    MODEL_NOT_AVAILABLE,
    MIC_UNAVAILABLE,
    PERMISSION_DENIED,
    UNSUPPORTED_LANGUAGE,
    CANCELLED,
    INTERNAL,
}
