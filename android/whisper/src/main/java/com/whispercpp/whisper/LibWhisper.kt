package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

/**
 * Thin Kotlin wrapper over whisper.cpp's JNI (vendored, built from source). whisper.cpp requires that
 * a context is used from only one thread at a time, so every call is confined to a single-thread
 * dispatcher.
 */
class WhisperContext private constructor(private var ptr: Long) {
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    )

    /**
     * Transcribe 16 kHz mono float samples to text. [prompt] biases recognition toward the caller's
     * own vocabulary (whisper initial prompt); pass "" for none.
     */
    suspend fun transcribeData(data: FloatArray, prompt: String = ""): String =
        withContext(scope.coroutineContext) {
            require(ptr != 0L)
            val numThreads = WhisperCpuConfig.preferredThreadCount
            Log.d(LOG_TAG, "Transcribing ${data.size} samples on $numThreads threads")
            WhisperLib.fullTranscribe(ptr, numThreads, data, prompt)
            val textCount = WhisperLib.getTextSegmentCount(ptr)
            buildString {
                for (i in 0 until textCount) append(WhisperLib.getTextSegment(ptr, i))
            }.trim()
        }

    /** Blocking convenience for non-coroutine callers (the addon's capture thread). */
    fun transcribeBlocking(data: FloatArray, prompt: String = ""): String =
        runBlocking { transcribeData(data, prompt) }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    /** Blocking convenience for non-coroutine callers (keeps the coroutines dependency inside :whisper). */
    fun releaseBlocking() = runBlocking { release() }

    protected fun finalize() {
        runBlocking { release() }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) throw RuntimeException("Couldn't create whisper context from $filePath")
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            if (ptr == 0L) throw RuntimeException("Couldn't create whisper context from stream")
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) throw RuntimeException("Couldn't create whisper context from asset $assetPath")
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

private class WhisperLib {
    companion object {
        init {
            // arm64-v8a is the only ABI we ship; prefer the fp16-optimized library when the CPU has it.
            var loadV8fp16 = false
            if (Build.SUPPORTED_ABIS[0].equals("arm64-v8a")) {
                val cpuInfo = cpuInfo()
                if (cpuInfo?.contains("fphp") == true) loadV8fp16 = true
            }
            if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        external fun initContext(modelPath: String): Long
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, prompt: String)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getSystemInfo(): String
    }
}

private fun cpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}
