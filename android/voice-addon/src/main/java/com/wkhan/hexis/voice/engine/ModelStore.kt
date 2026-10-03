package com.wkhan.hexis.voice.engine

import android.content.Context
import java.io.File

/**
 * Locates the GGML Whisper model the user imported (via SAF, no network) into the addon's private
 * storage at filesDir/stt-model/. A whisper.cpp model is a single `.bin` (e.g. ggml-tiny.en-q5_1.bin).
 */
class ModelStore(context: Context) {

    val dir: File = File(context.filesDir, DIR)

    /** Every imported `.bin` model, sorted by name (a user may keep both tiny and base, say). */
    val models: List<File>
        get() = dir.listFiles()?.filter { it.name.endsWith(".bin") }?.sortedBy { it.name } ?: emptyList()

    /**
     * The model to use. When several are imported, prefer the more accurate tier (base > small > medium),
     * otherwise fall back to whatever is present (e.g. tiny). This lets a user upgrade accuracy simply by
     * importing a better model — it is picked up automatically, no setting to flip.
     */
    val model: File?
        get() {
            val all = models
            return all.firstOrNull { it.name.contains("base", ignoreCase = true) }
                ?: all.firstOrNull { it.name.contains("small", ignoreCase = true) }
                ?: all.firstOrNull { it.name.contains("medium", ignoreCase = true) }
                ?: all.firstOrNull()
        }

    /** Human-readable name of the active model (for the core to display), or null if none. */
    val modelName: String? get() = model?.name

    fun isReady(): Boolean = model != null

    companion object {
        const val DIR = "stt-model"
    }
}
