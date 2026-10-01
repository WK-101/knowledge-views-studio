package com.wkhan.hexis.voice.engine

import android.content.Context
import java.io.File

/**
 * Locates the sherpa-onnx model the user imported (via SAF, no network) into the addon's private
 * storage at filesDir/stt-model/. Filename prefixes are matched so the exact model revision in the
 * name (e.g. encoder-epoch-99-avg-1.int8.onnx) doesn't have to be hard-coded.
 */
class ModelStore(context: Context) {

    val dir: File = File(context.filesDir, DIR)

    val encoder: File? get() = firstOnnx("encoder")
    val decoder: File? get() = firstOnnx("decoder")
    val joiner: File? get() = firstOnnx("joiner")
    val tokens: File get() = File(dir, "tokens.txt")
    val bpeVocab: File get() = File(dir, "bpe.vocab")

    /** The transducer model files needed to recognize at all. */
    fun isReady(): Boolean = encoder != null && decoder != null && joiner != null && tokens.exists()

    /** bpe.vocab present → hotword biasing (modified_beam_search) is available. */
    fun hasBiasing(): Boolean = bpeVocab.exists()

    private fun firstOnnx(prefix: String): File? =
        dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }

    companion object {
        const val DIR = "stt-model"
    }
}
