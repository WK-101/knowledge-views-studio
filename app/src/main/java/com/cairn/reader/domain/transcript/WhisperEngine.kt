package com.cairn.reader.domain.transcript

import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device speech-to-text backed by whisper.cpp (open-source GGML weights via [WhisperModelManager]).
 *
 * Inference runs through a native library loaded at runtime. When that library isn't present in the
 * running build, [isSupported] is false and the engine honestly declines rather than fabricating a
 * transcript — the UI then offers captions only and explains that the speech pack ships separately.
 * The JNI bridge slots in behind [transcribeNative] without touching any caller.
 */
@Singleton
class WhisperEngine @Inject constructor(
    private val modelManager: WhisperModelManager,
) : SpeechToTextEngine {

    /** Load the native inference library once; absent in a captions-only build. */
    private val nativeAvailable: Boolean by lazy {
        runCatching { System.loadLibrary(NATIVE_LIB) }.isSuccess
    }

    override fun isSupported(): Boolean = nativeAvailable

    override suspend fun isModelReady(): Boolean = modelManager.anyInstalled()

    override suspend fun transcribe(
        audioPath: String,
        language: String?,
        onProgress: (Float) -> Unit,
    ): Transcript? {
        if (!isSupported()) return null
        val model = modelManager.catalog.firstOrNull { modelManager.isInstalled(it.id) } ?: return null
        val modelPath = modelManager.file(model.id).absolutePath
        // The native bridge (whisper.cpp JNI) is not bundled in this build; when it is, this call
        // returns timed segments we map straight into cues. We never fake a result in the meantime.
        val segments = runCatching { transcribeNative(modelPath, audioPath, language) }.getOrNull() ?: return null
        val cues = segments.mapNotNull { s ->
            val parts = s.split('')
            if (parts.size < 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: start
            TranscriptCue(start, end, parts[2].trim())
        }.filter { it.text.isNotBlank() }
        return if (cues.isEmpty()) null else Transcript(cues, language = language, source = TranscriptSourceKind.ON_DEVICE)
    }

    /** JNI entry point: returns segments as "startMsendMstext". Implemented in native code
     *  when the speech pack is present; the [nativeAvailable] guard keeps it from being called otherwise. */
    private external fun transcribeNative(modelPath: String, audioPath: String, language: String?): Array<String>?

    private companion object {
        const val NATIVE_LIB = "cairn_whisper"
    }
}
