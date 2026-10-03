package com.wkhan.hexis.voice.engine

import android.content.Context
import java.io.File

/**
 * Locates the GGML Whisper model the user imported (via SAF, no network) into the addon's private
 * storage at filesDir/stt-model/. A whisper.cpp model is a single `.bin` (e.g. ggml-tiny.en-q5_1.bin).
 */
class ModelStore(context: Context) {

    val dir: File = File(context.filesDir, DIR)

    /** The first `.bin` in the model dir, or null if none imported yet. */
    val model: File? get() = dir.listFiles()?.firstOrNull { it.name.endsWith(".bin") }

    fun isReady(): Boolean = model != null

    companion object {
        const val DIR = "stt-model"
    }
}
